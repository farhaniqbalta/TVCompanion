package id.poncol.tvcompanion.airplay.runtime
import id.poncol.tvcompanion.domain.airplay.*

/** Serialized lifecycle owner, independent of Android and the native implementation. */
internal class ReceiverSession(private val capabilities: AirPlayCapabilities = AirPlayCapabilities(), private val publish: (AirPlaySnapshot) -> Unit) {
    interface Lease { fun stop(released: () -> Unit) }
    enum class Signal { READY, CONNECTED, VIDEO, AUDIO, AUDIO_STOPPED, AUDIO_UNAVAILABLE, DISPLAY_REQUIRED, DISCONNECTED, FAILED }
    var snapshot = AirPlaySnapshot(availability = AirPlayAvailability.AVAILABLE, capabilities = capabilities)
        private set
    private var lease: Lease? = null
    private var generation = 0L
    private var closed = false
    private var displayAttached = false
    private var videoPlaying = false
    private var connected = false
    val heldInBackground: Boolean get() = snapshot.keepAvailable || (snapshot.keepSession && connected)
    val discoverable: Boolean get() = displayAttached || snapshot.keepAvailable
    val audioAllowed: Boolean get() = displayAttached || (snapshot.keepSession && connected && snapshot.backgroundAudio)
    private fun publishSnapshot() {
        snapshot = snapshot.copy(discoverable = snapshot.phase == AirPlayPhase.READY && discoverable,
            videoActive = snapshot.phase == AirPlayPhase.READY && displayAttached && videoPlaying)
        publish(snapshot)
    }
    private fun state(phase: AirPlayPhase, connection: AirPlayConnection = AirPlayConnection.NONE, issue: AirPlayIssue? = null, audio: Boolean = phase == AirPlayPhase.READY && snapshot.audioActive) {
        snapshot = snapshot.copy(phase = phase, connection = connection, issue = issue, audioActive = audio); publishSnapshot()
    }
    fun start(displayAvailable: Boolean, create: ((Signal) -> Unit) -> Lease): AirPlayResult {
        if (closed) return AirPlayResult(false, AirPlayIssue.CLOSED)
        if (snapshot.phase == AirPlayPhase.READY) return AirPlayResult(true)
        if (snapshot.phase in listOf(AirPlayPhase.STARTING, AirPlayPhase.STOPPING)) return AirPlayResult(false, AirPlayIssue.BUSY)
        displayAttached = displayAvailable
        if (!displayAvailable && !snapshot.keepAvailable) return AirPlayResult(false, AirPlayIssue.DISPLAY_REQUIRED)
        val token = ++generation
        videoPlaying = false
        state(AirPlayPhase.STARTING)
        if (closed || token != generation) return AirPlayResult(false, AirPlayIssue.BUSY)
        var initializing = true
        var initial: Signal? = null
        val receive: (Signal) -> Unit = receive@ { signal ->
                if (closed || token != generation) return@receive
                when (signal) {
                    Signal.READY -> state(AirPlayPhase.READY)
                    Signal.CONNECTED -> { connected = true; state(AirPlayPhase.READY, AirPlayConnection.CONNECTING) }
                    Signal.VIDEO -> { connected = true; videoPlaying = true; state(AirPlayPhase.READY, AirPlayConnection.PLAYING) }
                    Signal.AUDIO -> { connected = true; state(AirPlayPhase.READY, AirPlayConnection.PLAYING, audio = true) }
                    Signal.AUDIO_STOPPED -> state(AirPlayPhase.READY, if (videoPlaying) AirPlayConnection.PLAYING else if (connected) AirPlayConnection.CONNECTING else AirPlayConnection.NONE, snapshot.issue, audio = false)
                    Signal.AUDIO_UNAVAILABLE -> state(AirPlayPhase.READY, snapshot.connection, AirPlayIssue.AUDIO_UNAVAILABLE, audio = false)
                    Signal.DISPLAY_REQUIRED -> { connected = true; state(AirPlayPhase.READY, AirPlayConnection.CONNECTING, AirPlayIssue.DISPLAY_REQUIRED) }
                    Signal.DISCONNECTED -> { connected = false; videoPlaying = false;
                        if (!displayAttached && !snapshot.keepAvailable) stop() else state(AirPlayPhase.READY, audio = false) }
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
    fun attachDisplay() {
        displayAttached = true
        if (snapshot.issue == AirPlayIssue.DISPLAY_REQUIRED) snapshot = snapshot.copy(issue = null)
        publishSnapshot()
    }
    fun detachDisplay() {
        displayAttached = false
        val needsDisplay = videoPlaying || snapshot.connection == AirPlayConnection.CONNECTING
        videoPlaying = false
        if (connected && !snapshot.keepSession) stop(preserveListening = true)
        else if (!heldInBackground) stop()
        else if (needsDisplay) state(AirPlayPhase.READY, AirPlayConnection.CONNECTING, AirPlayIssue.DISPLAY_REQUIRED, audio = snapshot.audioActive && audioAllowed)
        else state(snapshot.phase, snapshot.connection, snapshot.issue, snapshot.audioActive && audioAllowed)
    }
    fun setKeepAvailable(enabled: Boolean): AirPlayResult {
        if (closed) return AirPlayResult(false, AirPlayIssue.CLOSED)
        if (snapshot.phase == AirPlayPhase.STOPPING && enabled) return AirPlayResult(false, AirPlayIssue.BUSY)
        snapshot = snapshot.copy(keepAvailable = enabled); publishSnapshot()
        if (!enabled && !displayAttached && !heldInBackground) stop()
        return AirPlayResult(true)
    }
    fun setBackgroundOptions(keepSession: Boolean, backgroundAudio: Boolean, autoOpenVideo: Boolean): AirPlayResult {
        if (closed) return AirPlayResult(false, AirPlayIssue.CLOSED)
        snapshot = snapshot.copy(keepSession = keepSession, backgroundAudio = backgroundAudio, autoOpenVideo = autoOpenVideo)
        if (!displayAttached && connected && !keepSession) return stop(preserveListening = true)
        state(snapshot.phase, snapshot.connection, snapshot.issue, snapshot.audioActive && audioAllowed)
        return AirPlayResult(true)
    }
    fun stop(failure: AirPlayIssue? = null, preserveListening: Boolean = false): AirPlayResult {
        if (closed) return AirPlayResult(false, AirPlayIssue.CLOSED)
        snapshot = snapshot.copy(keepAvailable = preserveListening && snapshot.keepAvailable)
        connected = false; videoPlaying = false
        if (snapshot.phase == AirPlayPhase.STOPPING) { publishSnapshot(); return AirPlayResult(true) }
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
