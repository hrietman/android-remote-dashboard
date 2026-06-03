package nl.hrietman.androidremotedashboard

class FrameAssembler {
    private var currentFrameId: Long = -1L
    private var currentEncoding: Int = Protocol.Encoding.JPEG
    private var currentFlags: Int = 0
    private val currentRects = mutableListOf<TileRect>()

    fun reset() {
        currentFrameId = -1L
        currentEncoding = Protocol.Encoding.JPEG
        currentFlags = 0
        currentRects.clear()
    }

    fun push(packet: FrameMessage): AssembledFrame? {
        if (currentFrameId != packet.frameId) {
            currentFrameId = packet.frameId
            currentEncoding = packet.encoding
            currentFlags = packet.flags
            currentRects.clear()
        } else {
            currentFlags = currentFlags or packet.flags
        }

        currentRects += packet.rects

        val isLastPacket = (packet.flags and Protocol.FLAG_LAST_OF_FRAME) != 0
        if (!isLastPacket) {
            return null
        }

        val assembled = AssembledFrame(
            frameId = currentFrameId,
            encoding = currentEncoding,
            flags = currentFlags,
            rects = currentRects.toList(),
        )
        currentFrameId = -1L
        currentEncoding = Protocol.Encoding.JPEG
        currentFlags = 0
        currentRects.clear()
        return assembled
    }
}
