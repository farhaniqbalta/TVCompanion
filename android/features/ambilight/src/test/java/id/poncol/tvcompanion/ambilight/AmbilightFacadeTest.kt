package id.poncol.tvcompanion.ambilight

import org.junit.Assert.*
import org.junit.Test

class AmbilightFacadeTest {
    @Test fun startDecisionRejectsUnconfiguredAndRunning() {
        assertEquals(AmbilightStart.NOT_SET_UP, Ambilight.startDecision(AmbilightState(), configured = false))
        assertEquals(AmbilightStart.BUSY, Ambilight.startDecision(AmbilightState(phase = Phase.ACTIVE), true))
        assertEquals(AmbilightStart.BUSY, Ambilight.startDecision(AmbilightState(phase = Phase.STARTING), true))
        assertNull(Ambilight.startDecision(AmbilightState(phase = Phase.ERROR, issue = Issue.CAPTURE), true))
    }
    @Test fun statusDistinguishesAwaitingApprovalFromStarting() {
        val s = AmbilightState(phase = Phase.STARTING)
        assertEquals(AmbilightStatus.NEEDS_APPROVAL, Ambilight.status(s, true, attached = false))
        assertEquals(AmbilightStatus.STARTING, Ambilight.status(s, true, attached = true))
        assertEquals(AmbilightStatus.RUNNING, Ambilight.status(s.copy(phase = Phase.ACTIVE), true, true))
        assertEquals(AmbilightStatus.NOT_SET_UP, Ambilight.status(AmbilightState(), false, false))
    }
    @Test fun sessionActionStaysUsableWhileStarting() {
        // Stop/Cancel must work during STARTING, attached or still awaiting approval; only STOPPING disables it.
        assertTrue(Ambilight.actionEnabled(AmbilightState(phase = Phase.STARTING)))
        assertTrue(Ambilight.actionEnabled(AmbilightState(phase = Phase.OFF)))
        assertFalse(Ambilight.actionEnabled(AmbilightState(phase = Phase.STOPPING)))
    }
    @Test fun unopenedConsentExpiresButShownConsentWaits() {
        val s = AmbilightState(epoch = 7, phase = Phase.STARTING)
        assertTrue(Ambilight.consentExpired(s, epoch = 7, attached = false, shownEpoch = 0))
        assertFalse(Ambilight.consentExpired(s, epoch = 7, attached = false, shownEpoch = 7))
        assertFalse(Ambilight.consentExpired(s, epoch = 7, attached = true, shownEpoch = 0))
        assertFalse(Ambilight.consentExpired(s, epoch = 6, attached = false, shownEpoch = 0))
        assertFalse(Ambilight.consentExpired(s.copy(phase = Phase.OFF), epoch = 7, attached = false, shownEpoch = 0))
    }
}
