package id.poncol.tvcompanion.ambilight

import id.poncol.tvcompanion.ambilight.engine.DdpEncoder
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

internal interface LedTransport : Closeable {
    fun send(rgb: ByteArray): Boolean
}

internal class UdpDdpTransport(address: InetAddress, leds: Int) : LedTransport {
    private val encoder = DdpEncoder(leds)
    private val packet = ByteBuffer.allocateDirect(1450)
    private val channel = DatagramChannel.open().apply {
        try {
            configureBlocking(false)
            connect(InetSocketAddress(address, 4048))
        } catch (error: Exception) {
            close()
            throw error
        }
    }
    private val write: (ByteArray) -> Boolean = { bytes ->
        packet.clear()
        packet.put(bytes).flip()
        channel.write(packet) == bytes.size
    }

    override fun send(rgb: ByteArray): Boolean = try {
        encoder.send(rgb, write)
    } catch (_: IOException) {
        false
    }

    override fun close() = channel.close()
}
