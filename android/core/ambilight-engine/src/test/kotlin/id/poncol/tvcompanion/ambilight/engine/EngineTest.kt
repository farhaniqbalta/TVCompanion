package id.poncol.tvcompanion.ambilight.engine

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class EngineTest {
    @Test fun ddpFixtures() {
        for (leds in listOf(30, 60, 180, 300, 480, 481, 600)) {
            val rgb = ByteArray(leds * 3) { it.toByte() }
            val encoder = DdpEncoder(leds)
            var sequence = 0
            val stableBuffers = mutableMapOf<Int, ByteArray>()
            repeat(20) { frame ->
                rgb[0] = frame.toByte()
                var expectedOffset = 0
                assertTrue(encoder.send(rgb) { bytes ->
                    val packet = ByteBuffer.wrap(bytes)
                    val size = minOf(rgb.size - expectedOffset, 1440)
                    assertSame(stableBuffers.getOrPut(expectedOffset) { bytes }, bytes)
                    assertEquals(10 + size, bytes.size)
                    assertEquals(if (expectedOffset + size == rgb.size) 0x41 else 0x40, packet.get().toInt())
                    sequence = (sequence + 1) and 15
                    assertEquals(sequence, packet.get().toInt())
                    assertEquals(11, packet.get().toInt())
                    assertEquals(1, packet.get().toInt())
                    assertEquals(expectedOffset, packet.int)
                    assertEquals(size, packet.short.toInt())
                    assertArrayEquals(rgb.copyOfRange(expectedOffset, expectedOffset + size), bytes.copyOfRange(10, bytes.size))
                    expectedOffset += size
                    true
                })
                assertEquals(rgb.size, expectedOffset)
            }
        }
    }

    @Test fun strideCornerDirectionAndOffset() {
        val w = 16
        val h = 12
        val stride = 90
        val raw = ByteBuffer.allocate((h - 1) * stride + (w - 1) * 5 + 3)
        val pixels = PixelAccess(w, h).apply { bind(raw, stride, 5) }
        for (y in 0 until h) for (x in 0 until w) {
            raw.put(y * stride + x * 5, (10 + x * 7).toByte())
            raw.put(y * stride + x * 5 + 1, (20 + y * 9).toByte())
        }
        val counts = listOf(20, 3, 2, 4)
        fun run(layout: LedLayout): List<List<Byte>> {
            val out = ByteArray(layout.total * 3)
            LedPipeline(w, h, layout, LightingProfile.RESPONSIVE, 75).process(pixels, 1, out)
            return out.toList().chunked(3)
        }
        val baseline = run(LedLayout(counts))
        assertEquals(listOf<Byte>(8, 15, 0), baseline.first())
        assertTrue(baseline[19][0] > baseline.first()[0])
        for (corner in 0..3) for (clockwise in listOf(true, false)) {
            val offset = 7
            val seam = counts.take(corner).sum()
            val mapped = run(LedLayout(counts, corner, clockwise, offset))
            for (i in baseline.indices) {
                val physical = if (clockwise) seam + i else seam - 1 - i
                assertEquals(baseline[Math.floorMod(physical, baseline.size)], mapped[(i + offset) % baseline.size])
            }
        }
        assertThrows(IllegalArgumentException::class.java) { pixels.bind(raw, stride + 1, 5) }
        assertThrows(IllegalArgumentException::class.java) { LedLayout(listOf(0, 0, 0, 0)) }
    }

    @Test fun movingBarAndElapsedSmoothing() {
        val raw = ByteBuffer.allocate(16 * 8 * 4)
        val pixels = PixelAccess(16, 8).apply { bind(raw, 64, 4) }
        val layout = LedLayout(listOf(4, 0, 0, 0))
        val pipeline = LedPipeline(16, 8, layout, LightingProfile.RESPONSIVE, 50)
        val out = ByteArray(12)
        assertTrue(pipeline.process(pixels, 1, out))
        for (x in 0..3) raw.put(x * 4, 255.toByte())
        assertFalse(pipeline.process(pixels, 33_333_334, out))
        val rising = out[0].toInt()
        assertTrue(rising in 1..126)
        assertEquals(0, out[9].toInt())
        for (x in 0..3) raw.put(x * 4, 0)
        for (x in 12..15) raw.put(x * 4, 255.toByte())
        pipeline.process(pixels, 233_333_334, out)
        assertTrue(out[0].toInt() < rising)
        assertTrue(out[9].toInt() in (rising + 1)..127)
    }

    @Test fun oneWideZoneAveragesItsAreaAndToleratesTinyBrightOverlays() {
        val raw = ByteBuffer.allocate(160 * 90 * 4)
        val pixels = PixelAccess(160, 90).apply { bind(raw, 640, 4) }
        val pipeline = LedPipeline(160, 90, LedLayout(listOf(1, 0, 0, 0)), LightingProfile.CINEMA, 50)
        val out = ByteArray(3)
        raw.put(42 * 4, 255.toByte())
        assertTrue(pipeline.process(pixels, 1, out))
        assertEquals(0, out[0].toInt())
        for (y in 0 until 11) for (x in 0 until 40) {
            raw.put(y * 640 + x * 4, 255.toByte())
        }
        assertFalse(pipeline.process(pixels, 251_000_001, out))
        assertTrue(out[0].toInt() in 28..32)
    }

    @Test fun letterboxBarsAreSkippedOnceStableAndDroppedAtOnce() {
        // A 2.39:1 film on a 16:9 capture: 12 black rows above and below red content.
        val raw = ByteBuffer.allocate(160 * 90 * 4)
        val pixels = PixelAccess(160, 90).apply { bind(raw, 640, 4) }
        fun fill(fromRow: Int, toRow: Int) {
            for (y in fromRow until toRow) for (x in 0 until 160) raw.put(y * 640 + x * 4, 200.toByte())
        }
        fill(12, 78)
        val pipeline = LedPipeline(160, 90, LedLayout(listOf(1, 0, 1, 0)), LightingProfile.RESPONSIVE, 100)
        val out = ByteArray(6)
        var time = 1L
        fun frame() { pipeline.process(pixels, time, out); time += 250_000_000L }
        frame()
        assertEquals("a new bar waits for stable frames", 0, out[0].toInt() and 255)
        repeat(20) { frame() }
        assertTrue("top LED samples inside the bar", (out[0].toInt() and 255) > 190)
        assertTrue("bottom LED samples inside the bar", (out[3].toInt() and 255) > 190)
        // The film ends and full-screen content fills the old bar: sampling moves back to the edge at once.
        for (y in 0 until 12) for (x in 0 until 160) raw.put(y * 640 + x * 4 + 2, 255.toByte())
        frame()
        assertTrue("blue at the top edge is used immediately", (out[2].toInt() and 255) > 190)
    }

    @Test fun normalOutputIsTheOriginalBrightnessScale() {
        val rgb = ByteArray(256 * 3) { (it / 3).toByte() }
        val out = ByteArray(rgb.size)
        for (brightness in listOf(1, 5, 50, 73, 100)) {
            scaleOutput(rgb, out, brightness, false)
            for (i in rgb.indices) assertEquals(((rgb[i].toInt() and 255) * brightness + 50) / 100, out[i].toInt() and 255)
        }
    }

    @Test fun dramaticDimsDarkLedsKeepsHighlightsAndHue() {
        fun dramatic(r: Int, g: Int, b: Int, brightness: Int = 100): IntArray {
            val out = ByteArray(3)
            scaleOutput(byteArrayOf(r.toByte(), g.toByte(), b.toByte()), out, brightness, true)
            return IntArray(3) { out[it].toInt() and 255 }
        }
        // Highlights untouched, black stays black, never brighter than Normal.
        assertArrayEquals(intArrayOf(255, 255, 255), dramatic(255, 255, 255))
        assertArrayEquals(intArrayOf(0, 0, 0), dramatic(0, 0, 0))
        for (v in 0..255) assertTrue(dramatic(v, v, v)[0] <= v)
        // Contrast: bright:dark ratio of 255:64 (4x) widens to over 8x; shadows dim but stay lit.
        assertTrue(255.0 / dramatic(64, 64, 64)[0] > 8)
        assertTrue(dramatic(16, 16, 16)[0] > 0)
        // Saturated colours keyed by peak, not luma: full blue/red stay full.
        assertArrayEquals(intArrayOf(0, 0, 255), dramatic(0, 0, 255))
        assertArrayEquals(intArrayOf(255, 0, 0), dramatic(255, 0, 0))
        // One gain per LED keeps channel ratios (hue/saturation) within rounding.
        val warm = dramatic(160, 80, 40)
        assertEquals(2.0, warm[0].toDouble() / warm[1], 0.05)
        assertEquals(2.0, warm[1].toDouble() / warm[2], 0.1)
        // Brightness still scales after the curve.
        assertEquals((dramatic(200, 100, 50)[0] + 1) / 2.0, dramatic(200, 100, 50, 50)[0].toDouble(), 1.0)
        // Monotonic and gentle: one input step never jumps more than two output steps (no added flicker).
        for (v in 1..255) assertTrue(dramatic(v, v, v)[0] - dramatic(v - 1, v - 1, v - 1)[0] in 0..2)
    }

    @Test fun cadenceRetainsThirtyWithJitterAndCapsSixty() {
        for (fps in listOf(24, 30, 60)) {
            val gate = FrameCadence(30)
            val accepted = (0 until fps * 10).count {
                gate.accept(it * 1_000_000_000L / fps + (it % 3) * 200_000L)
            }
            assertEquals(minOf(30, fps) * 10, accepted)
        }
    }
}
