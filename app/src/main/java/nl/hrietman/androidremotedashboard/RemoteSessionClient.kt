package nl.hrietman.androidremotedashboard

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

class RemoteSessionClient(
    private val appConfig: AppConfig,
    private val width: Int,
    private val height: Int,
    private val listener: Listener,
) {
    companion object {
        private const val KEEPALIVE_INTERVAL_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val INACTIVITY_TIMEOUT_MS = 30_000L
        private const val WATCHDOG_INTERVAL_MS = 5_000L
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val FIRST_FRAME_TIMEOUT_MS = 12_000L
        private const val OPEN_URL_RETRY_MS = 2_000L
    }

    interface Listener {
        fun onConnecting(message: String)
        fun onConnected()
        fun onDisconnected(message: String)
        fun onFrame(frame: AssembledFrame)
        fun onCurrentUrl(url: String)
        fun onStats(stats: StreamStats)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = Runnable { connect() }
    private val connectTimeoutRunnable = Runnable {
        if (stopped) return@Runnable
        listener.onDisconnected("Connection timeout")
        webSocket?.cancel()
        webSocket = null
    }
    private val firstFrameTimeoutRunnable = Runnable {
        if (stopped) return@Runnable
        listener.onDisconnected("Connected but no frame received, reconnecting")
        webSocket?.cancel()
        webSocket = null
    }
    private val keepaliveRunnable = object : Runnable {
        override fun run() {
            webSocket?.send(ByteString.of(*Protocol.buildKeepalivePacket()))
            mainHandler.postDelayed(this, KEEPALIVE_INTERVAL_MS)
        }
    }
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (stopped) return
            val now = System.currentTimeMillis()
            val socket = webSocket
            if (socket != null && lastActivityAt > 0L && now - lastActivityAt > INACTIVITY_TIMEOUT_MS) {
                listener.onDisconnected("No data from server, reconnecting")
                socket.cancel()
                webSocket = null
                return
            }
            mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var frameCount = 0
    private var firstFrameAt = 0L
    private var lastFrameAt = 0L
    private var lastActivityAt = 0L
    private var stopped = false
    private var awaitingFirstFrame = false
    private val frameAssembler = FrameAssembler()

    fun start() {
        stopped = false
        frameAssembler.reset()
        connect()
    }

    fun stop() {
        stopped = true
        mainHandler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "closing")
        webSocket = null
        lastActivityAt = 0L
        awaitingFirstFrame = false
        frameAssembler.reset()
    }

    fun sendTouch(kind: Int, x: Int, y: Int) {
        webSocket?.send(ByteString.of(*Protocol.buildTouchPacket(kind, x, y)))
    }

    private fun connect() {
        if (stopped) return
        listener.onConnecting("Connecting to ${appConfig.websocketUrl}")
        frameAssembler.reset()
        lastActivityAt = System.currentTimeMillis()
        awaitingFirstFrame = true
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        mainHandler.removeCallbacks(firstFrameTimeoutRunnable)
        mainHandler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS)
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
        val request = Request.Builder()
            .url(appConfig.buildConnectionUri(width, height))
            .build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (stopped) {
                    webSocket.close(1000, "stopped")
                    return
                }
                mainHandler.removeCallbacks(connectTimeoutRunnable)
                listener.onConnected()
                this@RemoteSessionClient.webSocket = webSocket
                frameCount = 0
                firstFrameAt = 0L
                lastFrameAt = 0L
                lastActivityAt = System.currentTimeMillis()
                frameAssembler.reset()
                val openUrlPacket = ByteString.of(*Protocol.buildOpenUrlPacket(appConfig.normalizedInitialUrl()))
                webSocket.send(openUrlPacket)
                mainHandler.postDelayed({
                    if (!stopped && awaitingFirstFrame) {
                        this@RemoteSessionClient.webSocket?.send(openUrlPacket)
                    }
                }, OPEN_URL_RETRY_MS)
                mainHandler.postDelayed(firstFrameTimeoutRunnable, FIRST_FRAME_TIMEOUT_MS)
                mainHandler.postDelayed(keepaliveRunnable, KEEPALIVE_INTERVAL_MS)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                lastActivityAt = System.currentTimeMillis()
                handleBinaryMessage(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                mainHandler.removeCallbacks(connectTimeoutRunnable)
                mainHandler.removeCallbacks(firstFrameTimeoutRunnable)
                this@RemoteSessionClient.webSocket = null
                awaitingFirstFrame = false
                frameAssembler.reset()
                listener.onDisconnected("Closing: $code $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                mainHandler.removeCallbacks(connectTimeoutRunnable)
                mainHandler.removeCallbacks(firstFrameTimeoutRunnable)
                this@RemoteSessionClient.webSocket = null
                awaitingFirstFrame = false
                frameAssembler.reset()
                listener.onDisconnected("Closed: $code $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                mainHandler.removeCallbacks(connectTimeoutRunnable)
                mainHandler.removeCallbacks(firstFrameTimeoutRunnable)
                this@RemoteSessionClient.webSocket = null
                awaitingFirstFrame = false
                frameAssembler.reset()
                listener.onDisconnected("Error: ${t.message ?: "WebSocket failure"}")
            }
        })
    }

    private fun handleBinaryMessage(payload: ByteArray) {
        if (payload.isEmpty()) return
        when (payload[0].toInt() and 0xFF) {
            Protocol.MsgType.FRAME -> {
                val packet = FrameParser.parseFrame(payload) ?: return
                val frame = frameAssembler.push(packet) ?: return
                frameCount += 1
                val now = System.currentTimeMillis()
                if (firstFrameAt == 0L) firstFrameAt = now
                lastFrameAt = now
                awaitingFirstFrame = false
                mainHandler.removeCallbacks(firstFrameTimeoutRunnable)
                val elapsedMs = (lastFrameAt - firstFrameAt).coerceAtLeast(1L)
                val fps = if (frameCount <= 1) 0.0 else (frameCount - 1) * 1000.0 / elapsedMs
                listener.onFrame(frame)
                listener.onStats(
                    StreamStats(
                        fps = fps,
                        lastFrameRenderMs = 0L,
                        frameId = frame.frameId,
                    ),
                )
            }
            Protocol.MsgType.CURRENT_URL -> {
                FrameParser.parseCurrentUrl(payload)?.let(listener::onCurrentUrl)
            }
            Protocol.MsgType.FRAME_STATS -> {
                val renderMs = FrameParser.parseFrameStats(payload) ?: return
                val elapsedMs = (lastFrameAt - firstFrameAt).coerceAtLeast(1L)
                val fps = if (frameCount <= 1) 0.0 else (frameCount - 1) * 1000.0 / elapsedMs
                listener.onStats(
                    StreamStats(
                        fps = fps,
                        lastFrameRenderMs = renderMs,
                        frameId = 0L,
                    ),
                )
            }
        }
    }
}
