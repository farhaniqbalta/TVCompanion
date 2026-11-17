package id.poncol.tvcompanion.airplay.runtime.ambient

import org.junit.Assert.*
import org.junit.Test

class AmbientDownscaleTest {
    private val ms = 1_000_000L

    /** Feeds frames until [untilNs]; [gap] gives the interval before frame number n. Returns the fire times. */
    private fun run(d: AmbientDownscale, startNs: Long, untilNs: Long, gap: (Int) -> Long): List<Long> {
        val fired = mutableListOf<Long>()
        var t = startNs
        var n = 0
        while (t < untilNs) { t += gap(n++); if (d.onFrame(t)) fired += t }
        return fired
    }

    @Test fun healthyThirtyFpsNeverFires() {
        val d = AmbientDownscale().apply { reset(0) }
        assertEquals(emptyList<Long>(), run(d, 0, 30_000 * ms) { 33_333_333 })
        val jitter = AmbientDownscale().apply { reset(0) }
        assertEquals(emptyList<Long>(), run(jitter, 0, 60_000 * ms) { n ->
            when { n % 45 == 0 -> 37 * ms; n % 2 == 0 -> 33 * ms; else -> 33_700_000 }
        })
    }

    @Test fun sustainedMissFiresOnce() {
        val d = AmbientDownscale().apply { reset(0) }
        val fired = run(d, 0, 20_000 * ms) { 45 * ms }
        assertEquals(1, fired.size)
        assertTrue(fired[0] >= 7_000 * ms && fired[0] < 7_100 * ms)   // warm-up + two windows
        assertTrue(d.meanNs > 33_900_000)
    }

    @Test fun duskLikeSlightMissFires() {
        val d = AmbientDownscale().apply { reset(0) }
        assertEquals(1, run(d, 0, 20_000 * ms) { n -> if (n % 30 == 0) 66_700_000 else 33_333_333 }.size)
    }

    @Test fun singleLongStallIgnored() {
        val d = AmbientDownscale().apply { reset(0) }
        assertEquals(emptyList<Long>(), run(d, 0, 30_000 * ms) { n -> if (n == 100) 300 * ms else 33_333_333 })
    }

    @Test fun warmupSpikesIgnored() {
        val d = AmbientDownscale().apply { reset(0) }
        assertEquals(emptyList<Long>(), run(d, 0, 10_000 * ms) { n -> if (n == 12) 300 * ms else 33_333_333 })
    }

    // Every ~10 s: a 120 ms gap every 5 s averages 33.91 ms, at the 33.9 ms limit by design (comparable to Dusk).
    @Test fun briefHiccupIgnored() {
        val d = AmbientDownscale().apply { reset(0) }
        assertEquals(emptyList<Long>(), run(d, 0, 60_000 * ms) { n -> if (n > 0 && n % 300 == 0) 120 * ms else 33_333_333 })
    }

    @Test fun resetReArms() {
        val d = AmbientDownscale().apply { reset(0) }
        assertEquals(1, run(d, 0, 10_000 * ms) { 45 * ms }.size)
        d.reset(10_000 * ms)
        assertEquals(1, run(d, 10_000 * ms, 20_000 * ms) { 45 * ms }.size)
    }
}
