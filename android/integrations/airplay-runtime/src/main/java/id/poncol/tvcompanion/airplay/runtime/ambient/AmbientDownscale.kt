package id.poncol.tvcompanion.airplay.runtime.ambient

/**
 * Detects a look that misses frames for a sustained time: two consecutive full windows (after warm-up) each with a
 * mean interval over the limit. One window alone is not enough, so a single long stall cannot downgrade a healthy
 * look. Generic by design: never keyed to look names. Fires once per reset.
 */
class AmbientDownscale(
    private val windowNs: Long = 3_000_000_000,
    private val warmupNs: Long = 1_000_000_000,
    private val limitNs: Long = 33_900_000,   // healthy looks measured 33.33-33.39 ms on the TV, Dusk 34.05 ms
) {
    private var armedAtNs = 0L
    private var windowStartNs = -1L
    private var frames = 0
    private var slowWindows = 0
    private var fired = false
    /** Mean interval of the window that fired. */
    var meanNs = 0L
        private set

    fun reset(nowNs: Long) {
        armedAtNs = nowNs + warmupNs; windowStartNs = -1L; frames = 0; slowWindows = 0; fired = false
    }

    fun onFrame(nowNs: Long): Boolean {
        if (fired || nowNs < armedAtNs) return false
        if (windowStartNs < 0) { windowStartNs = nowNs; return false }
        frames++
        val elapsed = nowNs - windowStartNs
        if (elapsed < windowNs) return false
        meanNs = elapsed / frames
        windowStartNs = nowNs; frames = 0
        slowWindows = if (meanNs > limitNs) slowWindows + 1 else 0
        fired = slowWindows >= 2
        return fired
    }
}
