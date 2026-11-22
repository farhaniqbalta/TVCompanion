package id.poncol.tvcompanion.ambilight

import id.poncol.tvcompanion.ambilight.engine.PixelAccess
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class CaptureOutputTest {
    private class RecordingTransport : LedTransport {
        val sent = mutableListOf<ByteArray>()
        var success = true

        override fun send(rgb: ByteArray): Boolean {
            sent += rgb.copyOf()
            return success
        }

        override fun close() = Unit
    }

    private class SyntheticFrames : FrameSource {
        private val raw = ByteBuffer.allocate(16 * 8 * 4)
        private val pixels = PixelAccess(16, 8).apply { bind(raw, 64, 4) }
        private lateinit var sink: (PixelAccess, Long, Long) -> Unit
        private lateinit var onEnd: (Issue) -> Unit
        override fun start(frame: (PixelAccess, Long, Long) -> Unit, ended: (Issue) -> Unit) {
            sink = frame
            onEnd = ended
        }

        fun frame(atNs: Long, pixel: Byte = 0, sourceTime: Long = atNs) {
            raw.put(0, pixel)
            sink(pixels, atNs, sourceTime)
        }

        fun revoke() = onEnd(Issue.REVOKED)
        override fun close() = pixels.clear()
    }

    @Test fun staticKeepaliveAndTransportFailureRecoverWithoutSendingAfterStop() {
        val controller = SessionController()
        val epoch = controller.begin()!!
        controller.attach(epoch) {}
        controller.activate(epoch)
        val transport = RecordingTransport()
        val source = SyntheticFrames()
        val output = CaptureOutput(AmbilightConfig(), controller, epoch, transport) { blank, error ->
            controller.health(epoch, blank, error)
        }
        source.start(output::frame) { controller.stop() }
        output.keepAlive(1)
        assertTrue(transport.sent.isEmpty())
        source.frame(10, 255.toByte())
        assertTrue(transport.sent.single().any { it != 0.toByte() })
        assertEquals(1L, output.keepAlive(999_999_999))
        assertEquals(1, transport.sent.size)
        transport.success = false
        output.keepAlive(1_000_000_010)
        assertArrayEquals(transport.sent.first(), transport.sent.last())
        assertTrue(controller.state.sendError)
        transport.success = true
        source.frame(2_000_000_010)
        assertFalse(controller.state.sendError)
        source.revoke()
        assertEquals(Phase.STOPPING, controller.state.phase)
        source.frame(3_000_000_010)
        output.keepAlive(4_000_000_010)
        assertEquals(3, transport.sent.size)
        source.close()
    }

    @Test fun brightnessChangesApplyToTheRunningSessionWithoutNewFrames() {
        val controller = SessionController()
        val epoch = controller.begin()!!
        controller.attach(epoch) {}
        controller.activate(epoch)
        controller.look = Look(engineProfile(), 100)
        val transport = RecordingTransport()
        val source = SyntheticFrames()
        val output = CaptureOutput(AmbilightConfig(), controller, epoch, transport) { _, _ -> }
        source.start(output::frame) {}
        source.frame(10, 200.toByte())
        val full = transport.sent.last()[0].toInt() and 255
        assertTrue(full > 0)
        // A paused picture sends no new frames; the keepalive resend must carry the new brightness.
        controller.look = Look(engineProfile(), 50)
        output.keepAlive(1_000_000_010)
        assertEquals((full + 1) / 2, transport.sent.last()[0].toInt() and 255)
    }

    private fun engineProfile() = id.poncol.tvcompanion.ambilight.engine.LightingProfile.RESPONSIVE

    @Test fun blankHintNeedsFiveSecondsOfReceivedDarkSamplesAndClearsOnColor() {
        val controller = SessionController()
        val epoch = controller.begin()!!
        controller.attach(epoch) {}
        controller.activate(epoch)
        val source = SyntheticFrames()
        val output = CaptureOutput(AmbilightConfig(), controller, epoch, RecordingTransport()) { blank, error ->
            controller.health(epoch, blank, error)
        }
        source.start(output::frame) {}
        source.frame(1)
        output.keepAlive(10_000_000_000)
        assertFalse(controller.state.blank)
        for (i in 0..49) source.frame(11_000_000_001 + i * 100_000_000L)
        assertFalse(controller.state.blank)
        source.frame(16_000_000_001)
        assertTrue(controller.state.blank)
        source.frame(16_100_000_001, 255.toByte())
        assertFalse(controller.state.blank)
    }

    @Test fun sourceClockRegressionFallsBackToAcquisitionCadence() {
        val controller = SessionController()
        val epoch = controller.begin()!!
        controller.attach(epoch) {}
        controller.activate(epoch)
        val source = SyntheticFrames()
        val transport = RecordingTransport()
        val output = CaptureOutput(AmbilightConfig(), controller, epoch, transport) { _, _ -> }
        source.start(output::frame) {}
        source.frame(1, sourceTime = 900_000_000)
        source.frame(40_000_001, sourceTime = 933_333_333)
        source.frame(70_000_001, sourceTime = 100)
        source.frame(75_000_001, sourceTime = 966_666_666)
        source.frame(105_000_001, sourceTime = 999_999_999)
        assertEquals(4, transport.sent.size)
    }

    @Test fun ipv4ValidationAcceptsOnlyPrivateAndLinkLocalAddresses() {
        assertArrayEquals(byteArrayOf(192.toByte(), 168.toByte(), 10, 20), ipv4Bytes("192.168.10.20"))
        assertNotNull(ipv4Bytes("169.254.1.9"))
        assertNotNull(ipv4Bytes("172.31.0.1"))
        for (bad in listOf("8.8.8.8", "172.32.0.1", "127.0.0.1", "224.0.0.1", "0.1.2.3", "192.168.1.256", "192.168.1", "192.168..1", "wled.local", "192.168.1.1:80")) {
            assertNull(bad, ipv4Bytes(bad))
        }
    }
}
