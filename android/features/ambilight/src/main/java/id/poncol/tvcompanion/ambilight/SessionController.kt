package id.poncol.tvcompanion.ambilight

import java.security.SecureRandom

internal enum class Phase { OFF, STARTING, ACTIVE, STOPPING, ERROR }
internal enum class Issue { CONSENT, CAPTURE, REVOKED, CONTROLLER, STOP_OUTPUT, TEST_COMPLETE }

internal data class AmbilightState(
    val epoch: Long = 0,
    val phase: Phase = Phase.OFF,
    val issue: Issue? = null,
    val testing: Boolean = false,
    val controllerName: String? = null,
    val blank: Boolean = false,
    val sendError: Boolean = false,
    val controllerUnreachable: Boolean = false,
)

/** Main-loop state writer. The capture thread only enters the nonblocking send gate. */
internal class SessionController {
    private val sendGate = Any()
    private val observers = linkedSetOf<(AmbilightState) -> Unit>()
    private var stopResources: (() -> Unit)? = null
    var state = AmbilightState(epoch = SecureRandom().nextLong().ushr(2))
        private set

    fun observe(observer: (AmbilightState) -> Unit) {
        observers.add(observer)
        observer(state)
    }

    fun remove(observer: (AmbilightState) -> Unit) { observers.remove(observer) }

    private fun publish(next: AmbilightState) {
        synchronized(sendGate) {
            if (state == next) return
            state = next
        }
        observers.toList().forEach { it(next) }
    }

    fun begin(testing: Boolean = false): Long? {
        if (state.phase != Phase.OFF && state.phase != Phase.ERROR) return null
        val epoch = state.epoch + 1
        publish(AmbilightState(epoch, Phase.STARTING, testing = testing))
        return epoch
    }

    fun attach(epoch: Long, stop: () -> Unit): Boolean {
        if (epoch != state.epoch || state.phase != Phase.STARTING || stopResources != null) return false
        stopResources = stop
        return true
    }

    fun activate(epoch: Long, controllerName: String? = null) {
        if (state.epoch == epoch && state.phase == Phase.STARTING && stopResources != null) {
            publish(state.copy(phase = Phase.ACTIVE, controllerName = controllerName))
        }
    }

    fun cancel(epoch: Long, issue: Issue? = null) {
        if (state.epoch == epoch && state.phase == Phase.STARTING && stopResources == null) finish(epoch, issue)
    }

    fun stop() {
        if (state.phase != Phase.STARTING && state.phase != Phase.ACTIVE) return
        val stop = stopResources
        if (stop == null) finish(state.epoch, null) else {
            publish(state.copy(phase = Phase.STOPPING))
            stop()
        }
    }

    fun health(epoch: Long, blank: Boolean? = null, sendError: Boolean? = null, unreachable: Boolean? = null) {
        if (state.epoch != epoch || state.phase != Phase.ACTIVE) return
        publish(state.copy(
            blank = blank ?: state.blank,
            sendError = sendError ?: state.sendError,
            controllerUnreachable = unreachable ?: state.controllerUnreachable,
        ))
    }

    fun finish(epoch: Long, issue: Issue?) {
        if (state.epoch != epoch || state.phase !in livePhases) return
        stopResources = null
        val phase = if (issue == null || issue == Issue.TEST_COMPLETE) Phase.OFF else Phase.ERROR
        publish(AmbilightState(epoch, phase, issue, controllerName = state.controllerName))
    }

    inline fun send(epoch: Long, action: () -> Unit) {
        synchronized(sendGate) {
            if (state.epoch == epoch && state.phase == Phase.ACTIVE) action()
        }
    }

    private companion object {
        val livePhases = setOf(Phase.STARTING, Phase.ACTIVE, Phase.STOPPING)
    }
}

internal val ambilightSession = SessionController()
