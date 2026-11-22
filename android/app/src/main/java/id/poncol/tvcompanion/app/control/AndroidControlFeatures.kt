package id.poncol.tvcompanion.app.control

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import id.poncol.tvcompanion.airplay.feature.AndroidAirPlayControl
import id.poncol.tvcompanion.airplay.runtime.AirPlayService
import id.poncol.tvcompanion.airplay.runtime.ambient.AmbientShaders
import id.poncol.tvcompanion.airplay.runtime.ambient.AmbientShortcut
import id.poncol.tvcompanion.ambilight.Ambilight
import id.poncol.tvcompanion.ambilight.AmbilightStart
import id.poncol.tvcompanion.ambilight.AmbilightStatus
import id.poncol.tvcompanion.application.LightController
import id.poncol.tvcompanion.application.LightOutcome
import id.poncol.tvcompanion.application.LightResult
import id.poncol.tvcompanion.domain.LightCommand
import id.poncol.tvcompanion.domain.LightPresets
import id.poncol.tvcompanion.domain.LightState
import id.poncol.tvcompanion.domain.ReceiverPhase
import id.poncol.tvcompanion.domain.airplay.AirPlayAvailability
import id.poncol.tvcompanion.domain.airplay.AirPlayConnection
import id.poncol.tvcompanion.domain.airplay.AirPlayPhase
import id.poncol.tvcompanion.notifications.infrastructure.AndroidNotificationRuntime
import id.poncol.tvcompanion.notifications.infrastructure.ReceiverService
import id.poncol.tvcompanion.notifications.infrastructure.ReceiverSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/** Phase 1 entry points behind the dispatcher. Create and close on the main thread; calls block a connection thread. */
class AndroidControlFeatures(context: Context, private val light: LightController) : ControlFeatures, AutoCloseable {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    // Observes AirPlay only while its service runs; never starts the :airplay process for a status read.
    private val airplay = AndroidAirPlayControl(this.context, autoCreate = false)
    private val notificationRuntime = AndroidNotificationRuntime(this.context, ReceiverSettings(this.context))
    @Volatile private var lightConfirmedAt = 0L
    @Volatile private var lastLight: LightState? = null

