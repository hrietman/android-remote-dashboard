package nl.hrietman.androidremotedashboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import nl.hrietman.androidremotedashboard.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity(), RemoteDashboardView.TouchSink {
    companion object {
        private const val MAX_RECOVERY_ATTEMPTS = 3
        private const val RELAUNCH_DELAY_MS = 1_500L
    }

    private lateinit var binding: ActivityMainBinding
    private var sessionClient: RemoteSessionClient? = null
    private var currentConfig: AppConfig? = null
    private var latestFrameId: Long = 0
    private var latestRenderMs: Long = 0
    private var latestFps: Double = 0.0
    private var latestMqttStatus: String = "MQTT disabled"
    private var isActivityVisible = false
    private var relaunchScheduled = false
    private var relaunchInProgress = false
    private var recoveryAttempt = 0
    private var isPopulatingSettings = false
    private var runtimeConfigDirty = false
    private var mqttConfigDirty = false
    private var hasRenderedFrame = false
    private var idleActive = false
    private var forcedWakeUntilMs = 0L
    private var suppressDisconnectRecoveryUntilMs = 0L
    private var systemBrightnessBeforeIdle: Int? = null
    private var systemBrightnessModeBeforeIdle: Int? = null
    private var latestEnhancedDimmingPermissionGranted = false
    private val restartHandler = Handler(Looper.getMainLooper())
    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable { enterIdleMode() }
    private var mqttReceiverRegistered = false
    private val mqttStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RemoteDashboardService.ACTION_MQTT_STATUS) return
            val message = intent.getStringExtra(RemoteDashboardService.EXTRA_STATUS).orEmpty()
            latestMqttStatus = message
            binding.mqttStatusText.text = message
            val config = currentConfig ?: AppConfig.DEFAULT
            if (!hasRenderedFrame) {
                showStartupOverlay(
                    binding.statusText.text?.toString().orEmpty().ifBlank { "Connecting" },
                    binding.startupDashboardText.text?.toString().orEmpty().ifBlank { config.normalizedInitialUrl() },
                    config,
                )
            }
        }
    }
    private val relaunchAppRunnable = Runnable {
        relaunchScheduled = false
        relaunchInProgress = false
        if (!isFinishing && !isDestroyed) {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                recreate()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyKioskWindowFlags()

        binding.dashboardView.touchSink = this
        binding.settingsButton.setOnClickListener { openSettingsOverlay() }
        binding.closeSettingsButton.setOnClickListener { closeSettingsOverlay() }
        binding.idleOverlay.setOnClickListener { wakeFromIdle(resetForcedWake = true) }
        binding.debugPanel.setOnLongClickListener {
            toggleDebugPanel()
            true
        }

        currentConfig = AppConfig.load(this)
        binding.startupVersion.text = "v${appVersionName()}"
        refreshEnhancedDimmingPermissionStatus()
        applyUiConfig(currentConfig ?: AppConfig.DEFAULT)
        bindSettingsUi()
        handleWakeIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWakeIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        isActivityVisible = true
        relaunchScheduled = false
        relaunchInProgress = false
        refreshEnhancedDimmingPermissionStatus()
        applyKioskWindowFlags()
        updateKeepScreenOnState(keepScreenOn = true)
        registerMqttStatusReceiver()
        startOrUpdateMqttService()
        binding.dashboardView.post {
            startSession(forceRestart = false)
            scheduleIdleTimeout()
        }
    }

    override fun onPause() {
        super.onPause()
        isActivityVisible = false
        if (!relaunchInProgress) {
            restartHandler.removeCallbacks(relaunchAppRunnable)
            relaunchScheduled = false
        }
        idleHandler.removeCallbacks(idleRunnable)
        unregisterMqttStatusReceiver()
        sessionClient?.stop()
        sessionClient = null
    }

    override fun onRemoteTouch(kind: Int, x: Int, y: Int) {
        noteUserInteraction()
        if (idleActive) {
            wakeFromIdle(resetForcedWake = true)
            return
        }
        sessionClient?.sendTouch(kind, x, y)
    }

    override fun onBackPressed() {
        if (binding.settingsOverlay.visibility == View.VISIBLE) {
            closeSettingsOverlay()
            return
        }
        super.onBackPressed()
    }

    private fun startSession(forceRestart: Boolean) {
        val viewWidth = binding.dashboardView.width
        val viewHeight = binding.dashboardView.height
        val config = currentConfig ?: AppConfig.load(this)
        if (viewWidth <= 0 || viewHeight <= 0) return

        if (forceRestart || sessionClient == null) {
            hasRenderedFrame = false
            showStartupOverlay("Connecting", config.normalizedInitialUrl(), config)
        }
        binding.dashboardView.configure(viewWidth, viewHeight)
        if (!forceRestart && sessionClient != null) return

        if (forceRestart && sessionClient != null) {
            suppressDisconnectRecoveryUntilMs = System.currentTimeMillis() + 3_000L
        }
        sessionClient?.stop()
        sessionClient = RemoteSessionClient(config, viewWidth, viewHeight, object : RemoteSessionClient.Listener {
            override fun onConnecting(message: String) {
                runOnUiThread {
                    binding.statusText.text = message
                    showStartupOverlay(message, config.normalizedInitialUrl(), config)
                }
            }

            override fun onConnected() {
                runOnUiThread {
                    restartHandler.removeCallbacks(relaunchAppRunnable)
                    relaunchScheduled = false
                    relaunchInProgress = false
                    recoveryAttempt = 0
                    binding.statusText.text = "Connected"
                    showStartupOverlay("Connected", config.normalizedInitialUrl(), config)
                }
            }

            override fun onDisconnected(message: String) {
                runOnUiThread {
                    if (System.currentTimeMillis() < suppressDisconnectRecoveryUntilMs) {
                        binding.statusText.text = message
                        return@runOnUiThread
                    }
                    val status = recoveryStatus(message)
                    binding.statusText.text = status
                    hasRenderedFrame = false
                    showStartupOverlay(status, config.normalizedInitialUrl(), config)
                    if (isActivityVisible) {
                        sessionClient?.stop()
                        sessionClient = null
                        if (!relaunchScheduled && recoveryAttempt < MAX_RECOVERY_ATTEMPTS) {
                            recoveryAttempt += 1
                            relaunchScheduled = true
                            relaunchInProgress = true
                            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_HOME)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            startActivity(homeIntent)
                            restartHandler.removeCallbacks(relaunchAppRunnable)
                            restartHandler.postDelayed(relaunchAppRunnable, RELAUNCH_DELAY_MS)
                        }
                    }
                }
            }

            override fun onFrame(frame: AssembledFrame) {
                runOnUiThread {
                    latestFrameId = frame.frameId
                    binding.dashboardView.applyFrame(frame)
                    if (!hasRenderedFrame) {
                        hasRenderedFrame = true
                        binding.startupOverlay.visibility = View.GONE
                    }
                    renderStats()
                }
            }

            override fun onCurrentUrl(url: String) {
                runOnUiThread {
                    binding.urlText.text = url
                    if (!hasRenderedFrame) {
                        showStartupOverlay(
                            binding.statusText.text?.toString().orEmpty().ifBlank { "Connecting" },
                            url,
                            config,
                        )
                    }
                }
            }

            override fun onStats(stats: StreamStats) {
                runOnUiThread {
                    latestFps = stats.fps
                    if (stats.lastFrameRenderMs > 0) {
                        latestRenderMs = stats.lastFrameRenderMs
                    }
                    if (stats.frameId > 0) {
                        latestFrameId = stats.frameId
                    }
                    renderStats()
                }
            }
        }).also { it.start() }
    }

    private fun bindSettingsUi() {
        binding.wsUrlInput.doAfterTextChanged {
            persistRuntimeConfigFromInputs()
        }
        binding.deviceIdInput.doAfterTextChanged {
            persistRuntimeConfigFromInputs()
        }
        binding.startUrlInput.doAfterTextChanged {
            persistRuntimeConfigFromInputs()
        }
        binding.jpegQualityInput.doAfterTextChanged {
            persistRuntimeConfigFromInputs()
        }
        binding.tileSizeInput.doAfterTextChanged {
            persistRuntimeConfigFromInputs()
        }
        binding.frameIntervalInput.doAfterTextChanged {
            persistRuntimeConfigFromInputs()
        }
        binding.mqttHostInput.doAfterTextChanged {
            persistMqttConfigFromInputs()
        }
        binding.mqttPortInput.doAfterTextChanged {
            persistMqttConfigFromInputs()
        }
        binding.mqttUsernameInput.doAfterTextChanged {
            persistMqttConfigFromInputs()
        }
        binding.mqttPasswordInput.doAfterTextChanged {
            persistMqttConfigFromInputs()
        }
        binding.mqttBaseTopicInput.doAfterTextChanged {
            persistMqttConfigFromInputs()
        }
        binding.mqttClientIdInput.doAfterTextChanged {
            persistMqttConfigFromInputs()
        }
        binding.screenOffTimeoutInput.doAfterTextChanged {
            persistIdleConfigFromInputs()
        }
        binding.enhancedIdleDimmingSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isPopulatingSettings) return@setOnCheckedChangeListener
            val updated = buildConfigFromInputs().copy(enhancedIdleDimmingEnabled = isChecked)
            persistConfig(updated, runtimeChanged = false)
            if (isChecked && !canWriteSystemSettings()) {
                openWriteSettingsPermissionScreen()
            }
            refreshEnhancedDimmingPermissionStatus()
            noteUserInteraction()
        }
        binding.debugOverlaySwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isPopulatingSettings) return@setOnCheckedChangeListener
            val updated = (currentConfig ?: AppConfig.DEFAULT).copy(showDebugOverlay = isChecked)
            persistConfig(updated, runtimeChanged = false)
            noteUserInteraction()
        }
        binding.mqttEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isPopulatingSettings) return@setOnCheckedChangeListener
            val updated = buildConfigFromInputs().copy(mqttEnabled = isChecked)
            persistConfig(updated, runtimeChanged = false, mqttChanged = true)
            noteUserInteraction()
        }
        binding.mqttSslSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isPopulatingSettings) return@setOnCheckedChangeListener
            val updated = buildConfigFromInputs().copy(mqttUseSsl = isChecked)
            persistConfig(updated, runtimeChanged = false, mqttChanged = true)
            noteUserInteraction()
        }
        binding.brightnessSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (isPopulatingSettings) return
                val updated = (currentConfig ?: AppConfig.DEFAULT).copy(screenBrightnessPercent = progress + 1)
                persistConfig(updated, runtimeChanged = false)
                noteUserInteraction()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        binding.configButtonPositionGroup.setOnCheckedChangeListener { _, checkedId ->
            if (isPopulatingSettings) return@setOnCheckedChangeListener
            val position = if (checkedId == binding.configButtonRight.id) {
                AppConfig.BUTTON_POSITION_RIGHT
            } else {
                AppConfig.BUTTON_POSITION_LEFT
            }
            val updated = (currentConfig ?: AppConfig.DEFAULT).copy(configButtonPosition = position)
            persistConfig(updated, runtimeChanged = false)
            noteUserInteraction()
        }
    }

    private fun openSettingsOverlay() {
        idleHandler.removeCallbacks(idleRunnable)
        populateSettingsForm(currentConfig ?: AppConfig.load(this))
        binding.settingsOverlay.visibility = View.VISIBLE
    }

    private fun closeSettingsOverlay() {
        binding.settingsOverlay.visibility = View.GONE
        var shouldRecreate = false
        if (runtimeConfigDirty) {
            runtimeConfigDirty = false
            shouldRecreate = true
        }
        if (mqttConfigDirty) {
            mqttConfigDirty = false
            startOrUpdateMqttService()
        }
        if (shouldRecreate) {
            binding.root.post { recreate() }
            return
        }
        scheduleIdleTimeout()
    }

    private fun populateSettingsForm(config: AppConfig) {
        isPopulatingSettings = true
        binding.wsUrlInput.setText(config.websocketUrl)
        binding.deviceIdInput.setText(config.deviceId)
        binding.startUrlInput.setText(config.initialUrl)
        binding.jpegQualityInput.setText(config.jpegQuality.toString())
        binding.tileSizeInput.setText(config.tileSize.toString())
        binding.frameIntervalInput.setText(config.minFrameIntervalMs.toString())
        binding.mqttEnabledSwitch.isChecked = config.mqttEnabled
        binding.mqttHostInput.setText(config.mqttHost)
        binding.mqttPortInput.setText(config.mqttPort.toString())
        binding.mqttSslSwitch.isChecked = config.mqttUseSsl
        binding.mqttUsernameInput.setText(config.mqttUsername)
        binding.mqttPasswordInput.setText(config.mqttPassword)
        binding.mqttBaseTopicInput.setText(config.mqttBaseTopic)
        binding.mqttClientIdInput.setText(config.mqttClientId)
        binding.screenOffTimeoutInput.setText(config.screenOffTimeoutSeconds.toString())
        binding.enhancedIdleDimmingSwitch.isChecked = config.enhancedIdleDimmingEnabled
        binding.debugOverlaySwitch.isChecked = config.showDebugOverlay
        binding.brightnessSlider.progress = config.screenBrightnessPercent - 1
        binding.brightnessValue.text = "${config.screenBrightnessPercent}%"
        binding.configButtonPositionGroup.check(
            if (config.configButtonPosition == AppConfig.BUTTON_POSITION_RIGHT) {
                binding.configButtonRight.id
            } else {
                binding.configButtonLeft.id
            },
        )
        isPopulatingSettings = false
    }

    private fun buildConfigFromInputs(): AppConfig {
        return AppConfig(
            websocketUrl = binding.wsUrlInput.text?.toString()?.trim().orEmpty().ifBlank { AppConfig.DEFAULT.websocketUrl },
            deviceId = binding.deviceIdInput.text?.toString()?.trim().orEmpty().ifBlank { AppConfig.DEFAULT.deviceId },
            initialUrl = binding.startUrlInput.text?.toString()?.trim().orEmpty().ifBlank { AppConfig.DEFAULT.initialUrl },
            jpegQuality = binding.jpegQualityInput.text?.toString()?.toIntOrNull()?.coerceIn(1, 100)
                ?: AppConfig.DEFAULT.jpegQuality,
            tileSize = binding.tileSizeInput.text?.toString()?.toIntOrNull()?.coerceAtLeast(8)
                ?: AppConfig.DEFAULT.tileSize,
            minFrameIntervalMs = binding.frameIntervalInput.text?.toString()?.toIntOrNull()?.coerceAtLeast(0)
                ?: AppConfig.DEFAULT.minFrameIntervalMs,
            mqttEnabled = binding.mqttEnabledSwitch.isChecked,
            mqttHost = binding.mqttHostInput.text?.toString()?.trim().orEmpty(),
            mqttPort = binding.mqttPortInput.text?.toString()?.toIntOrNull() ?: AppConfig.DEFAULT.mqttPort,
            mqttUseSsl = binding.mqttSslSwitch.isChecked,
            mqttUsername = binding.mqttUsernameInput.text?.toString()?.trim().orEmpty(),
            mqttPassword = binding.mqttPasswordInput.text?.toString().orEmpty(),
            mqttBaseTopic = binding.mqttBaseTopicInput.text?.toString()?.trim().orEmpty()
                .ifBlank { AppConfig.DEFAULT.mqttBaseTopic },
            mqttClientId = binding.mqttClientIdInput.text?.toString()?.trim().orEmpty(),
            screenOffTimeoutSeconds = binding.screenOffTimeoutInput.text?.toString()?.toIntOrNull()
                ?: AppConfig.DEFAULT.screenOffTimeoutSeconds,
            enhancedIdleDimmingEnabled = binding.enhancedIdleDimmingSwitch.isChecked,
            showDebugOverlay = binding.debugOverlaySwitch.isChecked,
            configButtonPosition = if (binding.configButtonPositionGroup.checkedRadioButtonId == binding.configButtonRight.id) {
                AppConfig.BUTTON_POSITION_RIGHT
            } else {
                AppConfig.BUTTON_POSITION_LEFT
            },
            screenBrightnessPercent = binding.brightnessSlider.progress + 1,
        )
    }

    private fun persistRuntimeConfigFromInputs() {
        if (isPopulatingSettings) return
        val updated = buildConfigFromInputs()
        persistConfig(updated, runtimeChanged = true)
        noteUserInteraction()
    }

    private fun persistMqttConfigFromInputs() {
        if (isPopulatingSettings) return
        val updated = buildConfigFromInputs()
        persistConfig(updated, runtimeChanged = false, mqttChanged = true)
        noteUserInteraction()
    }

    private fun persistIdleConfigFromInputs() {
        if (isPopulatingSettings) return
        val updated = buildConfigFromInputs()
        persistConfig(updated, runtimeChanged = false)
        scheduleIdleTimeout()
        noteUserInteraction()
    }

    private fun persistConfig(updated: AppConfig, runtimeChanged: Boolean, mqttChanged: Boolean = false) {
        currentConfig = updated
        AppConfig.save(this, updated)
        applyUiConfig(updated)
        if (runtimeChanged) {
            runtimeConfigDirty = true
        }
        if (mqttChanged) {
            mqttConfigDirty = true
        }
    }

    private fun applyUiConfig(config: AppConfig) {
        binding.debugPanel.visibility = if (config.showDebugOverlay && !idleActive) View.VISIBLE else View.GONE
        binding.settingsButton.visibility = if (idleActive) View.GONE else View.VISIBLE
        binding.brightnessValue.text = "${config.screenBrightnessPercent}%"
        binding.mqttStatusText.text = latestMqttStatus
        updateEnhancedDimmingPermissionText(config)
        if (!hasRenderedFrame) {
            showStartupOverlay(
                binding.statusText.text?.toString().orEmpty().ifBlank { "Connecting" },
                config.normalizedInitialUrl(),
                config,
            )
        }
        val params = binding.settingsButton.layoutParams as FrameLayout.LayoutParams
        params.gravity = if (config.configButtonPosition == AppConfig.BUTTON_POSITION_RIGHT) {
            Gravity.BOTTOM or Gravity.END
        } else {
            Gravity.BOTTOM or Gravity.START
        }
        binding.settingsButton.layoutParams = params
        applyScreenBrightness(if (idleActive) 1 else config.screenBrightnessPercent)
    }

    private fun applyScreenBrightness(percent: Int) {
        val normalized = (percent.coerceIn(1, 100) / 100f).coerceIn(0.01f, 1f)
        val layoutParams = window.attributes
        layoutParams.screenBrightness = normalized
        window.attributes = layoutParams
    }

    private fun showStartupOverlay(status: String, dashboardUrl: String, config: AppConfig) {
        binding.startupStatusText.text = status
        binding.startupDashboardText.text = dashboardUrl
        if (config.mqttEnabled) {
            binding.startupMqttStatusText.text = latestMqttStatus
            binding.startupMqttStatusText.visibility = View.VISIBLE
        } else {
            binding.startupMqttStatusText.visibility = View.GONE
        }
        binding.startupOverlay.visibility = View.VISIBLE
    }

    private fun scheduleIdleTimeout() {
        idleHandler.removeCallbacks(idleRunnable)
        if (!isActivityVisible || binding.settingsOverlay.visibility == View.VISIBLE) return
        if (idleActive) return
        val config = currentConfig ?: AppConfig.DEFAULT
        val now = System.currentTimeMillis()
        if (forcedWakeUntilMs > now) {
            idleHandler.postDelayed(idleRunnable, forcedWakeUntilMs - now)
            return
        }
        forcedWakeUntilMs = 0L
        idleHandler.postDelayed(idleRunnable, config.screenOffTimeoutSeconds * 1000L)
    }

    private fun noteUserInteraction() {
        if (forcedWakeUntilMs > 0L) {
            forcedWakeUntilMs = 0L
        }
        scheduleIdleTimeout()
    }

    private fun enterIdleMode() {
        if (binding.settingsOverlay.visibility == View.VISIBLE) return
        idleActive = true
        binding.idleOverlay.visibility = View.VISIBLE
        applyEnhancedIdleSystemDimmingIfNeeded()
        applyUiConfig(currentConfig ?: AppConfig.DEFAULT)
        updateKeepScreenOnState(keepScreenOn = false)
    }

    private fun wakeFromIdle(resetForcedWake: Boolean) {
        if (resetForcedWake) {
            forcedWakeUntilMs = 0L
        }
        idleActive = false
        updateKeepScreenOnState(keepScreenOn = true)
        binding.idleOverlay.visibility = View.GONE
        restoreSystemBrightnessIfNeeded()
        applyUiConfig(currentConfig ?: AppConfig.DEFAULT)
        scheduleIdleTimeout()
    }

    private fun canWriteSystemSettings(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(this)
        } else {
            true
        }
    }

    private fun openWriteSettingsPermissionScreen() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val intent = Intent(
            Settings.ACTION_MANAGE_WRITE_SETTINGS,
            Uri.parse("package:$packageName"),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun refreshEnhancedDimmingPermissionStatus() {
        latestEnhancedDimmingPermissionGranted = canWriteSystemSettings()
        updateEnhancedDimmingPermissionText(currentConfig ?: AppConfig.DEFAULT)
    }

    private fun updateEnhancedDimmingPermissionText(config: AppConfig) {
        binding.enhancedIdleDimmingPermissionText.text = if (config.enhancedIdleDimmingEnabled) {
            if (latestEnhancedDimmingPermissionGranted) {
                getString(R.string.enhanced_idle_dimming_permission_granted)
            } else {
                getString(R.string.enhanced_idle_dimming_permission_required)
            }
        } else {
            ""
        }
    }

    private fun applyEnhancedIdleSystemDimmingIfNeeded() {
        val config = currentConfig ?: AppConfig.DEFAULT
        if (!config.enhancedIdleDimmingEnabled || !latestEnhancedDimmingPermissionGranted) return
        try {
            if (systemBrightnessBeforeIdle == null) {
                systemBrightnessBeforeIdle = Settings.System.getInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                )
            }
            if (systemBrightnessModeBeforeIdle == null) {
                systemBrightnessModeBeforeIdle = Settings.System.getInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                )
            }
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                1,
            )
        } catch (_: Exception) {
        }
    }

    private fun restoreSystemBrightnessIfNeeded() {
        val brightness = systemBrightnessBeforeIdle
        val mode = systemBrightnessModeBeforeIdle
        if (!latestEnhancedDimmingPermissionGranted || (brightness == null && mode == null)) return
        try {
            if (brightness != null) {
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    brightness,
                )
            }
            if (mode != null) {
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    mode,
                )
            }
        } catch (_: Exception) {
        } finally {
            systemBrightnessBeforeIdle = null
            systemBrightnessModeBeforeIdle = null
        }
    }

    private fun appVersionName(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "dev"
        } catch (_: Exception) {
            "dev"
        }
    }

    private fun renderStats() {
        binding.statsText.text = String.format(
            Locale.US,
            "%.1f fps | %d ms | frame %d",
            latestFps,
            latestRenderMs,
            latestFrameId,
        )
    }

    private fun toggleDebugPanel() {
        val updated = (currentConfig ?: AppConfig.DEFAULT).copy(
            showDebugOverlay = binding.debugPanel.visibility != View.VISIBLE,
        )
        persistConfig(updated, runtimeChanged = false)
        populateSettingsForm(updated)
    }

    private fun recoveryStatus(message: String): String {
        return if (recoveryAttempt <= 0) {
            message
        } else {
            "$message • Retry $recoveryAttempt/$MAX_RECOVERY_ATTEMPTS"
        }
    }

    private fun updateKeepScreenOnState(keepScreenOn: Boolean) {
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun registerMqttStatusReceiver() {
        if (mqttReceiverRegistered) return
        val filter = IntentFilter(RemoteDashboardService.ACTION_MQTT_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mqttStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(mqttStatusReceiver, filter)
        }
        mqttReceiverRegistered = true
    }

    private fun unregisterMqttStatusReceiver() {
        if (!mqttReceiverRegistered) return
        unregisterReceiver(mqttStatusReceiver)
        mqttReceiverRegistered = false
    }

    private fun startOrUpdateMqttService() {
        val config = currentConfig ?: AppConfig.load(this)
        val action = if (config.mqttEnabled) {
            RemoteDashboardService.ACTION_START_OR_UPDATE
        } else {
            RemoteDashboardService.ACTION_STOP
        }
        val intent = Intent(this, RemoteDashboardService::class.java).apply {
            this.action = action
        }
        if (config.mqttEnabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } else {
            startService(intent)
        }
    }

    private fun handleWakeIntent(intent: Intent?) {
        if (intent?.action != RemoteDashboardService.ACTION_WAKE_ACTIVITY) return
        val wakeEnabled = intent.getBooleanExtra(RemoteDashboardService.EXTRA_WAKE, false)
        val wakeTimeSeconds = if (intent.hasExtra(RemoteDashboardService.EXTRA_WAKE_TIME_SECONDS)) {
            intent.getIntExtra(RemoteDashboardService.EXTRA_WAKE_TIME_SECONDS, 0).takeIf { it > 0 }
        } else {
            null
        }
        if (wakeEnabled) {
            wakeFromIdle(resetForcedWake = false)
            forcedWakeUntilMs = wakeTimeSeconds?.let { System.currentTimeMillis() + (it * 1000L) } ?: 0L
            scheduleIdleTimeout()
        } else {
            forcedWakeUntilMs = 0L
            wakeFromIdle(resetForcedWake = false)
            scheduleIdleTimeout()
        }
    }

    @Suppress("DEPRECATION")
    private fun applyKioskWindowFlags() {
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(
                WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars(),
            )
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }
}
