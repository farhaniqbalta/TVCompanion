package id.poncol.tvcompanion.ambilight

import android.content.Context
import android.content.Intent
import id.poncol.tvcompanion.ambilight.AmbilightCaptureService.Companion.EXTRA_EPOCH

enum class AmbilightStatus { OFF, NEEDS_APPROVAL, STARTING, RUNNING, STOPPING, NOT_SET_UP, ERROR }
enum class AmbilightStart { REQUESTED, BUSY, NOT_SET_UP, FAILED }

/** Headless entry point for Ambilight. Main thread only, like the session it drives. */
object Ambilight {
    fun status(context: Context): AmbilightStatus =
        status(ambilightSession.state, configured(context), ambilightSession.attached)

    fun observe(context: Context, observer: (AmbilightStatus) -> Unit): AutoCloseable {
        val configured = configured(context)
        val listener: (AmbilightState) -> Unit = { observer(status(it, configured, ambilightSession.attached)) }
        ambilightSession.observe(listener)
        return AutoCloseable { ambilightSession.remove(listener) }
    }

    /** Opens the screen-capture approval; Android skips the prompt when PROJECT_MEDIA is allowed for this app. */
    fun requestStart(context: Context): AmbilightStart {
        startDecision(ambilightSession.state, configured(context))?.let { return it }
        val epoch = ambilightSession.begin() ?: return AmbilightStart.BUSY
        return try {
            context.startActivity(Intent(context, ConsentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(EXTRA_EPOCH, epoch))
            AmbilightStart.REQUESTED
        } catch (_: RuntimeException) {
            ambilightSession.cancel(epoch, Issue.CONSENT)
            AmbilightStart.FAILED
        }
    }

    fun stop() = ambilightSession.stop()

    internal fun status(state: AmbilightState, configured: Boolean, attached: Boolean): AmbilightStatus = when {
        !configured -> AmbilightStatus.NOT_SET_UP
        state.phase == Phase.STARTING -> if (attached) AmbilightStatus.STARTING else AmbilightStatus.NEEDS_APPROVAL
        state.phase == Phase.ACTIVE -> AmbilightStatus.RUNNING
        state.phase == Phase.STOPPING -> AmbilightStatus.STOPPING
        state.phase == Phase.ERROR -> AmbilightStatus.ERROR
        else -> AmbilightStatus.OFF
    }

    /** Null when a new session may start. */
    internal fun startDecision(state: AmbilightState, configured: Boolean): AmbilightStart? = when {
        !configured -> AmbilightStart.NOT_SET_UP
        state.phase != Phase.OFF && state.phase != Phase.ERROR -> AmbilightStart.BUSY
        else -> null
    }

    private fun configured(context: Context) = AmbilightPreferences(context).load().address.isNotEmpty()
}
