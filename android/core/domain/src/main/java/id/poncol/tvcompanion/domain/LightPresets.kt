package id.poncol.tvcompanion.domain

/** Named presets shared by the TV screen and remote clients. Commands derive from confirmed state only. */
object LightPresets {
    val HUES: Map<String, Int> = linkedMapOf("red" to 0, "orange" to 30, "yellow" to 60, "green" to 120, "blue" to 240, "purple" to 280)
    const val WHITE = "white"
    val NAMES: List<String> = HUES.keys.toList() + WHITE

    fun base(state: LightState): LightColor? {
        if (!state.supportsColor) return null
        val color = state.color ?: return null
        // Starting from white preserves the confirmed brightness approximately across the two scales.
        return if (state.whiteMode) color.copy(value = state.brightness?.times(10) ?: color.value) else color
    }

    /** Null when the preset is unknown or the light can't take it in its current state. */
    fun command(state: LightState, name: String): LightCommand? {
        if (name == WHITE) return if (state.supportsWhiteMode) LightCommand.WhiteMode else null
        val hue = HUES[name] ?: return null
        return base(state)?.let { LightCommand.Color(it.copy(hue = hue, saturation = 1000)) }
    }
}
