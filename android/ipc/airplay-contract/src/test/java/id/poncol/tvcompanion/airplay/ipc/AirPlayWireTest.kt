package id.poncol.tvcompanion.airplay.ipc
import org.junit.Assert.*
import org.junit.Test
import id.poncol.tvcompanion.domain.airplay.*
class AirPlayWireTest {
    @Test fun invalidRemoteStateIsRejectedInsteadOfInventingReady() {
        assertNull(AirPlayWire.snapshot(99, 0, 0))
        assertNull(AirPlayWire.snapshot(0, -1, 0))
        assertNull(AirPlayWire.snapshot(0, 0, 100))
        assertNull(AirPlayWire.snapshot(0, 0, 0, 0, Int.MAX_VALUE, 0, 0))
    }
    @Test fun supportedModesAreIndependentOfImplementationAndUnknownSizesStayUnknown() {
        val unknown = AirPlayWire.snapshot(0, 0, 0)!!
        assertNull(unknown.capabilities.width); assertFalse(unknown.capabilities.video)
        val replacement = AirPlayWire.snapshot(2, 2, 0, 3, 1920, 1080, 60)!!
        assertEquals(AirPlayPhase.READY, replacement.phase)
        assertEquals(AirPlayConnection.PLAYING, replacement.connection)
        assertTrue(replacement.capabilities.audio); assertEquals(1920, replacement.capabilities.width)
    }
    @Test fun malformedFailureCodesNeverBecomeSuccess() {
        assertEquals(AirPlayIssue.UNSUPPORTED, AirPlayWire.issue(-1))
        assertEquals(AirPlayIssue.UNSUPPORTED, AirPlayWire.issue(999))
        assertNull(AirPlayWire.issue(0))
    }
    @Test fun availabilityAndAudioFlagsAreControlMetadataOnly() {
        val state = AirPlayWire.snapshot(2, 2, 0, 3, 1280, 720, 30, 3)!!
        assertTrue(state.keepAvailable); assertTrue(state.audioActive)
        assertNull(AirPlayWire.snapshot(2, 2, 0, flags = 32))
    }


    @Test fun backgroundPoliciesRemainIndependentAcrossWire() {
        for (flags in 0..31) {
            val state = AirPlayWire.snapshot(2, 0, 0, flags = flags)!!
            assertEquals(flags and 1 != 0, state.keepAvailable)
            assertEquals(flags and 2 != 0, state.audioActive)
            assertEquals(flags and 4 != 0, state.keepSession)
            assertEquals(flags and 8 != 0, state.backgroundAudio)
            assertEquals(flags and 16 != 0, state.autoOpenVideo)
        }
    }
}
