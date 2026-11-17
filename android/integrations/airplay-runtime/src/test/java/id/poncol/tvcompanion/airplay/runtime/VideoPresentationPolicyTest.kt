package id.poncol.tvcompanion.airplay.runtime
import org.junit.Assert.*
import org.junit.Test
class VideoPresentationPolicyTest {
    @Test fun portraitPhoneIsPillarboxedNotStretched() {
        assertEquals(405 to 720, fitVideo(1170, 2080, 1280, 720))   // portrait iPhone on a 720p screen
        assertEquals(1920 to 1080, fitVideo(1280, 720, 1920, 1080)) // same aspect fills
        assertEquals(1280 to 548, fitVideo(1920, 822, 1280, 720))   // wide film is letterboxed
        assertNull(fitVideo(0, 720, 1280, 720)); assertNull(fitVideo(1280, 720, 0, 0))
    }
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
