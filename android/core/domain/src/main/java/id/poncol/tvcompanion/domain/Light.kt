package id.poncol.tvcompanion.domain

/** Device-independent HSV values. Saturation and value use thousandths to preserve readback precision. */
data class LightColor(val hue: Int, val saturation: Int, val value: Int) {
    init { require(hue in 0..360 && saturation in 0..1000 && value in 0..1000) }
}
data class LightState(
    val on: Boolean, val whiteMode: Boolean, val brightness: Int?,
    val color: LightColor? = null, val colorMode: Boolean = false
) {
    val supportsBrightness get() = on && whiteMode && brightness != null
    val supportsColor get() = on && color != null && (whiteMode || colorMode)
    val supportsWhiteMode get() = on && brightness != null && (whiteMode || colorMode)
}
sealed class LightCommand {
    data class Power(val on: Boolean) : LightCommand()
    data class Brightness(val percent: Int) : LightCommand() {
        init { require(percent in 0..100) }
    }
    data class Color(val color: LightColor) : LightCommand()
    object WhiteMode : LightCommand()
}
/** Blocking port. Call from a worker; successful writes require readback. */
interface LightGateway : AutoCloseable {
    /** The screen owns this gateway. Closing aborts active I/O and prevents further work. */
    override fun close() {}
    fun read(): LightState
    fun execute(command: LightCommand): LightState
}
