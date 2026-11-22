package id.poncol.tvcompanion.ambilight

import id.poncol.tvcompanion.ambilight.engine.LedLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class StripDiagramTest {
    private val counts = listOf(44, 26, 44, 26)

    @Test fun firstLedRingFollowsCornerDirectionAndSkip() {
        // Top-left, clockwise: LED 0 is the first top LED and LED 1 is right of it.
        val plain = LedLayout(counts)
        assertEquals(0, clockwiseIndexOf(plain, 0))
        assertEquals(1, clockwiseIndexOf(plain, 1))
        // Starting at the top-right corner: LED 0 is the first right-edge LED.
        assertEquals(44, clockwiseIndexOf(LedLayout(counts, startCorner = 1), 0))
        // Top-left, counterclockwise: LED 0 is the top LED of the left edge and LED 1 is below it.
        val reverse = LedLayout(counts, clockwise = false)
        assertEquals(139, clockwiseIndexOf(reverse, 0))
        assertEquals(138, clockwiseIndexOf(reverse, 1))
        // Skipping 3 LEDs shifts the strip, so physical LED 3 sits where LED 0 sat.
        assertEquals(0, clockwiseIndexOf(LedLayout(counts, firstLed = 3), 3))
    }
}
