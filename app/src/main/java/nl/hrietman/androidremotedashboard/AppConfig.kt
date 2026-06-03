package nl.hrietman.androidremotedashboard

import android.content.Context
import android.net.Uri

data class AppConfig(
    val websocketUrl: String,
    val deviceId: String,
    val initialUrl: String,
    val jpegQuality: Int,
    val tileSize: Int,
    val minFrameIntervalMs: Int,
    val mqttEnabled: Boolean,
    val mqttHost: String,
    val mqttPort: Int,
    val mqttUseSsl: Boolean,
    val mqttUsername: String,
    val mqttPassword: String,
    val mqttBaseTopic: String,
    val mqttClientId: String,
    val screenOffTimeoutSeconds: Int,
    val enhancedIdleDimmingEnabled: Boolean,
    val showDebugOverlay: Boolean,
    val configButtonPosition: String,
    val screenBrightnessPercent: Int,
) {
    companion object {
        const val BUTTON_POSITION_LEFT = "left"
        const val BUTTON_POSITION_RIGHT = "right"

        private const val PREFS = "remote_dashboard"
        private const val KEY_WS_URL = "ws_url"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_INITIAL_URL = "initial_url"
        private const val KEY_JPEG_QUALITY = "jpeg_quality"
        private const val KEY_TILE_SIZE = "tile_size"
        private const val KEY_MIN_FRAME_INTERVAL = "min_frame_interval"
        private const val KEY_MQTT_ENABLED = "mqtt_enabled"
        private const val KEY_MQTT_HOST = "mqtt_host"
        private const val KEY_MQTT_PORT = "mqtt_port"
        private const val KEY_MQTT_USE_SSL = "mqtt_use_ssl"
        private const val KEY_MQTT_USERNAME = "mqtt_username"
        private const val KEY_MQTT_PASSWORD = "mqtt_password"
        private const val KEY_MQTT_BASE_TOPIC = "mqtt_base_topic"
        private const val KEY_MQTT_CLIENT_ID = "mqtt_client_id"
        private const val KEY_SCREEN_OFF_TIMEOUT_SECONDS = "screen_off_timeout_seconds"
        private const val KEY_ENHANCED_IDLE_DIMMING_ENABLED = "enhanced_idle_dimming_enabled"
        private const val KEY_SHOW_DEBUG_OVERLAY = "show_debug_overlay"
        private const val KEY_CONFIG_BUTTON_POSITION = "config_button_position"
        private const val KEY_SCREEN_BRIGHTNESS_PERCENT = "screen_brightness_percent"

        val DEFAULT = AppConfig(
            websocketUrl = "ws://server-ip:8081",
            deviceId = "android-panel",
            initialUrl = "http://home-assistant.local:8123/dashboard?kiosk",
            jpegQuality = 85,
            tileSize = 32,
            minFrameIntervalMs = 80,
            mqttEnabled = false,
            mqttHost = "",
            mqttPort = 1883,
            mqttUseSsl = false,
            mqttUsername = "",
            mqttPassword = "",
            mqttBaseTopic = "android-panel",
            mqttClientId = "android-panel",
            screenOffTimeoutSeconds = 60,
            enhancedIdleDimmingEnabled = false,
            showDebugOverlay = false,
            configButtonPosition = BUTTON_POSITION_LEFT,
            screenBrightnessPercent = 80,
        )

        fun load(context: Context): AppConfig {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return AppConfig(
                websocketUrl = prefs.getString(KEY_WS_URL, DEFAULT.websocketUrl).orEmpty(),
                deviceId = prefs.getString(KEY_DEVICE_ID, DEFAULT.deviceId).orEmpty(),
                initialUrl = normalizeInitialUrl(
                    prefs.getString(KEY_INITIAL_URL, DEFAULT.initialUrl).orEmpty(),
                ),
                jpegQuality = prefs.getInt(KEY_JPEG_QUALITY, DEFAULT.jpegQuality),
                tileSize = prefs.getInt(KEY_TILE_SIZE, DEFAULT.tileSize),
                minFrameIntervalMs = prefs.getInt(KEY_MIN_FRAME_INTERVAL, DEFAULT.minFrameIntervalMs),
                mqttEnabled = prefs.getBoolean(KEY_MQTT_ENABLED, DEFAULT.mqttEnabled),
                mqttHost = prefs.getString(KEY_MQTT_HOST, DEFAULT.mqttHost).orEmpty(),
                mqttPort = normalizePort(prefs.getInt(KEY_MQTT_PORT, DEFAULT.mqttPort)),
                mqttUseSsl = prefs.getBoolean(KEY_MQTT_USE_SSL, DEFAULT.mqttUseSsl),
                mqttUsername = prefs.getString(KEY_MQTT_USERNAME, DEFAULT.mqttUsername).orEmpty(),
                mqttPassword = prefs.getString(KEY_MQTT_PASSWORD, DEFAULT.mqttPassword).orEmpty(),
                mqttBaseTopic = normalizeTopicSegment(
                    prefs.getString(KEY_MQTT_BASE_TOPIC, DEFAULT.mqttBaseTopic).orEmpty(),
                ),
                mqttClientId = normalizeClientId(
                    prefs.getString(KEY_MQTT_CLIENT_ID, DEFAULT.mqttClientId).orEmpty(),
                    prefs.getString(KEY_DEVICE_ID, DEFAULT.deviceId).orEmpty(),
                ),
                screenOffTimeoutSeconds = normalizeTimeoutSeconds(
                    prefs.getInt(KEY_SCREEN_OFF_TIMEOUT_SECONDS, DEFAULT.screenOffTimeoutSeconds),
                ),
                enhancedIdleDimmingEnabled = prefs.getBoolean(
                    KEY_ENHANCED_IDLE_DIMMING_ENABLED,
                    DEFAULT.enhancedIdleDimmingEnabled,
                ),
                showDebugOverlay = prefs.getBoolean(KEY_SHOW_DEBUG_OVERLAY, DEFAULT.showDebugOverlay),
                configButtonPosition = normalizeButtonPosition(
                    prefs.getString(KEY_CONFIG_BUTTON_POSITION, DEFAULT.configButtonPosition).orEmpty(),
                ),
                screenBrightnessPercent = normalizeBrightness(
                    prefs.getInt(KEY_SCREEN_BRIGHTNESS_PERCENT, DEFAULT.screenBrightnessPercent),
                ),
            )
        }

        fun save(context: Context, config: AppConfig) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_WS_URL, config.websocketUrl)
                .putString(KEY_DEVICE_ID, config.deviceId)
                .putString(KEY_INITIAL_URL, normalizeInitialUrl(config.initialUrl))
                .putInt(KEY_JPEG_QUALITY, config.jpegQuality)
                .putInt(KEY_TILE_SIZE, config.tileSize)
                .putInt(KEY_MIN_FRAME_INTERVAL, config.minFrameIntervalMs)
                .putBoolean(KEY_MQTT_ENABLED, config.mqttEnabled)
                .putString(KEY_MQTT_HOST, config.mqttHost.trim())
                .putInt(KEY_MQTT_PORT, normalizePort(config.mqttPort))
                .putBoolean(KEY_MQTT_USE_SSL, config.mqttUseSsl)
                .putString(KEY_MQTT_USERNAME, config.mqttUsername)
                .putString(KEY_MQTT_PASSWORD, config.mqttPassword)
                .putString(KEY_MQTT_BASE_TOPIC, normalizeTopicSegment(config.mqttBaseTopic))
                .putString(KEY_MQTT_CLIENT_ID, normalizeClientId(config.mqttClientId, config.deviceId))
                .putInt(KEY_SCREEN_OFF_TIMEOUT_SECONDS, normalizeTimeoutSeconds(config.screenOffTimeoutSeconds))
                .putBoolean(KEY_ENHANCED_IDLE_DIMMING_ENABLED, config.enhancedIdleDimmingEnabled)
                .putBoolean(KEY_SHOW_DEBUG_OVERLAY, config.showDebugOverlay)
                .putString(KEY_CONFIG_BUTTON_POSITION, normalizeButtonPosition(config.configButtonPosition))
                .putInt(KEY_SCREEN_BRIGHTNESS_PERCENT, normalizeBrightness(config.screenBrightnessPercent))
                .apply()
        }

        private fun normalizeInitialUrl(url: String): String {
            if (url.isBlank()) return normalizeInitialUrl(DEFAULT.initialUrl)
            val uri = Uri.parse(url)
            if (uri.getQueryParameter("kiosk") != null) {
                return url
            }
            return uri.buildUpon()
                .appendQueryParameter("kiosk", null)
                .build()
                .toString()
        }

        private fun normalizeButtonPosition(value: String): String {
            return if (value == BUTTON_POSITION_RIGHT) BUTTON_POSITION_RIGHT else BUTTON_POSITION_LEFT
        }

        private fun normalizeBrightness(value: Int): Int {
            return value.coerceIn(1, 100)
        }

        private fun normalizeTimeoutSeconds(value: Int): Int {
            return value.coerceIn(5, 86_400)
        }

        private fun normalizePort(value: Int): Int {
            return value.coerceIn(1, 65535)
        }

        private fun normalizeTopicSegment(value: String): String {
            return value.trim().ifBlank { DEFAULT.mqttBaseTopic }
        }

        private fun normalizeClientId(value: String, deviceId: String): String {
            return value.trim().ifBlank { "remotedashboard-${deviceId.ifBlank { DEFAULT.deviceId }}" }
        }
    }

    fun buildConnectionUri(width: Int, height: Int): String {
        val baseUri = Uri.parse(websocketUrl)
        return baseUri.buildUpon()
            .appendQueryParameter("id", deviceId)
            .appendQueryParameter("w", width.toString())
            .appendQueryParameter("h", height.toString())
            .appendQueryParameter("q", jpegQuality.toString())
            .appendQueryParameter("ts", tileSize.toString())
            .appendQueryParameter("mfi", minFrameIntervalMs.toString())
            .build()
            .toString()
    }

    fun normalizedInitialUrl(): String {
        val uri = Uri.parse(initialUrl)
        if (uri.getQueryParameter("kiosk") != null) {
            return initialUrl
        }
        return uri.buildUpon()
            .appendQueryParameter("kiosk", null)
            .build()
                .toString()
    }

    fun mqttCommandTopic(): String {
        return "remotedashboard/${normalizeTopicSegment(mqttBaseTopic)}/command"
    }

    fun mqttServerUri(): String {
        val scheme = if (mqttUseSsl) "ssl" else "tcp"
        return "$scheme://${mqttHost.trim()}:${normalizePort(mqttPort)}"
    }

    fun normalizedMqttClientId(): String {
        return normalizeClientId(mqttClientId, deviceId)
    }
}
