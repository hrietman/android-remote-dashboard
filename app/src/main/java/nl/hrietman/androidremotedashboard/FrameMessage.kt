package nl.hrietman.androidremotedashboard

data class FrameMessage(
    val frameId: Long,
    val encoding: Int,
    val flags: Int,
    val rects: List<TileRect>,
)

data class TileRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val data: ByteArray,
)

data class StreamStats(
    val fps: Double,
    val lastFrameRenderMs: Long,
    val frameId: Long,
)

data class AssembledFrame(
    val frameId: Long,
    val encoding: Int,
    val flags: Int,
    val rects: List<TileRect>,
)
