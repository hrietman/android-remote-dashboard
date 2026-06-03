package nl.hrietman.androidremotedashboard

import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttMessageListener
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.json.JSONObject

class MqttCommandClient(
    private val config: AppConfig,
    private val listener: Listener,
) {
    interface Listener {
        fun onMqttStatus(message: String)
        fun onWakeCommand(enabled: Boolean, wakeTimeSeconds: Int?)
        fun onAudioCommand(audioUrl: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mqttClient: MqttAsyncClient? = null
    private var stopped = false
    private var reconnectScheduled = false
    private val reconnectRunnable = Runnable {
        reconnectScheduled = false
        connect()
    }

    fun start() {
        stopped = false
        if (!config.mqttEnabled || config.mqttBaseTopic.isBlank() || config.mqttHost.isBlank()) {
            listener.onMqttStatus("MQTT disabled")
            return
        }
        connect()
    }

    fun stop() {
        stopped = true
        mainHandler.removeCallbacks(reconnectRunnable)
        reconnectScheduled = false
        try {
            mqttClient?.disconnect()
        } catch (_: Exception) {
        }
        try {
            mqttClient?.close()
        } catch (_: Exception) {
        }
        mqttClient = null
    }

    private fun connect() {
        if (stopped || !config.mqttEnabled) return
        val serverUri = config.mqttServerUri()
        val clientId = config.normalizedMqttClientId()
        listener.onMqttStatus("MQTT connecting to $serverUri")
        try {
            val client = MqttAsyncClient(serverUri, clientId, null)
            mqttClient = client
            client.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    subscribe()
                }

                override fun connectionLost(cause: Throwable?) {
                    listener.onMqttStatus("MQTT disconnected")
                    scheduleReconnect()
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    handleMessage(message?.payload?.toString(Charsets.UTF_8).orEmpty())
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            })

            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = false
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 30
                if (config.mqttUsername.isNotBlank()) {
                    userName = config.mqttUsername
                }
                if (config.mqttPassword.isNotBlank()) {
                    password = config.mqttPassword.toCharArray()
                }
            }

            client.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?) {
                    listener.onMqttStatus("MQTT connected")
                    subscribe()
                }

                override fun onFailure(
                    asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?,
                    exception: Throwable?,
                ) {
                    listener.onMqttStatus("MQTT connect failed")
                    scheduleReconnect()
                }
            })
        } catch (_: MqttException) {
            listener.onMqttStatus("MQTT setup failed")
            scheduleReconnect()
        }
    }

    private fun subscribe() {
        val topic = config.mqttCommandTopic()
        try {
            val token = mqttClient?.subscribe(topic, 1, object : IMqttMessageListener {
                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    handleMessage(message?.payload?.toString(Charsets.UTF_8).orEmpty())
                }
            })
            token?.actionCallback = object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?) {
                    listener.onMqttStatus("MQTT listening on $topic")
                }

                override fun onFailure(
                    asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?,
                    exception: Throwable?,
                ) {
                    listener.onMqttStatus("MQTT subscribe failed")
                    scheduleReconnect()
                }
            }
        } catch (_: Exception) {
            listener.onMqttStatus("MQTT subscribe failed")
            scheduleReconnect()
        }
    }

    private fun handleMessage(payload: String) {
        try {
            val json = JSONObject(payload)
            var handled = false

            if (json.has("wake")) {
                val wake = json.optBoolean("wake", false)
                val wakeTime = if (json.has("wakeTime")) json.optInt("wakeTime").takeIf { it > 0 } else null
                listener.onWakeCommand(wake, wakeTime)
                handled = true
            }

            if (json.has("audio")) {
                val audioUrl = json.optString("audio").trim()
                if (isSupportedAudioUrl(audioUrl)) {
                    listener.onAudioCommand(audioUrl)
                    handled = true
                }
            }

            if (!handled) {
                listener.onMqttStatus("MQTT payload ignored")
            }
        } catch (_: Exception) {
            listener.onMqttStatus("MQTT payload ignored")
        }
    }

    private fun isSupportedAudioUrl(value: String): Boolean {
        if (value.isBlank()) return false
        val scheme = Uri.parse(value).scheme?.lowercase()
        return scheme == "http" || scheme == "https"
    }

    private fun scheduleReconnect() {
        if (stopped || reconnectScheduled) return
        reconnectScheduled = true
        mainHandler.postDelayed(reconnectRunnable, 5_000L)
    }
}
