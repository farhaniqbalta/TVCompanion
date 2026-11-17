package id.poncol.tvcompanion.airplay.runtime
import org.junit.Assert.*
import org.junit.Test

class OnceGateTest {
    @Test fun onlyTheFirstClaimWins() {
        val gate = OnceGate()
        assertTrue(gate.claim()); assertFalse(gate.claim()); assertFalse(gate.claim())
    }
    @Test fun refusedCheckDoesNotUseUpTheDefault() {
        val gate = OnceGate()
        assertFalse(gate.claimIf(false)); assertTrue(gate.claimIf(true)); assertFalse(gate.claimIf(true))
    }
}
