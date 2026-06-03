package nl.hrietman.androidremotedashboard

import java.nio.ByteBuffer
import java.nio.ByteOrder

object Protocol {
    const val VERSION: Byte = 1
    const val FLAG_LAST_OF_FRAME = 1 shl 0
    const val FLAG_IS_FULL_FRAME = 1 shl 1
    const val CURRENT_URL_HEADER_BYTES = 6

    object MsgType {
        const val FRAME = 1
        const val TOUCH = 2
        const val FRAME_STATS = 3
        const val OPEN_URL = 4
        const val KEEPALIVE = 5
        const val CURRENT_URL = 6
    }

    object Encoding {
        const val PNG = 1
        const val JPEG = 2
    }

    object TouchKind {
        const val DOWN = 1
        const val MOVE = 2
        const val UP = 3
        const val TAP = 4
    }

    fun buildTouchPacket(kind: Int, x: Int, y: Int, pointerId: Int = 0): ByteArray {
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MsgType.TOUCH.toByte())
        buffer.put(VERSION)
        buffer.put(kind.toByte())
        buffer.put(pointerId.toByte())
        buffer.putShort(x.coerceIn(0, 0xFFFF).toShort())
        buffer.putShort(y.coerceIn(0, 0xFFFF).toShort())
        return buffer.array()
    }

    fun buildKeepalivePacket(): ByteArray = byteArrayOf(
        MsgType.KEEPALIVE.toByte(),
        VERSION,
    )

    fun buildOpenUrlPacket(url: String): ByteArray {
        val payload = url.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MsgType.OPEN_URL.toByte())
        buffer.put(VERSION)
        buffer.putShort(0)
        buffer.putInt(payload.size)
        buffer.put(payload)
        return buffer.array()
    }
}
