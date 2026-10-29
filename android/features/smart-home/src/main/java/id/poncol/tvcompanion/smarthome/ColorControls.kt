package id.poncol.tvcompanion.smarthome

import id.poncol.tvcompanion.domain.LightColor
import id.poncol.tvcompanion.domain.LightState

/** Pure adjustment policy. Reading or focusing a control never produces a command. */
internal object ColorControls {
    fun base(state: LightState): LightColor? {
        if (!state.supportsColor) return null
        val color = state.color ?: return null
        // Starting from white preserves the confirmed brightness approximately across the two scales.
        return if (state.whiteMode) color.copy(value = state.brightness?.times(10) ?: color.value) else color
    }
    fun hue(color: LightColor, direction: Int) = color.copy(hue = Math.floorMod(color.hue + direction * 15, 360))
    fun saturation(color: LightColor, direction: Int) = color.copy(saturation = (color.saturation + direction * 100).coerceIn(0, 1000))
    fun value(color: LightColor, direction: Int) = color.copy(value = (color.value + direction * 100).coerceIn(0, 1000))
}
