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
}
