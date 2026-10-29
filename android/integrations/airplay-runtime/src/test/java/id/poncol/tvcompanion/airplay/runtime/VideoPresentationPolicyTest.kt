package id.poncol.tvcompanion.airplay.runtime
import org.junit.Assert.*
import org.junit.Test
class VideoPresentationPolicyTest {
    @Test fun idleOrForbiddenBackgroundNeverLaunches() {
        val policy = VideoPresentationPolicy()
        assertFalse(policy.videoRequested(false, false, true))
        assertFalse(policy.videoRequested(true, false, false))
        assertTrue(policy.videoRequested(true, false, true))
    }
    @Test fun oneAttemptPerVideoConnectionAndNewSenderCanOpenAgain() {
        val policy = VideoPresentationPolicy()
        assertTrue(policy.videoRequested(true, false, true))
        assertFalse(policy.videoRequested(true, false, true))
        policy.disconnected(); assertTrue(policy.videoRequested(true, false, true))
    }
    @Test fun leavingAnAlreadyVisibleSessionIsNotUndoneByReopeningIt() {
        val policy = VideoPresentationPolicy()
        assertFalse(policy.videoRequested(true, true, true))
        assertFalse(policy.videoRequested(true, false, true))
    }
}
