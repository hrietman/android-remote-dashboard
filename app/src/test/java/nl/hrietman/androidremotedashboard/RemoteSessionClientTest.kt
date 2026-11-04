package nl.hrietman.androidremotedashboard

import android.os.Looper
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class RemoteSessionClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: RemoteSessionClient
    private val listener = RecordingListener()
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val messages = CopyOnWriteArrayList<ByteString>()

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = RemoteSessionClient(
            AppConfig.DEFAULT.copy(
                websocketUrl = server.url("/").toString().replace("http://", "ws://"),
                initialUrl = "http://ha.test/dashboard/home?kiosk=null",
            ), 480, 480, listener,
        )
    }

    @After fun tearDown() {
        client.stop()
        server.shutdown()
    }

    @Test fun quietDashboardKeepsItsConnectionAndStillAcceptsNavigation() {
        enqueueSession()
        client.start()
        await { listener.frames == 1 }

        // No server messages for longer than both the old 30s watchdog and
        // the dashboard's 60s return timer. Silence must not mean failure.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(95))
        client.openUrl("http://ha.test/dashboard/controls")
        await { messages.any { openUrlFromPacket(it) == "http://ha.test/dashboard/controls" } }
        assertEquals(1, listener.connections)
        assertTrue(listener.disconnections.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test fun brokenSocketReconnectsInPlaceToLastReportedPageExactlyOnce() {
        enqueueSession()
        enqueueSession()
        client.start()
        await { listener.frames == 1 }
        sockets[0].send(currentUrl("http://ha.test/dashboard/controls"))
        await { listener.url.endsWith("/controls") }
        sockets[0].close(1001, "test server restart")
        await { listener.disconnections.size == 1 }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        await { listener.connections == 2 && listener.frames == 2 }
        await { messages.count { openUrlFromPacket(it) == "http://ha.test/dashboard/controls" } == 1 }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals(2, server.requestCount)
        assertEquals(1, listener.disconnections.size)
    }

    @Test fun stopCancelsPendingRecovery() {
        enqueueSession()
        client.start()
        await { listener.frames == 1 }
        sockets[0].close(1001, "test server restart")
        await { listener.disconnections.size == 1 }
        client.stop()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2))
        assertEquals(1, server.requestCount)
        assertEquals(1, listener.connections)
    }

    @Test fun failedHandshakeRetriesWithoutAnActivityRestart() {
        server.enqueue(MockResponse().setResponseCode(503))
        enqueueSession()
        client.start()
        await { listener.disconnections.size == 1 }
        assertEquals(0, listener.connections)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        await { listener.frames == 1 }
        assertEquals(1, listener.connections)
        assertEquals(2, server.requestCount)
    }

    @Test fun unchangedPageRequestsFullFrameWithoutNavigatingTwice() {
        enqueueSession(sendInitialFrame = false)
        client.start()
        await { listener.connections == 1 }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        await { listener.frames == 1 }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertEquals(1, messages.count { it[0].toInt() == Protocol.MsgType.OPEN_URL })
        assertEquals(1, messages.count { it[0].toInt() == Protocol.MsgType.REQUEST_FULL_FRAME })
        assertTrue(listener.disconnections.isEmpty())
    }

    private fun enqueueSession(sendInitialFrame: Boolean = true) {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sockets += webSocket
                if (sendInitialFrame) webSocket.send(frame())
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                messages += bytes
                if (bytes[0].toInt() == Protocol.MsgType.REQUEST_FULL_FRAME) webSocket.send(frame())
            }
        }))
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Condition did not become true", condition())
    }

    private fun frame(): ByteString = ByteString.of(*ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN)
        .put(Protocol.MsgType.FRAME.toByte()).put(Protocol.VERSION).putInt(1)
        .put(Protocol.Encoding.JPEG.toByte()).putShort(0).putShort(Protocol.FLAG_LAST_OF_FRAME.toShort()).array())

    private fun currentUrl(url: String): ByteString {
        val data = url.toByteArray()
        return ByteString.of(*ByteBuffer.allocate(6 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(Protocol.MsgType.CURRENT_URL.toByte()).put(Protocol.VERSION).putInt(data.size).put(data).array())
    }

    private fun openUrlFromPacket(packet: ByteString): String? {
        if (packet[0].toInt() != Protocol.MsgType.OPEN_URL) return null
        return packet.substring(8).utf8()
    }

    private class RecordingListener : RemoteSessionClient.Listener {
        var connections = 0
        var frames = 0
        var url = ""
        val disconnections = mutableListOf<String>()
        override fun onConnecting(message: String) {}
        override fun onConnected() { connections++ }
        override fun onDisconnected(message: String) { disconnections += message }
        override fun onFrame(frame: AssembledFrame) { frames++ }
        override fun onCurrentUrl(url: String) { this.url = url }
        override fun onStats(stats: StreamStats) {}
    }
}
