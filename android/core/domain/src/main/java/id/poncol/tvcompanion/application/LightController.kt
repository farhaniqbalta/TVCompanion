package id.poncol.tvcompanion.application

import id.poncol.tvcompanion.domain.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

enum class LightOutcome { CONFIRMED, BUSY, UNSUPPORTED, UNAVAILABLE, UNCONFIRMED, CLOSED }
data class LightResult(val outcome: LightOutcome, val state: LightController.State)

/** UI-independent state holder shared by every caller. One operation at a time; no polling or write retry. */
class LightController(
    private val gateway: LightGateway,
    private val dispatch: ((() -> Unit) -> Unit),
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
) : AutoCloseable {
    data class State(val light: LightState? = null, val busy: Boolean = false, val message: String = "Refresh to read your light")
    var state = State()
        private set
    private val observers = linkedSetOf<(State) -> Unit>()
    // Completion of the single running operation; taken exactly once by its result or by close().
    private var pending: ((LightResult) -> Unit)? = null
    @Volatile private var closed = false
    // Public methods and dispatch callbacks run on the caller's UI/event thread.
    fun observe(observer: (State) -> Unit): AutoCloseable {
        if (closed) return AutoCloseable {}
        observers.add(observer); observer(state)
        return AutoCloseable { observers.remove(observer) }
    }
    fun refresh(done: ((LightResult) -> Unit)? = null) = run(null, done, NO_BUILD)
    fun execute(command: LightCommand, done: ((LightResult) -> Unit)? = null) {
        val light = state.light
        val supported = light != null && when (command) {
            is LightCommand.Brightness -> light.supportsBrightness
            is LightCommand.Color -> light.supportsColor
            LightCommand.WhiteMode -> light.supportsWhiteMode
            is LightCommand.Power -> true
        }
        if (!supported) { done?.invoke(LightResult(if (closed) LightOutcome.CLOSED else LightOutcome.UNSUPPORTED, state)); return }
        run(command, done, NO_BUILD)
    }
    /** Remote path: one worker job reads, builds the command from that fresh state, then writes. */
    fun readThenExecute(build: (LightState) -> LightCommand?, done: (LightResult) -> Unit) = run(null, done, build)

    private fun publish(next: State) { if (!closed) { state = next; observers.toList().forEach { it(next) } } }
    private fun run(command: LightCommand?, done: ((LightResult) -> Unit)?, build: (LightState) -> LightCommand?) {
        if (closed) { done?.invoke(LightResult(LightOutcome.CLOSED, state)); return }
        if (state.busy) { done?.invoke(LightResult(LightOutcome.BUSY, state)); return }
        val writing = command != null
        publish(state.copy(busy = true, message = if (writing) "Confirming your change…" else "Reading local status…"))
        pending = done
        try {
            worker.execute {
                var wrote = writing
                val (next, outcome) = try {
                    var light = if (command != null) gateway.execute(command) else gateway.read()
                    var outcome = LightOutcome.CONFIRMED
                    if (command == null && build !== NO_BUILD) {
                        val built = build(light)
                        if (built == null) outcome = LightOutcome.UNSUPPORTED
                        else { wrote = true; light = gateway.execute(built) }
                    }
                    State(light, message = "Confirmed on your local network") to outcome
                } catch (_: Exception) {
                    if (wrote) State(message = "Change unconfirmed. Refresh before trying again; it may have applied.") to LightOutcome.UNCONFIRMED
                    else State(message = "Light unavailable. Check setup and connection, then refresh.") to LightOutcome.UNAVAILABLE
                }
                dispatch {
                    val callback = pending; pending = null
                    if (closed) { callback?.invoke(LightResult(LightOutcome.CLOSED, state)); return@dispatch }
                    publish(next); callback?.invoke(LightResult(outcome, next))
                }
            }
        } catch (_: RejectedExecutionException) {
            pending = null
            publish(State(message = "Connection closed")); done?.invoke(LightResult(LightOutcome.CLOSED, state))
        }
    }
    override fun close() {
        if (closed) return
        closed = true; observers.clear()
        val callback = pending; pending = null
        worker.shutdownNow()
        // Interrupting a worker alone does not release its active socket.
        try { gateway.close() } catch (_: Exception) { /* Cleanup must not crash the screen. */ }
        callback?.invoke(LightResult(LightOutcome.CLOSED, state))
    }
    private companion object { val NO_BUILD: (LightState) -> LightCommand? = { null } }
}
