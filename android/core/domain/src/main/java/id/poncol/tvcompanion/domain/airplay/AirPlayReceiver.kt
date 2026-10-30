package id.poncol.tvcompanion.domain.airplay

enum class AirPlayAvailability { AVAILABLE, DISCONNECTED, UNSUPPORTED }
enum class AirPlayPhase { STOPPED, STARTING, READY, STOPPING, FAILED }
enum class AirPlayConnection { NONE, CONNECTING, PLAYING }
enum class AirPlayIssue { UNAVAILABLE, UNSUPPORTED, BUSY, DISPLAY_REQUIRED, START_FAILED, MEDIA_FAILED, TIMEOUT, CLOSED, AUDIO_UNAVAILABLE, MEDIA_UNSUPPORTED, PROTECTED_CONTENT }
data class AirPlayCapabilities(val video: Boolean = false, val audio: Boolean = false, val width: Int? = null, val height: Int? = null, val maxFps: Int? = null, val appVideo: Boolean = false)
data class AirPlaySnapshot(
    val availability: AirPlayAvailability = AirPlayAvailability.DISCONNECTED,
    val phase: AirPlayPhase = AirPlayPhase.STOPPED,
    val connection: AirPlayConnection = AirPlayConnection.NONE,
    val issue: AirPlayIssue? = null,
    val capabilities: AirPlayCapabilities = AirPlayCapabilities(),
    val keepAvailable: Boolean = false,
    val audioActive: Boolean = false,
    val keepSession: Boolean = true,
    val backgroundAudio: Boolean = false,
    val autoOpenVideo: Boolean = true,
    val discoverable: Boolean = false,
    val videoActive: Boolean = false
)
data class AirPlayOptions(val receiverName: String) {
    init { require(receiverName.length in 1..40 && receiverName.all { it in ' '..'~' }) }
}
/** Acceptance is not readiness or proof that a picture is visible. */
data class AirPlayResult(val accepted: Boolean, val issue: AirPlayIssue? = null)
interface AirPlayReceiverControl : AutoCloseable {
    val snapshot: AirPlaySnapshot
    fun start(options: AirPlayOptions, reply: (AirPlayResult) -> Unit)
    fun setKeepAvailable(enabled: Boolean, reply: (AirPlayResult) -> Unit)
    fun setBackgroundOptions(keepSession: Boolean, backgroundAudio: Boolean, autoOpenVideo: Boolean, reply: (AirPlayResult) -> Unit)
    fun stop(reply: (AirPlayResult) -> Unit)
    fun observe(observer: (AirPlaySnapshot) -> Unit): AutoCloseable
    /** Releases this client's binding and callbacks; explicit stop owns receiver-wide shutdown. */
    override fun close()
}
