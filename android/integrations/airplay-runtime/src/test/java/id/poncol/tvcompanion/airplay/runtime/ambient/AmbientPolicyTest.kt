package id.poncol.tvcompanion.airplay.runtime.ambient

import id.poncol.tvcompanion.domain.airplay.AirPlayConnection
import id.poncol.tvcompanion.domain.airplay.AirPlayPhase
import id.poncol.tvcompanion.domain.airplay.AirPlaySnapshot
import org.junit.Assert.*
import org.junit.Test

class AmbientPolicyTest {
    private val ready = AirPlaySnapshot(phase = AirPlayPhase.READY, connection = AirPlayConnection.PLAYING, audioActive = true)
    @Test fun opensOnlyWithSenderAndNoVideo() {
        assertTrue(AmbientPolicy.canOpen(ready))
        assertTrue(AmbientPolicy.canOpen(ready.copy(audioActive = false)))          // paused still allowed
        assertFalse(AmbientPolicy.canOpen(ready.copy(connection = AirPlayConnection.NONE)))
        assertFalse(AmbientPolicy.canOpen(ready.copy(videoActive = true)))
    }
    @Test fun videoForcesClose() {
        assertTrue(AmbientPolicy.mustClose(ready.copy(videoActive = true)))
        assertFalse(AmbientPolicy.mustClose(ready.copy(audioActive = false, connection = AirPlayConnection.NONE)))
    }
}
class AmbientEpochTest {
    @Test fun staleOpenIsRejected() {
        val e = AmbientEpoch(); val first = e.next(); val second = e.next()
        assertFalse(e.isCurrent(first)); assertTrue(e.isCurrent(second))
        e.invalidate(); assertFalse(e.isCurrent(second))
    }
}
