package id.poncol.tvcompanion.airplay.runtime.ambient

import kotlin.math.exp
import kotlin.math.min

class AmbientMotion(private val reduced: Boolean) {
    var level = 0f; private set
    var bass = 0f; private set
    var mid = 0f; private set
    var high = 0f; private set
    var pulse = 0f; private set
    var beatCount = 0f; private set
    var ditherSeed = 0f; private set
    val phase = FloatArray(4)
    val bands = FloatArray(16)
    val beatAges = FloatArray(4) { 99f }

    fun update(frame: FloatArray, status: AmbientStatus, dtSec: Float, timeSec: Float) {
        val idle = status != AmbientStatus.OK || frame[AmbientFrame.HAS_RECENT] == 0f
        var heard = 0   // unheard (future) beats are skipped so slot 0 is the newest heard beat
        for (i in 0..3) {
            val raw = frame[AmbientFrame.BEAT_AGE_MS + i]
            val ms = raw - SYNC_OFFSET_MS
            if (ms >= 0f && raw < 99000f) beatAges[heard++] = ms / 1000f
        }
        for (i in heard..3) beatAges[i] = 99f
        if (idle) {
            level = ease(level, 0f, dtSec, IDLE_RELEASE, IDLE_RELEASE); bass = ease(bass, 0f, dtSec, IDLE_RELEASE, IDLE_RELEASE)
            mid = ease(mid, 0.5f, dtSec, IDLE_RELEASE, IDLE_RELEASE); high = ease(high, 0f, dtSec, IDLE_RELEASE, IDLE_RELEASE)
            for (i in 0..15) bands[i] = ease(bands[i], 0f, dtSec, IDLE_RELEASE, IDLE_RELEASE)
            pulse = 0f
        } else {
            level = ease(level, min(1f, frame[AmbientFrame.RMS] * 3.2f), dtSec, 150f, 800f)
            bass = ease(bass, mean(frame, 0, 4), dtSec, 60f, 350f)
            mid = ease(mid, mean(frame, 4, 10), dtSec, 400f, 400f)
            high = ease(high, mean(frame, 10, 16), dtSec, 80f, 500f)
            val release = if (reduced) 600f else 250f
            for (i in 0..15) bands[i] = ease(bands[i], frame[AmbientFrame.BANDS + i], dtSec, 50f, release)
            val age = beatAges[0]
            val env = if (age < 0.04f) age / 0.04f else exp(-(age - 0.04f) / 0.3f)
            // Beats only: a bass floor here kept the pulse up between beats and hid them. Looks read uBass separately.
            pulse = if (reduced) 0f else ease(pulse, env, dtSec, 40f, 450f)
        }
        beatCount = frame[AmbientFrame.BEAT_COUNT]
        val speed = (if (idle) 0.5f else 0.6f + 0.7f * level) * (if (reduced) 0.25f else 1f)
        for (i in 0..3) phase[i] = (phase[i] + dtSec * RATES[i] * speed) % TWO_PI
        ditherSeed = if (reduced) 0f else (timeSec * 61f) % 97f
    }

    private fun mean(f: FloatArray, from: Int, to: Int): Float {
        var s = 0f; for (i in from until to) s += f[AmbientFrame.BANDS + i]; return s / (to - from)
    }
    private fun ease(cur: Float, target: Float, dt: Float, attackMs: Float, releaseMs: Float): Float =
        cur + (target - cur) * (1f - exp(-dt * 1000f / (if (target > cur) attackMs else releaseMs)))

    companion object {
        const val SYNC_OFFSET_MS = -25f
        private const val IDLE_RELEASE = 1500f
        private val TWO_PI = 2f * Math.PI.toFloat()
        private val RATES = floatArrayOf(0.11f, 0.083f, 0.047f, 0.031f)
    }
}
