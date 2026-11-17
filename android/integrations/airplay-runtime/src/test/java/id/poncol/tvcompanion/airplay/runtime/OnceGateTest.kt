package id.poncol.tvcompanion.airplay.runtime
import org.junit.Assert.*
import org.junit.Test

class OnceGateTest {
    @Test fun onlyTheFirstClaimWins() {
        val gate = OnceGate()
        assertTrue(gate.claim()); assertFalse(gate.claim()); assertFalse(gate.claim())
    }
}
