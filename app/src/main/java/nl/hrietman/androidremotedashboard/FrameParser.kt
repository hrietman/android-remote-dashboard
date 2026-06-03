package nl.hrietman.androidremotedashboard

import java.nio.ByteBuffer
import java.nio.ByteOrder

object FrameParser {
    private const val FRAME_HEADER_BYTES = 11
    private const val TILE_HEADER_BYTES = 12

    fun parseFrame(payload: ByteArray): FrameMessage? {
        if (payload.size < FRAME_HEADER_BYTES) return null
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val type = buffer.get().toInt() and 0xFF
        val version = buffer.get().toInt() and 0xFF
        if (type != Protocol.MsgType.FRAME || version != Protocol.VERSION.toInt()) return null

        val frameId = buffer.int.toLong() and 0xFFFFFFFFL
        val encoding = buffer.get().toInt() and 0xFF
        val tileCount = buffer.short.toInt() and 0xFFFF
        val flags = buffer.short.toInt() and 0xFFFF
        val rects = ArrayList<TileRect>(tileCount)

        repeat(tileCount) {
            if (buffer.remaining() < TILE_HEADER_BYTES) return null
            val x = buffer.short.toInt() and 0xFFFF
            val y = buffer.short.toInt() and 0xFFFF
            val width = buffer.short.toInt() and 0xFFFF
            val height = buffer.short.toInt() and 0xFFFF
            val dataLength = buffer.int
            if (dataLength < 0 || buffer.remaining() < dataLength) return null
            val bytes = ByteArray(dataLength)
            buffer.get(bytes)
            rects += TileRect(x, y, width, height, bytes)
        }

        return FrameMessage(frameId, encoding, flags, rects)
    }

    fun parseCurrentUrl(payload: ByteArray): String? {
        if (payload.size < Protocol.CURRENT_URL_HEADER_BYTES) return null
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val type = buffer.get().toInt() and 0xFF
        val version = buffer.get().toInt() and 0xFF
        if (type != Protocol.MsgType.CURRENT_URL || version != Protocol.VERSION.toInt()) return null
        val length = buffer.int
        if (length < 0 || buffer.remaining() < length) return null
        val bytes = ByteArray(length)
        buffer.get(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    fun parseFrameStats(payload: ByteArray): Long? {
        if (payload.size < 10) return null
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val type = buffer.get().toInt() and 0xFF
        val version = buffer.get().toInt() and 0xFF
        if (type != Protocol.MsgType.FRAME_STATS || version != Protocol.VERSION.toInt()) return null
        return buffer.int.toLong() and 0xFFFFFFFFL
    }
}
