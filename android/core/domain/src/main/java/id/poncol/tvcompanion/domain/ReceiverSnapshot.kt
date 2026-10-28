package id.poncol.tvcompanion.domain

/** Process-local lifecycle metadata only: never notification content or pairing credentials. */
enum class ReceiverPhase { PAUSED, STARTING, LISTENING, STOPPING, FAILED }
enum class ReceiverIssue { OVERLAY_PERMISSION, START_FAILED, LISTENER_FAILED, OVERLAY_FAILED }
data class ReceiverSnapshot(
    val phase: ReceiverPhase = ReceiverPhase.PAUSED,
    val issue: ReceiverIssue? = null,
    val port: Int? = null,
    val lastAcceptedAt: Long? = null
) {
    val running get() = phase == ReceiverPhase.LISTENING
}
