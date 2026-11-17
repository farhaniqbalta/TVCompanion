package id.poncol.tvcompanion.airplay.runtime.ambient

import org.junit.Assert.*
import org.junit.Test

class AmbientDownscaleTest {
    private val ms = 1_000_000L

    /** Feeds frames until [untilNs]; [gap] gives the interval before the frame at time t. Returns the fire times. */
    private fun run(d: AmbientDownscale, startNs: Long, untilNs: Long, gap: (Long) -> Long): List<Long> {
        val fired = mutableListOf<Long>()
        var t = startNs
        while (t < untilNs) { t += gap(t); if (d.onFrame(t)) fired += t }
        return fired
    }

    @Test fun healthyThirtyFpsNeverFires() {
        val d = AmbientDownscale().apply { reset(0) }
        assertEquals(emptyList<Long>(), run(d, 0, 10_000 * ms) { 33_333_333 })
    }

    @Test fun sustainedMissFiresOnce() {
        val d = AmbientDownscale().apply { reset(0) }
        val fired = run(d, 0, 20_000 * ms) { 45 * ms }
        assertEquals(1, fired.size)
        assertTrue(fired[0] >= 4_000 * ms && fired[0] < 4_100 * ms)
        assertTrue(d.meanNs > 36 * ms)
    }

    @Test fun warmupSpikesIgnored() {
        val d = AmbientDownscale().apply { reset(0) }
        assertEquals(emptyList<Long>(), run(d, 0, 10_000 * ms) { t -> if (t in 400 * ms until 450 * ms) 300 * ms else 33_333_333 })
    }

    @Test fun briefHiccupIgnored() {
        val d = AmbientDownscale().apply { reset(0) }
        var next = 5_000 * ms
        assertEquals(emptyList<Long>(), run(d, 0, 30_000 * ms) { t ->
            if (t >= next) { next += 5_000 * ms; 120 * ms } else 33_333_333
        })
    }

    @Test fun resetReArms() {
        val d = AmbientDownscale().apply { reset(0) }
        assertEquals(1, run(d, 0, 10_000 * ms) { 45 * ms }.size)
        d.reset(10_000 * ms)
        assertEquals(1, run(d, 10_000 * ms, 20_000 * ms) { 45 * ms }.size)
    }
}
