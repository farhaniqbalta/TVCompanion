package id.poncol.tvcompanion.airplay.feature

import id.poncol.tvcompanion.domain.airplay.*

/** UI decisions from receiver facts. No Views, Android resources or native implementation types. */
internal data class AirPlayPresentation(
    val status: Status,
    val canOpen: Boolean,
    val canStop: Boolean,
    val canChangePreferences: Boolean,
    val backgroundAudioEffective: Boolean
) {
    enum class Status { BINDING, UNAVAILABLE, STOPPED, STARTING, STOPPING, FAILED, LISTENING, CONNECTED, AUDIO, VIDEO, IDLE }
    companion object {
        fun from(state: AirPlaySnapshot): AirPlayPresentation {
            val available = state.availability == AirPlayAvailability.AVAILABLE
            val status = when {
                !available && state.issue != null -> Status.UNAVAILABLE
                !available -> Status.BINDING
                state.phase == AirPlayPhase.FAILED -> Status.FAILED
                state.phase == AirPlayPhase.STOPPING -> Status.STOPPING
                state.phase == AirPlayPhase.STARTING -> Status.STARTING
                state.phase == AirPlayPhase.STOPPED -> Status.STOPPED
                state.videoActive -> Status.VIDEO
                state.audioActive -> Status.AUDIO
                state.connection != AirPlayConnection.NONE -> Status.CONNECTED
                state.discoverable -> Status.LISTENING
                else -> Status.IDLE
            }
            return AirPlayPresentation(status, available && state.phase !in listOf(AirPlayPhase.STOPPING, AirPlayPhase.STARTING),
                available && state.phase in listOf(AirPlayPhase.STARTING, AirPlayPhase.READY),
                available && state.phase !in listOf(AirPlayPhase.STOPPING, AirPlayPhase.STARTING),
                state.keepSession && state.backgroundAudio)
        }
    }
}
