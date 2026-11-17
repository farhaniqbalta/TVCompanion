package id.poncol.tvcompanion.airplay.runtime.ambient

import id.poncol.tvcompanion.domain.airplay.AirPlayConnection
import id.poncol.tvcompanion.domain.airplay.AirPlaySnapshot

object AmbientPolicy {
    fun canOpen(s: AirPlaySnapshot): Boolean = s.connection != AirPlayConnection.NONE && !s.videoActive
    fun mustClose(s: AirPlaySnapshot): Boolean = s.videoActive
}

/** Rejects results of opens that were superseded or invalidated. */
class AmbientEpoch {
    private var current = 0
    fun next(): Int = ++current
    fun isCurrent(epoch: Int): Boolean = epoch == current
    fun invalidate() { current++ }
}

/** Rate-limits reopening after the source reports CLOSED (e.g. receiver restarted). Main thread only. */
internal class AmbientReopen(private val intervalMs: Long = 1000) {
    private var lastOpenMs: Long? = null
    private var lastCheckMs = 0L
    fun shouldReopen(status: AmbientStatus, nowMs: Long): Boolean {
        lastCheckMs = nowMs
        val last = lastOpenMs
        return status == AmbientStatus.CLOSED && (last == null || nowMs - last >= intervalMs)
    }
    fun opened() { lastOpenMs = lastCheckMs }
}
