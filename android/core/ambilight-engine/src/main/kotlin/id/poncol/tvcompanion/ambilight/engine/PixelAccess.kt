package id.poncol.tvcompanion.ambilight.engine

import java.nio.ByteBuffer

class PixelAccess(val width: Int, val height: Int) {
    private var buffer: ByteBuffer? = null
    private var base = 0
    private var rowStride = 0
    private var pixelStride = 0

    init {
        require(width > 0 && height > 0)
    }

    fun bind(buffer: ByteBuffer, rowStride: Int, pixelStride: Int, offset: Int = 0) {
        val rowBytes = (width - 1L) * pixelStride + 3
        require(offset >= 0 && pixelStride >= 3 && rowStride >= rowBytes)
        require(buffer.position().toLong() + offset + (height - 1L) * rowStride + rowBytes <= buffer.limit())
        this.buffer = buffer
        base = buffer.position() + offset
        this.rowStride = rowStride
        this.pixelStride = pixelStride
    }

    fun clear() { buffer = null }

    internal fun rgb(x: Int, y: Int): Int {
        val bytes = checkNotNull(buffer)
        val index = base + y * rowStride + x * pixelStride
        return ((bytes[index].toInt() and 255) shl 16) or
            ((bytes[index + 1].toInt() and 255) shl 8) or (bytes[index + 2].toInt() and 255)
    }
}
