package nl.hrietman.androidremotedashboard

import android.os.Handler
import android.os.Looper
import android.util.Log
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
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val FIRST_FRAME_TIMEOUT_MS = 12_000L
        private const val FULL_FRAME_REQUEST_DELAY_MS = 2_000L
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
        reconnect("Connection timeout")
    }
    private val firstFrameTimeoutRunnable = Runnable {
        reconnect("Connected but no frame received, reconnecting")
    }
    private val keepaliveRunnable = object : Runnable {
        override fun run() {
            if (stopped || webSocket == null) return
            webSocket?.send(ByteString.of(*Protocol.buildKeepalivePacket()))
            mainHandler.postDelayed(this, KEEPALIVE_INTERVAL_MS)
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        // Image updates are intentionally silent on a static dashboard. Pongs,
        // rather than incoming image traffic, establish transport liveness.
        .pingInterval(KEEPALIVE_INTERVAL_MS, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var frameCount = 0
    private var firstFrameAt = 0L
    private var lastFrameAt = 0L
    private var stopped = false
    private var awaitingFirstFrame = false
    private var generation = 0L
    private var currentUrl = appConfig.normalizedInitialUrl()
    private val frameAssembler = FrameAssembler()

    fun start() {
        stopped = false
        frameAssembler.reset()
        connect()
    }

    fun stop() {
        stopped = true
        generation += 1
        mainHandler.removeCallbacksAndMessages(null)
        webSocket?.cancel()
        webSocket = null
        awaitingFirstFrame = false
        frameAssembler.reset()
    }

    fun sendTouch(kind: Int, x: Int, y: Int) {
        webSocket?.send(ByteString.of(*Protocol.buildTouchPacket(kind, x, y)))
    }

    fun openUrl(url: String) {
        currentUrl = url
        webSocket?.send(ByteString.of(*Protocol.buildOpenUrlPacket(url)))
    }

    private fun reconnect(message: String) {
        if (stopped) return
        // Invalidate before cancel: late callbacks from the old socket must not
        // tear down a replacement or schedule a second reconnect.
        generation += 1
        mainHandler.removeCallbacksAndMessages(null)
        webSocket?.cancel()
        webSocket = null
        awaitingFirstFrame = false
        frameAssembler.reset()
        Log.i("RemoteSession", message)
        listener.onDisconnected(message)
        if (!stopped) mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    private fun connect() {
        if (stopped) return
        val connectionGeneration = ++generation
        mainHandler.removeCallbacksAndMessages(null)
        listener.onConnecting("Connecting to ${appConfig.websocketUrl}")
        frameAssembler.reset()
        awaitingFirstFrame = true
        mainHandler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS)
        val request = Request.Builder()
            .url(appConfig.buildConnectionUri(width, height))
            .build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                mainHandler.post {
                    if (stopped || connectionGeneration != generation) {
                        webSocket.cancel()
                        return@post
                    }
                    mainHandler.removeCallbacks(connectTimeoutRunnable)
                    this@RemoteSessionClient.webSocket = webSocket
                    Log.i("RemoteSession", "Connected")
                    listener.onConnected()
                    frameCount = 0
                    firstFrameAt = 0L
                    lastFrameAt = 0L
                    frameAssembler.reset()
                    val openUrlPacket = ByteString.of(*Protocol.buildOpenUrlPacket(currentUrl))
                    webSocket.send(openUrlPacket)
                    mainHandler.postDelayed({
                        if (!stopped && connectionGeneration == generation && awaitingFirstFrame) {
                            // Reconnecting to an unchanged page may not produce a
                            // diff; ask for its image without navigating again.
                            webSocket.send(ByteString.of(*Protocol.buildRequestFullFramePacket()))
                        }
                    }, FULL_FRAME_REQUEST_DELAY_MS)
                    mainHandler.postDelayed(firstFrameTimeoutRunnable, FIRST_FRAME_TIMEOUT_MS)
                    mainHandler.postDelayed(keepaliveRunnable, KEEPALIVE_INTERVAL_MS)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                mainHandler.post {
                    if (!stopped && connectionGeneration == generation) {
                        handleBinaryMessage(bytes.toByteArray())
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                mainHandler.post {
                    if (!stopped && connectionGeneration == generation) reconnect("Closing: $code $reason")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                mainHandler.post {
                    if (!stopped && connectionGeneration == generation) reconnect("Closed: $code $reason")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                mainHandler.post {
                    if (!stopped && connectionGeneration == generation) {
                        reconnect("Error: ${t.message ?: "WebSocket failure"}")
                    }
                }
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
                FrameParser.parseCurrentUrl(payload)?.let { url ->
                    currentUrl = url
                    listener.onCurrentUrl(url)
                }
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
