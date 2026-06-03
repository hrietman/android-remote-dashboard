package nl.hrietman.androidremotedashboard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class RemoteDashboardService : Service() {
    companion object {
        const val ACTION_START_OR_UPDATE = "nl.hrietman.androidremotedashboard.action.START_OR_UPDATE"
        const val ACTION_STOP = "nl.hrietman.androidremotedashboard.action.STOP"
        const val ACTION_MQTT_STATUS = "nl.hrietman.androidremotedashboard.action.MQTT_STATUS"
        const val ACTION_WAKE_ACTIVITY = "nl.hrietman.androidremotedashboard.action.WAKE_ACTIVITY"
        const val EXTRA_STATUS = "status"
        const val EXTRA_WAKE = "wake"
        const val EXTRA_WAKE_TIME_SECONDS = "wake_time_seconds"

        private const val CHANNEL_ID = "remote_dashboard_service"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TIMEOUT_MS = 10_000L
    }

    private var mqttClient: MqttCommandClient? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Android Remote Dashboard")
                .setContentText("MQTT wake service active")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setOngoing(true)
                .build(),
        )
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT > Build.VERSION_CODES.KITKAT) {
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
        } else {
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE
        }
        wakeLock = powerManager.newWakeLock(flags, "remotedashboard:wakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMqtt()
                stopSelf()
            }
            ACTION_START_OR_UPDATE, null -> {
                configureMqtt()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMqtt()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun configureMqtt() {
        val config = AppConfig.load(this)
        if (!config.mqttEnabled) {
            broadcastStatus("MQTT disabled")
            stopMqtt()
            stopSelf()
            return
        }
        stopMqtt()
        mqttClient = MqttCommandClient(config, object : MqttCommandClient.Listener {
            override fun onMqttStatus(message: String) {
                broadcastStatus(message)
            }

            override fun onWakeCommand(enabled: Boolean, wakeTimeSeconds: Int?) {
                val wakeBroadcast = Intent(ACTION_WAKE_ACTIVITY)
                    .setPackage(packageName)
                    .putExtra(EXTRA_WAKE, enabled)
                if (wakeTimeSeconds != null) {
                    wakeBroadcast.putExtra(EXTRA_WAKE_TIME_SECONDS, wakeTimeSeconds)
                }
                sendBroadcast(wakeBroadcast)

                if (enabled) {
                    acquireWakeLock()
                }

                if (enabled && !MainActivity.isActivityInForeground) {
                    val wakeIntent = Intent(this@RemoteDashboardService, MainActivity::class.java).apply {
                        action = ACTION_WAKE_ACTIVITY
                        putExtra(EXTRA_WAKE, true)
                        if (wakeTimeSeconds != null) {
                            putExtra(EXTRA_WAKE_TIME_SECONDS, wakeTimeSeconds)
                        }
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                        )
                    }
                    startActivity(wakeIntent)
                }
            }
        }).also { it.start() }
    }

    private fun acquireWakeLock() {
        val wl = wakeLock ?: return
        if (!wl.isHeld) {
            wl.acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun stopMqtt() {
        mqttClient?.stop()
        mqttClient = null
    }

    private fun broadcastStatus(message: String) {
        sendBroadcast(
            Intent(ACTION_MQTT_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_STATUS, message),
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Remote Dashboard Service",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }
}
