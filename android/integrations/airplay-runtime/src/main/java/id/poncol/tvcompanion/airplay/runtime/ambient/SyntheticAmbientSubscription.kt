package id.poncol.tvcompanion.airplay.runtime.ambient

import kotlin.math.sin

/** CP1 stand-in: 120 BPM beat and sine-modulated levels, always OK. Replaced by the real source in Task 6. */
class SyntheticAmbientSubscription : AmbientSubscription {
    private val start = System.nanoTime()

    override fun read(out: FloatArray): AmbientStatus {
        val t = (System.nanoTime() - start) / 1e9f
        val beats = (t / BEAT_SEC).toInt()
        out[AmbientFrame.GENERATION] = beats.toFloat()
        out[AmbientFrame.HAS_RECENT] = 1f
        out[AmbientFrame.RMS] = 0.18f + 0.08f * sin(t * 0.7f)
        out[AmbientFrame.PEAK] = 0.6f + 0.2f * sin(t * 1.3f)
        for (i in 0 until 16) out[AmbientFrame.BANDS + i] = 0.35f + 0.3f * sin(t * (0.5f + 0.17f * i) + i)
        out[AmbientFrame.BEAT_COUNT] = beats.toFloat()
        val age0 = (t - beats * BEAT_SEC) * 1000f
        for (i in 0..3) out[AmbientFrame.BEAT_AGE_MS + i] = if (i <= beats) age0 + i * BEAT_SEC * 1000f else 99000f
        return AmbientStatus.OK
    }

    override fun close() {}

    private companion object { const val BEAT_SEC = 0.5f }
}
