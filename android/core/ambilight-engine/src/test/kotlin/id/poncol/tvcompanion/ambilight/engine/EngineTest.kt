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
            scaleOutput(rgb, out, brightness, 0)
            for (i in rgb.indices) assertEquals(((rgb[i].toInt() and 255) * brightness + 50) / 100, out[i].toInt() and 255)
        }
    }

    @Test fun contrastDimsLedsRelativeToTheBrightestAndKeepsHue() {
        /** Scene: the given LED next to a reference LED of [peak] grey; returns the given LED's output. */
        fun scaled(r: Int, g: Int, b: Int, peak: Int = 220, brightness: Int = 100, contrast: Int = 50): IntArray {
            val out = ByteArray(6)
            scaleOutput(byteArrayOf(r.toByte(), g.toByte(), b.toByte(), peak.toByte(), peak.toByte(), peak.toByte()), out, brightness, contrast)
            return IntArray(3) { out[it].toInt() and 255 }
        }
        fun normal(v: Int, brightness: Int = 100) = (v * brightness + 50) / 100
        // 50%: anything at least half the brightest LED matches Normal, at any brightness.
        for (brightness in listOf(30, 50, 100)) for (v in 110..220) {
            assertEquals(normal(v, brightness), scaled(v, v, v, brightness = brightness)[0])
        }
        // 50%: 220:44 (5x) widens to 12x or more; 220:22 (10x) to 50x or more.
        assertTrue(220.0 / scaled(44, 44, 44)[0] >= 12)
        assertTrue(220.0 / scaled(22, 22, 22)[0] >= 50)
        // 100%: output follows level²/peak; the brightest LED still matches Normal at every strength.
        assertEquals(110 * 110 / 220.0, scaled(110, 110, 110, contrast = 100)[0].toDouble(), 1.0)
        for (contrast in 10..100 step 10) assertEquals(220, scaled(220, 220, 220, contrast = contrast)[0])
        // Stronger is never brighter, never brighter than Normal, and one input step moves at most two output steps.
        for (v in 0..220) {
            var previous = v
            for (contrast in 10..100 step 10) {
                val level = scaled(v, v, v, contrast = contrast)[0]
                assertTrue("v=$v contrast=$contrast", level <= previous)
                previous = level
                if (v > 0) assertTrue(level - scaled(v - 1, v - 1, v - 1, contrast = contrast)[0] in 0..2)
            }
        }
        // A uniformly dark scene is not crushed; black stays black.
        assertArrayEquals(intArrayOf(30, 30, 30), scaled(30, 30, 30, peak = 30, contrast = 100))
        assertArrayEquals(intArrayOf(0, 0, 0), scaled(0, 0, 0, peak = 0))
        // Saturated colours are keyed by their brightest channel, not luma: full blue stays full next to white.
        assertArrayEquals(intArrayOf(0, 0, 255), scaled(0, 0, 255, peak = 255, contrast = 100))
        // One gain per LED keeps channel ratios (hue and saturation) within rounding, also when dimmed.
        val warm = scaled(80, 40, 20)
        assertTrue(warm[0] < 80)
        assertEquals(2.0, warm[0].toDouble() / warm[1], 0.1)
        assertEquals(2.0, warm[1].toDouble() / warm[2], 0.2)
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
