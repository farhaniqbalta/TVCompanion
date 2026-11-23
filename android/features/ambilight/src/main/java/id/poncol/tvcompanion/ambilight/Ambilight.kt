package id.poncol.tvcompanion.ambilight

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import id.poncol.tvcompanion.ambilight.AmbilightCaptureService.Companion.EXTRA_EPOCH
import id.poncol.tvcompanion.ambilight.engine.LightingProfile

enum class AmbilightStatus { OFF, NEEDS_APPROVAL, STARTING, RUNNING, STOPPING, NOT_SET_UP, ERROR }
enum class AmbilightStart { REQUESTED, BUSY, NOT_SET_UP, FAILED }
/** Style and contrast by wire name, for example "cinema" and "dramatic". */
data class AmbilightLook(val style: String, val contrast: String)

/** Headless entry point for Ambilight. Main thread only, like the session it drives. */
object Ambilight {
    private const val CONSENT_LAUNCH_TIMEOUT_MS = 5_000L
    /** Epoch whose ConsentActivity actually started; written by ConsentActivity on the main thread. */
    internal var shownEpoch = 0L
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
            Handler(Looper.getMainLooper()).postDelayed({
                if (consentExpired(ambilightSession.state, epoch, ambilightSession.attached, shownEpoch)) ambilightSession.cancel(epoch, Issue.CONSENT)
            }, CONSENT_LAUNCH_TIMEOUT_MS)
            AmbilightStart.REQUESTED
        } catch (_: RuntimeException) {
            ambilightSession.cancel(epoch, Issue.CONSENT)
            AmbilightStart.FAILED
        }
    }

    fun stop() = ambilightSession.stop()

    val STYLES = LightingProfile.entries.map { it.name.lowercase() }
    val CONTRASTS = listOf("normal", "dramatic")

    fun look(context: Context): AmbilightLook = AmbilightPreferences(context).load()
        .let { AmbilightLook(it.profile.name.lowercase(), CONTRASTS[if (it.dramatic) 1 else 0]) }

    /** Saves the given parts and applies them to a running session at once; false for an unknown name. */
    fun setLook(context: Context, style: String?, contrast: String?): Boolean {
        if ((style != null && style !in STYLES) || (contrast != null && contrast !in CONTRASTS)) return false
        val prefs = AmbilightPreferences(context)
        val old = prefs.load()
        val config = old.copy(
            profile = style?.let { LightingProfile.entries[STYLES.indexOf(it)] } ?: old.profile,
            dramatic = contrast?.let { it == "dramatic" } ?: old.dramatic,
        )
        prefs.save(config)
        ambilightSession.look = Look(config.profile, config.brightness, config.dramatic)
        return true
    }

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

    /** Stop/Cancel stays usable through STARTING (attached or awaiting approval); only STOPPING disables it. */
    internal fun actionEnabled(state: AmbilightState) = state.phase != Phase.STOPPING

    /** A launch Android silently blocked never shows ConsentActivity; release that session instead of staying BUSY. */
    internal fun consentExpired(state: AmbilightState, epoch: Long, attached: Boolean, shownEpoch: Long) =
        state.epoch == epoch && state.phase == Phase.STARTING && !attached && shownEpoch != epoch

    private fun configured(context: Context) = AmbilightPreferences(context).load().address.isNotEmpty()
}