    /** Runs [block] on main and waits for its single answer; a timeout or throw becomes an exception for the dispatcher. */
    private fun <T : Any> onMain(timeoutMs: Long = 5_000, block: ((T) -> Unit) -> Unit): T {
        val answer = AtomicReference<T?>()
        val latch = CountDownLatch(1)
        main.post {
            try { block { value -> if (answer.compareAndSet(null, value)) latch.countDown() } }
            catch (_: Exception) { latch.countDown() }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw TimeoutException()
        return answer.get() ?: throw IllegalStateException("no answer")
    }
    private fun <T : Any> mainValue(block: () -> T): T = onMain { it(block()) }

    override fun ambient(): AmbientStatus {
        val snapshot = mainValue { airplay.snapshot }
        val state = when {
            snapshot.ambientShowing -> "showing"
            AmbientShortcut.canOpen(snapshot, AmbientShaders.DEFAULT_LOOK) -> "available"
            else -> "unavailable"
        }
        // Written by the :airplay process; MODE_MULTI_PROCESS re-reads the file instead of this process's cache.
        @Suppress("DEPRECATION")
        val look = AmbientShaders.resolve(context.getSharedPreferences("ambient", Context.MODE_MULTI_PROCESS).getString("look", null))
        return AmbientStatus(state, look, AmbientShaders.LOOKS)
    }

    override fun setAmbient(open: Boolean, look: String?): Outcome = mainValue {
        val snapshot = airplay.snapshot
        val launched = if (open) look != null && AmbientShortcut.open(context, snapshot, look) else AmbientShortcut.close(context, snapshot)
        if (launched) Outcome.PENDING else Outcome.INVALID
    }

    override fun ambilight(): String = when (mainValue { Ambilight.status(context) }) {
        AmbilightStatus.OFF -> "off"
        AmbilightStatus.NEEDS_APPROVAL -> "needs_approval"
        AmbilightStatus.STARTING -> "starting"
        AmbilightStatus.RUNNING -> "running"
        AmbilightStatus.STOPPING -> "stopping"
        AmbilightStatus.NOT_SET_UP -> "not_set_up"
        AmbilightStatus.ERROR -> "error"
    }

    override fun setAmbilight(on: Boolean): Outcome = mainValue {
        if (!on) { Ambilight.stop(); Outcome.PENDING } else when (Ambilight.requestStart(context)) {
            AmbilightStart.REQUESTED -> Outcome.PENDING
            AmbilightStart.BUSY -> Outcome.BUSY
            AmbilightStart.NOT_SET_UP -> Outcome.NEEDS_SETUP
            AmbilightStart.FAILED -> Outcome.UNAVAILABLE
        }
    }

    override fun light(): LightStatus {
        // A command's own confirmed readback is fresh enough for the status that follows it; saves a second lamp round trip.
        val recent = lastLight?.takeIf { SystemClock.elapsedRealtime() - lightConfirmedAt < FRESH_MS }
        if (recent != null) return lightStatus(recent)
        val result = onMain<LightResult>(LIGHT_TIMEOUT_MS) { done -> light.refresh { done(it) } }
        remember(result)
        return when (result.outcome) {
            LightOutcome.CONFIRMED -> lightStatus(result.state.light!!)
            LightOutcome.BUSY -> LightStatus("busy", lastLight?.let(::preset), LightPresets.NAMES)
            else -> LightStatus("unavailable", null, LightPresets.NAMES)
        }
    }

    override fun setLight(on: Boolean): Outcome = lightWrite { LightCommand.Power(on) }
    override fun lightPreset(name: String): Outcome = lightWrite { LightPresets.command(it, name) }

    private fun lightWrite(build: (LightState) -> LightCommand?): Outcome {
        val result = onMain<LightResult>(LIGHT_TIMEOUT_MS) { done -> light.readThenExecute(build) { done(it) } }
        remember(result)
        return when (result.outcome) {
            LightOutcome.CONFIRMED -> Outcome.OK
            LightOutcome.BUSY -> Outcome.BUSY
            LightOutcome.UNSUPPORTED -> Outcome.INVALID
            LightOutcome.UNCONFIRMED -> Outcome.UNCONFIRMED
            LightOutcome.UNAVAILABLE, LightOutcome.CLOSED -> Outcome.UNAVAILABLE
        }
    }

    private fun remember(result: LightResult) {
        val state = result.state.light
        if (state != null && result.outcome != LightOutcome.BUSY) { lastLight = state; lightConfirmedAt = SystemClock.elapsedRealtime() }
        if (result.outcome == LightOutcome.UNAVAILABLE || result.outcome == LightOutcome.UNCONFIRMED) { lastLight = null; lightConfirmedAt = 0 }
    }

    private fun lightStatus(state: LightState) = LightStatus(if (state.on) "on" else "off", preset(state), LightPresets.NAMES)

    private fun preset(state: LightState): String? {
        if (!state.on) return null
        if (state.whiteMode) return LightPresets.WHITE
        val color = state.color ?: return null
        if (!state.colorMode || color.saturation != 1000) return null
        return LightPresets.HUES.entries.firstOrNull { it.value == color.hue % 360 }?.key
    }

    override fun notifications(): String = when (mainValue { notificationRuntime.snapshot.phase }) {
        ReceiverPhase.LISTENING -> "listening"
        ReceiverPhase.STARTING -> "starting"
        ReceiverPhase.FAILED -> "failed"
        ReceiverPhase.PAUSED, ReceiverPhase.STOPPING -> "paused"
    }

    override fun setNotifications(on: Boolean): Outcome = mainValue {
        when {
            !on -> { notificationRuntime.pause(); Outcome.PENDING }
            !ReceiverService.canStart(context) -> Outcome.UNAVAILABLE
            else -> { notificationRuntime.start(); Outcome.PENDING }
        }
    }

    override fun airplay(): AirPlayStatus {
        val s = mainValue { airplay.snapshot }
        val state = when {
            s.availability != AirPlayAvailability.AVAILABLE -> "stopped"
            s.phase == AirPlayPhase.READY && s.connection == AirPlayConnection.PLAYING -> "playing"
            s.phase == AirPlayPhase.READY -> "ready"
            s.phase == AirPlayPhase.STARTING || s.phase == AirPlayPhase.STOPPING -> "starting"
            s.phase == AirPlayPhase.FAILED -> "failed"
            else -> "stopped"
        }
        return AirPlayStatus(state, s.availability == AirPlayAvailability.AVAILABLE && s.keepAvailable,
            connected = s.connection != AirPlayConnection.NONE, keepSession = s.keepSession)
    }

    override fun setAirPlay(available: Boolean): Outcome = onMain { done ->
        val running = airplay.snapshot.availability == AirPlayAvailability.AVAILABLE
        when {
            running -> airplay.setKeepAvailable(available) { done(if (it.accepted) Outcome.PENDING else Outcome.UNAVAILABLE) }
            !available -> done(Outcome.OK)
            else -> {
                // Same path as the app-start default; the service declines itself if it can't hold in the background.
                context.startForegroundService(Intent(context, AirPlayService::class.java)
                    .setAction(AirPlayService.ACTION_APP_START).putExtra(AirPlayService.EXTRA_FOREGROUND_START, true))
                done(Outcome.PENDING)
            }
        }
    }

    override fun close() { airplay.close() }

    private companion object {
        const val LIGHT_TIMEOUT_MS = 6_000L
        const val FRESH_MS = 3_000L
    }
}
