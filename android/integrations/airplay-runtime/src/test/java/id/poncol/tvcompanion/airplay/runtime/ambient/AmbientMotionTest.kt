package id.poncol.tvcompanion.airplay.runtime.ambient

import org.junit.Assert.*
import org.junit.Test

class AmbientMotionTest {
    private fun frame(rms: Float = 0f, bass: Float = 0f, recent: Boolean = true, beatAgeMs: Float = 99000f) =
        FloatArray(AmbientFrame.SIZE).also { f ->
            f[AmbientFrame.HAS_RECENT] = if (recent) 1f else 0f; f[AmbientFrame.RMS] = rms
            for (b in 0..3) f[AmbientFrame.BANDS + b] = bass
            for (i in 0..3) f[AmbientFrame.BEAT_AGE_MS + i] = 99000f
            f[AmbientFrame.BEAT_AGE_MS] = beatAgeMs
        }
    @Test fun phasesStayWrappedOverHours() {
        val m = AmbientMotion(false)
        repeat(30 * 3600 * 3) { m.update(frame(rms = 0.3f), AmbientStatus.OK, 1f / 30f, it / 30f) }
        m.phase.forEach { assertTrue(it >= 0f && it < 2f * Math.PI.toFloat()) }
    }
    @Test fun attackFasterThanRelease() {
        val m = AmbientMotion(false)
        m.update(frame(bass = 1f), AmbientStatus.OK, 0.06f, 0f); val up = m.bass
        assertEquals(1f - kotlin.math.exp(-1f), up, 0.02f)                       // one attack tau (60 ms)
        m.update(frame(bass = 0f), AmbientStatus.OK, 0.06f, 0.06f)
        assertTrue(m.bass > up * 0.8f)                                            // release tau 350 ms
    }
    @Test fun idleDecaysAndClosedIsSilent() {
        val m = AmbientMotion(false)
        repeat(30) { m.update(frame(rms = 0.3f, bass = 0.8f), AmbientStatus.OK, 1f / 30f, it / 30f) }
        repeat(150) { m.update(frame(recent = false), AmbientStatus.STALE, 1f / 30f, 1f + it / 30f) }
        assertTrue(m.level < 0.05f && m.bass < 0.05f && m.pulse < 0.05f)
        m.update(frame(rms = 1f, bass = 1f), AmbientStatus.CLOSED, 1f / 30f, 7f)
        assertTrue(m.level < 0.05f)
    }
    @Test fun unheardBeatIsIgnoredAndHeardBeatPulses() {
        val m = AmbientMotion(false)
        m.update(frame(beatAgeMs = -40f), AmbientStatus.OK, 1f / 30f, 0f)
        assertEquals(99f, m.beatAges[0], 0f)
        m.update(frame(beatAgeMs = 50f), AmbientStatus.OK, 1f / 30f, 0.033f)
        assertEquals((50f - AmbientMotion.SYNC_OFFSET_MS) / 1000f, m.beatAges[0], 0.001f); assertTrue(m.pulse > 0.3f)
    }
    @Test fun unheardNewestBeatCompactsHeardBeatsForward() {
        val m = AmbientMotion(false)
        val f = frame(beatAgeMs = -40f).also { it[AmbientFrame.BEAT_AGE_MS + 1] = 400f; it[AmbientFrame.BEAT_AGE_MS + 2] = 900f }
        m.update(f, AmbientStatus.OK, 1f / 30f, 0f)
        assertEquals(-25f, AmbientMotion.SYNC_OFFSET_MS, 0f)
        assertArrayEquals(floatArrayOf(0.425f, 0.925f, 99f, 99f), m.beatAges, 0.001f)
    }
    @Test fun sustainedBassWithoutBeatsDoesNotPulse() {
        val m = AmbientMotion(false)
        repeat(60) { m.update(frame(bass = 1f), AmbientStatus.OK, 1f / 30f, it / 30f) }
        assertEquals(0f, m.pulse, 0.001f); assertTrue(m.bass > 0.9f)
    }
    @Test fun bandsFollowInputAndDecayWhenIdle() {
        val m = AmbientMotion(false)
        val f = frame().also { for (i in 0..15) it[AmbientFrame.BANDS + i] = i / 15f }
        repeat(30) { m.update(f, AmbientStatus.OK, 1f / 30f, it / 30f) }
        for (i in 0..15) assertEquals(i / 15f, m.bands[i], 0.02f)
        repeat(150) { m.update(frame(recent = false), AmbientStatus.STALE, 1f / 30f, 1f + it / 30f) }
        m.bands.forEach { assertTrue(it < 0.05f) }
    }
    @Test fun reducedMotionHasNoPulseAndStaticDither() {
        val m = AmbientMotion(true)
        m.update(frame(beatAgeMs = 50f, bass = 1f), AmbientStatus.OK, 1f / 30f, 0f); val seed = m.ditherSeed
        m.update(frame(beatAgeMs = 80f, bass = 1f), AmbientStatus.OK, 1f / 30f, 0.033f)
        assertEquals(0f, m.pulse, 0f); assertEquals(seed, m.ditherSeed, 0f)
    }
}
