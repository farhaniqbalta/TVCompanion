package id.poncol.tvcompanion.domain

import org.junit.Assert.*
import org.junit.Test

class LightPresetsTest {
    @Test fun colourPresetKeepsConfirmedBrightnessAndSetsFullSaturation() {
        val s = LightState(on = true, whiteMode = true, brightness = 40, color = LightColor(10, 200, 900))
        assertEquals(LightCommand.Color(LightColor(240, 1000, 400)), LightPresets.command(s, "blue"))
    }
    @Test fun whitePresetRequiresWhiteSupport() {
        val s = LightState(on = true, whiteMode = false, brightness = 40, color = LightColor(0, 1000, 1000), colorMode = true)
        assertEquals(LightCommand.WhiteMode, LightPresets.command(s, "white"))
        assertNull(LightPresets.command(s.copy(on = false), "white"))
    }
    @Test fun offOrUnknownPresetIsUnsupported() {
        val s = LightState(on = false, whiteMode = true, brightness = 40, color = LightColor(0, 0, 400))
        assertNull(LightPresets.command(s, "blue"))
        assertNull(LightPresets.command(s.copy(on = true), "teal"))
    }
    @Test fun namesAreDisplayOrder() =
        assertEquals(listOf("red", "orange", "yellow", "green", "blue", "purple", "white"), LightPresets.NAMES)
}
