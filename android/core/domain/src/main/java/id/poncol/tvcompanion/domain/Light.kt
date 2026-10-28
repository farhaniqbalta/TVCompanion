package id.poncol.tvcompanion.domain

data class LightState(val on: Boolean, val whiteMode: Boolean, val brightness: Int?) {
    val supportsBrightness get() = on && whiteMode && brightness != null
}
sealed class LightCommand {
    data class Power(val on: Boolean) : LightCommand()
    data class Brightness(val percent: Int) : LightCommand() {
        init { require(percent in 0..100) }
    }
}
/** Blocking port. Call from a worker; successful writes require readback. */
interface LightGateway : AutoCloseable {
    /** The screen owns this gateway. Closing aborts active I/O and prevents further work. */
    override fun close() {}
    fun read(): LightState
    fun execute(command: LightCommand): LightState
}
