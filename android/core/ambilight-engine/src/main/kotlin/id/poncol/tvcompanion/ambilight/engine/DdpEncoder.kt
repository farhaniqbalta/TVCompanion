package id.poncol.tvcompanion.ambilight.engine

class DdpEncoder(ledCount: Int) {
    private val rgbSize = ledCount.also { require(it in 1..LedLayout.MAX_LEDS) } * 3
    private val packets = Array((rgbSize + 1439) / 1440) { index ->
        val offset = index * 1440
        val length = minOf(rgbSize - offset, 1440)
        ByteArray(10 + length).apply {
            this[0] = if (offset + length == rgbSize) 0x41 else 0x40
            this[2] = 0x0B
            this[3] = 0x01
            this[4] = (offset ushr 24).toByte()
            this[5] = (offset ushr 16).toByte()
            this[6] = (offset ushr 8).toByte()
            this[7] = offset.toByte()
            this[8] = (length ushr 8).toByte()
            this[9] = length.toByte()
        }
    }
    private var sequence = 0

    /** The sender consumes each reusable packet synchronously. */
    fun send(rgb: ByteArray, write: (ByteArray) -> Boolean): Boolean {
        require(rgb.size == rgbSize)
        var offset = 0
        for (packet in packets) {
            sequence = (sequence + 1) and 15
            packet[1] = sequence.toByte()
            val length = packet.size - 10
            rgb.copyInto(packet, 10, offset, offset + length)
            if (!write(packet)) return false
            offset += length
        }
        return true
    }
}
