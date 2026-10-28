package id.poncol.tvcompanion.application

import id.poncol.tvcompanion.domain.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** UI-independent state holder. One operation at a time; no polling or write retry. */
class LightController(
    private val gateway: LightGateway,
    private val dispatch: ((() -> Unit) -> Unit),
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
) : AutoCloseable {
    data class State(val light: LightState? = null, val busy: Boolean = false, val message: String = "Refresh to read your light")
    var state = State()
        private set
    private var observer: ((State) -> Unit)? = null
    @Volatile private var closed = false
    // Public methods and dispatch callbacks run on the caller's UI/event thread.
    fun observe(observer: (State) -> Unit) { if (!closed) { this.observer = observer; observer(state) } }
    fun refresh() = run(null)
    fun execute(command: LightCommand) {
        val light = state.light ?: return
        if (command is LightCommand.Brightness && !light.supportsBrightness) return
        run(command)
    }
    private fun publish(next: State) { if (!closed) { state = next; observer?.invoke(next) } }
    private fun run(command: LightCommand?) {
        if (closed || state.busy) return
        publish(state.copy(busy = true, message = if (command == null) "Reading local status…" else "Confirming your change…"))
        try {
            worker.execute {
                val next = try {
                    val light = if (command == null) gateway.read() else gateway.execute(command)
                    State(light, message = "Confirmed on your local network")
                } catch (_: Exception) {
                    State(message = if (command == null) "Light unavailable. Check setup and connection, then refresh."
                        else "Change unconfirmed. Refresh before trying again; it may have applied.")
                }
                if (!closed) dispatch { publish(next) }
            }
        } catch (_: RejectedExecutionException) { publish(State(message = "Connection closed")) }
    }
    override fun close() {
        if (closed) return
        closed = true; observer = null
        worker.shutdownNow()
        // Interrupting a worker alone does not release its active socket.
        try { gateway.close() } catch (_: Exception) { /* Cleanup must not crash the screen. */ }
    }
}
