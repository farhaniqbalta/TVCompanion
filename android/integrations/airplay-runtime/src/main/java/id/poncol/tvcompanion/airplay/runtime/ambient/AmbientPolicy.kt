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
