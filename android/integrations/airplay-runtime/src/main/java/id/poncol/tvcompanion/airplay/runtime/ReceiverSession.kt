package id.poncol.tvcompanion.airplay.runtime
import id.poncol.tvcompanion.domain.airplay.*

/** Serialized lifecycle owner, independent of Android and the native implementation. */
internal class ReceiverSession(private val capabilities: AirPlayCapabilities = AirPlayCapabilities(), private val publish: (AirPlaySnapshot) -> Unit) {
    interface Lease { fun stop(released: () -> Unit) }
    enum class Signal { READY, CONNECTED, VIDEO, DISCONNECTED, FAILED }
    var snapshot = AirPlaySnapshot(availability = AirPlayAvailability.AVAILABLE, capabilities = capabilities)
        private set
    private var lease: Lease? = null
    private var generation = 0L
    private var closed = false
    private fun state(phase: AirPlayPhase, connection: AirPlayConnection = AirPlayConnection.NONE, issue: AirPlayIssue? = null) {
        snapshot = snapshot.copy(phase = phase, connection = connection, issue = issue); publish(snapshot)
    }
    fun start(displayAvailable: Boolean, create: ((Signal) -> Unit) -> Lease): AirPlayResult {
        if (closed) return AirPlayResult(false, AirPlayIssue.CLOSED)
        if (snapshot.phase == AirPlayPhase.READY) return AirPlayResult(true)
        if (snapshot.phase in listOf(AirPlayPhase.STARTING, AirPlayPhase.STOPPING)) return AirPlayResult(false, AirPlayIssue.BUSY)
        if (!displayAvailable) return AirPlayResult(false, AirPlayIssue.DISPLAY_REQUIRED)
        val token = ++generation
        state(AirPlayPhase.STARTING)
        if (closed || token != generation) return AirPlayResult(false, AirPlayIssue.BUSY)
        var initializing = true
        var initial: Signal? = null
        val receive: (Signal) -> Unit = receive@ { signal ->
                if (closed || token != generation) return@receive
                when (signal) {
                    Signal.READY -> state(AirPlayPhase.READY)
                    Signal.CONNECTED -> state(AirPlayPhase.READY, AirPlayConnection.CONNECTING)
                    Signal.VIDEO -> state(AirPlayPhase.READY, AirPlayConnection.PLAYING)
                    Signal.DISCONNECTED -> state(AirPlayPhase.READY)
                    Signal.FAILED -> stop(if (snapshot.phase == AirPlayPhase.STARTING) AirPlayIssue.START_FAILED else AirPlayIssue.MEDIA_FAILED)
                }
        }
        try {
            val created = create { signal ->
                if (initializing) { if (initial != Signal.FAILED) initial = signal }
                else receive(signal)
            }
            if (!closed && token == generation) {
                lease = created; initializing = false; initial?.let(receive)
            } else { initializing = false; created.stop {} }
        } catch (_: Exception) { ++generation; state(AirPlayPhase.FAILED, issue = AirPlayIssue.START_FAILED); return AirPlayResult(false, AirPlayIssue.START_FAILED) }
        return AirPlayResult(true)
    }
    fun stop(failure: AirPlayIssue? = null): AirPlayResult {
        if (closed) return AirPlayResult(false, AirPlayIssue.CLOSED)
        if (snapshot.phase == AirPlayPhase.STOPPING) return AirPlayResult(true)
        val token = ++generation
        val owned = lease; lease = null
        if (owned == null) { state(if (failure == null) AirPlayPhase.STOPPED else AirPlayPhase.FAILED, issue = failure); return AirPlayResult(true) }
        state(AirPlayPhase.STOPPING, issue = failure)
        try { owned.stop {
            if (!closed && generation == token) state(if (failure == null) AirPlayPhase.STOPPED else AirPlayPhase.FAILED, issue = failure)
        } } catch (_: Exception) { state(AirPlayPhase.FAILED, issue = AirPlayIssue.MEDIA_FAILED) }
        return AirPlayResult(true)
    }
    fun close() { if (!closed) { stop(); closed = true; ++generation } }
}
