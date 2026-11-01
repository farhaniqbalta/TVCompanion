package id.poncol.tvcompanion.airplay.ipc
import id.poncol.tvcompanion.domain.airplay.*
/** Versioned control-only values. Never carry a Surface, media buffer, JNI handle or vendor model. */
object AirPlayWire {
    const val VERSION = 5
    const val SERVICE = "id.poncol.tvcompanion.airplay.runtime.AirPlayService"
    const val PLAYBACK = "id.poncol.tvcompanion.airplay.runtime.AirPlayPlaybackActivity"
    private val phases = listOf(AirPlayPhase.STOPPED, AirPlayPhase.STARTING, AirPlayPhase.READY, AirPlayPhase.STOPPING, AirPlayPhase.FAILED)
    private val connections = listOf(AirPlayConnection.NONE, AirPlayConnection.CONNECTING, AirPlayConnection.PLAYING)
    private val issues = listOf(AirPlayIssue.UNAVAILABLE, AirPlayIssue.UNSUPPORTED, AirPlayIssue.BUSY, AirPlayIssue.DISPLAY_REQUIRED, AirPlayIssue.START_FAILED, AirPlayIssue.MEDIA_FAILED, AirPlayIssue.TIMEOUT, AirPlayIssue.CLOSED, AirPlayIssue.AUDIO_UNAVAILABLE, AirPlayIssue.MEDIA_UNSUPPORTED, AirPlayIssue.PROTECTED_CONTENT)
    fun phase(value: AirPlayPhase) = phases.indexOf(value)
    fun connection(value: AirPlayConnection) = connections.indexOf(value)
    fun issue(value: AirPlayIssue?) = if (value == null) 0 else issues.indexOf(value) + 1
    fun issue(value: Int): AirPlayIssue? = if (value == 0) null else issues.getOrNull(value - 1) ?: AirPlayIssue.UNSUPPORTED
    fun snapshot(phase: Int, connection: Int, issue: Int, capabilities: Int = 0, width: Int = 0, height: Int = 0, maxFps: Int = 0, flags: Int = 0): AirPlaySnapshot? {
        val p = phases.getOrNull(phase) ?: return null
        val c = connections.getOrNull(connection) ?: return null
        if (issue !in 0..issues.size) return null
        if (flags !in 0..127 || capabilities !in 0..7 || width !in 0..8192 || height !in 0..8192 || maxFps !in 0..240) return null
        val modes = AirPlayCapabilities(capabilities and 1 != 0, capabilities and 2 != 0, width.takeIf { it > 0 }, height.takeIf { it > 0 }, maxFps.takeIf { it > 0 }, capabilities and 4 != 0)
        return AirPlaySnapshot(AirPlayAvailability.AVAILABLE, p, c, issue(issue), modes, flags and 1 != 0, flags and 2 != 0, flags and 4 != 0, flags and 8 != 0, flags and 16 != 0, flags and 32 != 0, flags and 64 != 0)
    }
}
