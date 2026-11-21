package id.poncol.tvcompanion.ambilight

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class SessionControllerTest {
    @Test fun ledCountMismatchIsScopedToTheFailedSession() {
        val controller = SessionController()
        val first = controller.begin()!!
        val mismatch = LedCountMismatch(configured = 60, available = 30)
        controller.finish(first, Issue.CONTROLLER, mismatch)
        assertEquals(mismatch, controller.state.ledCountMismatch)
        val next = controller.begin()!!
        controller.finish(first, Issue.CONTROLLER, mismatch)
        assertEquals(next, controller.state.epoch)
        assertNull(controller.state.ledCountMismatch)
    }

    @Test fun pendingConsentIsInvalidatedByStop() {
        val controller = SessionController()
        val canceled = controller.begin()!!
        assertNull(controller.begin())
        controller.stop()
        assertFalse(controller.attach(canceled) {})
        val next = controller.begin()!!
        assertNotEquals(canceled, next)
        controller.cancel(canceled)
        assertEquals(Phase.STARTING, controller.state.phase)
    }

    @Test fun staleEventsAndRepeatedStopNeverSendAfterStopping() {
        val random = Random(4048)
        repeat(1000) {
            val c = SessionController()
            var epoch = 0L
            var stopCalls = 0
            var attached = false
            repeat(40) {
                val event = if (random.nextBoolean()) epoch else epoch - 1
                when (random.nextInt(7)) {
                    0 -> c.begin()?.let { epoch = it; attached = false }
                    1 -> if (c.attach(event) { stopCalls++ }) attached = true
                    2 -> c.activate(event)
                    3 -> {
                        val before = c.state
                        val calls = stopCalls
                        c.stop()
                        assertEquals(calls + if (attached && before.phase in listOf(Phase.STARTING, Phase.ACTIVE)) 1 else 0, stopCalls)
                    }
                    4 -> c.finish(event, Issue.CONTROLLER)
                    5 -> c.health(event, blank = true, sendError = true)
                    else -> {
                        var sends = 0
                        c.send(event) { sends++ }
                        assertEquals(if (event == c.state.epoch && c.state.phase == Phase.ACTIVE) 1 else 0, sends)
                    }
                }
            }
        }
    }

    @Test fun finishAfterRevocationPublishesErrorAndAllowsFreshConsent() {
        val c = SessionController()
        val epoch = c.begin()!!
        assertTrue(c.attach(epoch) {})
        c.activate(epoch)
        c.stop()
        assertEquals(Phase.STOPPING, c.state.phase)
        c.finish(epoch, Issue.REVOKED)
        assertEquals(Phase.ERROR, c.state.phase)
        assertEquals(Issue.REVOKED, c.state.issue)
        assertNotEquals(epoch, c.begin())
    }
}
