package id.poncol.tvcompanion.airplay.runtime.ambient

/**
 * Detects a look that misses frames for a sustained time: the mean interval over a full window (after warm-up)
 * exceeds the limit. Generic by design: never keyed to look names. Tumbling windows; fires once per reset.
 */
class AmbientDownscale(
    private val windowNs: Long = 3_000_000_000,
    private val warmupNs: Long = 1_000_000_000,
    private val limitNs: Long = 36_000_000,
) {
    private var armedAtNs = 0L
    private var windowStartNs = -1L
    private var frames = 0
    private var fired = false
    /** Mean interval of the window that fired. */
    var meanNs = 0L
        private set

    fun reset(nowNs: Long) {
        armedAtNs = nowNs + warmupNs; windowStartNs = -1L; frames = 0; fired = false
    }

    fun onFrame(nowNs: Long): Boolean {
        if (fired || nowNs < armedAtNs) return false
        if (windowStartNs < 0) { windowStartNs = nowNs; return false }
        frames++
        val elapsed = nowNs - windowStartNs
        if (elapsed < windowNs) return false
        meanNs = elapsed / frames
        windowStartNs = nowNs; frames = 0
        fired = meanNs > limitNs
        return fired
    }
}
