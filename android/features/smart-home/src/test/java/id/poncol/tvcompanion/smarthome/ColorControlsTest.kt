package id.poncol.tvcompanion.smarthome

import id.poncol.tvcompanion.domain.*
import org.junit.Assert.*
import org.junit.Test

class ColorControlsTest {
    @Test fun switchingFromWhiteUsesConfirmedBrightnessWithoutChangingPower() {
        val state = LightState(true, true, 40, LightColor(120, 1000, 900))
        assertEquals(LightColor(120, 1000, 400), ColorControls.base(state))
        assertNull(ColorControls.base(state.copy(on = false)))
        assertNull(ColorControls.base(state.copy(color = null)))
        assertNull(ColorControls.base(state.copy(whiteMode = false)))
    }
    @Test fun colorAdjustmentsWrapHueClampScalesAndPreserveOtherComponents() {
        val color = LightColor(0, 950, 50)
        assertEquals(color.copy(hue = 345), ColorControls.hue(color, -1))
        assertEquals(5, ColorControls.hue(color.copy(hue = 350), 1).hue)
        assertEquals(color.copy(saturation = 1000), ColorControls.saturation(color, 1))
        assertEquals(0, ColorControls.saturation(color.copy(saturation = 30), -1).saturation)
        assertEquals(color.copy(value = 0), ColorControls.value(color, -1))
        assertEquals(1000, ColorControls.value(color.copy(value = 990), 1).value)
    }
}
