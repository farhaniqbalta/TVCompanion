package id.poncol.tvcompanion.smarthome

import id.poncol.tvcompanion.domain.LightColor
import id.poncol.tvcompanion.domain.LightPresets
import id.poncol.tvcompanion.domain.LightState

/** Pure adjustment policy. Reading or focusing a control never produces a command. */
internal object ColorControls {
    fun base(state: LightState): LightColor? = LightPresets.base(state)
    fun hue(color: LightColor, direction: Int) = color.copy(hue = Math.floorMod(color.hue + direction * 15, 360))
    fun saturation(color: LightColor, direction: Int) = color.copy(saturation = (color.saturation + direction * 100).coerceIn(0, 1000))
    fun value(color: LightColor, direction: Int) = color.copy(value = (color.value + direction * 100).coerceIn(0, 1000))
}
