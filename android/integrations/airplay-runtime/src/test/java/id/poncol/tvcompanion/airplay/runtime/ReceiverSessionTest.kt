package id.poncol.tvcompanion.airplay.runtime
import id.poncol.tvcompanion.domain.airplay.*
import org.junit.Assert.*
import org.junit.Test

class ReceiverSessionTest {
    private class Engine : ReceiverSession.Lease {
        var closes = 0
        var released: (() -> Unit)? = null
        override fun stop(released: () -> Unit) { closes++; this.released = released }
        fun completeStop() { released!!.invoke() }
    }
    @Test fun noNativeWorkWithoutLocalDisplay() {
        val session = ReceiverSession {}; var created = false
        assertEquals(AirPlayIssue.DISPLAY_REQUIRED, session.start(false) { created = true; Engine() }.issue)
        assertFalse(created); assertEquals(AirPlayPhase.STOPPED, session.snapshot.phase)
    }
    @Test fun acceptedIsNotReadyAndRepeatedStartDoesNotAllocateAnotherEngine() {
        val session = ReceiverSession {}; val engine = Engine(); var emit: ((ReceiverSession.Signal) -> Unit)? = null
        assertTrue(session.start(true) { emit = it; engine }.accepted)
        assertEquals(AirPlayPhase.STARTING, session.snapshot.phase)
        assertEquals(AirPlayIssue.BUSY, session.start(true) { fail("second engine"); Engine() }.issue)
        emit!!(ReceiverSession.Signal.READY)
        assertEquals(AirPlayPhase.READY, session.snapshot.phase)
        assertTrue(session.start(true) { fail("second engine"); Engine() }.accepted)
    }
    @Test fun stopDuringStartupSuppressesLateReadyAndWaitsForRealCleanup() {
        val session = ReceiverSession {}; val engine = Engine(); var emit: ((ReceiverSession.Signal) -> Unit)? = null
        session.start(true) { emit = it; engine }; session.stop()
        assertEquals(AirPlayPhase.STOPPING, session.snapshot.phase)
        emit!!(ReceiverSession.Signal.READY)
        assertEquals(AirPlayPhase.STOPPING, session.snapshot.phase)
        session.stop(); assertEquals(1, engine.closes)
        assertEquals(AirPlayIssue.BUSY, session.start(true) { Engine() }.issue)
        engine.completeStop(); assertEquals(AirPlayPhase.STOPPED, session.snapshot.phase)
    }
    @Test fun previousSessionCannotOverwriteNewSessionState() {
        val session = ReceiverSession {}; val first = Engine(); var old: ((ReceiverSession.Signal) -> Unit)? = null
        session.start(true) { old = it; first }; session.stop(); first.completeStop()
        session.start(true) { Engine() }
        old!!(ReceiverSession.Signal.FAILED); old!!(ReceiverSession.Signal.VIDEO)
        assertEquals(AirPlayPhase.STARTING, session.snapshot.phase)
        assertNull(session.snapshot.issue)
    }
    @Test fun decoderFailureReleasesEngineBeforeFailureIsFinal() {
        val session = ReceiverSession {}; val engine = Engine(); var emit: ((ReceiverSession.Signal) -> Unit)? = null
        session.start(true) { emit = it; engine }; emit!!(ReceiverSession.Signal.READY); emit!!(ReceiverSession.Signal.FAILED)
        assertEquals(1, engine.closes); assertEquals(AirPlayPhase.STOPPING, session.snapshot.phase)
        engine.completeStop()
        assertEquals(AirPlayPhase.FAILED, session.snapshot.phase); assertEquals(AirPlayIssue.MEDIA_FAILED, session.snapshot.issue)
    }
    @Test fun startupFailureIsTypedAndCanBeRetriedExplicitly() {
        val session = ReceiverSession {}
        assertEquals(AirPlayIssue.START_FAILED, session.start(true) { error("synthetic initialization failure") }.issue)
        assertEquals(AirPlayPhase.FAILED, session.snapshot.phase)
        assertTrue(session.start(true) { Engine() }.accepted)
    }
    @Test fun closeIsIdempotentAndNoLaterCallbackPublishes() {
        val states = mutableListOf<AirPlaySnapshot>(); val session = ReceiverSession { states.add(it) }; val engine = Engine()
        var emit: ((ReceiverSession.Signal) -> Unit)? = null
        session.start(true) { emit = it; engine }; session.close(); val count = states.size
        session.close(); emit!!(ReceiverSession.Signal.VIDEO); engine.completeStop()
        assertEquals(1, engine.closes); assertEquals(count, states.size)
        assertEquals(AirPlayIssue.CLOSED, session.start(true) { Engine() }.issue)
    }
    @Test fun connectionAndPlaybackAreDifferentFromDiscoverability() {
        val session = ReceiverSession {}; var emit: ((ReceiverSession.Signal) -> Unit)? = null
        session.start(true) { emit = it; Engine() }; emit!!(ReceiverSession.Signal.READY)
        assertEquals(AirPlayConnection.NONE, session.snapshot.connection)
        emit!!(ReceiverSession.Signal.CONNECTED); assertEquals(AirPlayConnection.CONNECTING, session.snapshot.connection)
        emit!!(ReceiverSession.Signal.VIDEO); assertEquals(AirPlayConnection.PLAYING, session.snapshot.connection)
        emit!!(ReceiverSession.Signal.DISCONNECTED); assertEquals(AirPlayConnection.NONE, session.snapshot.connection)
    }
    @Test fun synchronousEngineFailureStillWaitsForResourceCleanup() {
        val session = ReceiverSession {}; val engine = Engine()
        session.start(true) { emit -> emit(ReceiverSession.Signal.FAILED); engine }
        assertEquals(1, engine.closes)
        assertEquals(AirPlayPhase.STOPPING, session.snapshot.phase)
        assertEquals(AirPlayIssue.BUSY, session.start(true) { Engine() }.issue)
        engine.completeStop(); assertEquals(AirPlayPhase.FAILED, session.snapshot.phase)
    }
    @Test fun observerStoppingDuringStartPreventsAllocation() {
        lateinit var session: ReceiverSession
        session = ReceiverSession { if (it.phase == AirPlayPhase.STARTING) session.stop() }
        var allocated = false
        session.start(true) { allocated = true; Engine() }
        assertFalse(allocated); assertEquals(AirPlayPhase.STOPPED, session.snapshot.phase)
    }
    @Test fun capabilitiesComeFromCompositionWithoutEngineStartup() {
        val capabilities = AirPlayCapabilities(true, true, 1920, 1080, 60)
        val session = ReceiverSession(capabilities) {}
        assertEquals(capabilities, session.snapshot.capabilities)
        assertEquals(AirPlayPhase.STOPPED, session.snapshot.phase)
    }

    @Test fun optInAllowsStartupWithoutAnyActivityOrSurface() {
        val session = ReceiverSession {}; val engine = Engine()
        assertFalse(session.snapshot.keepAvailable)
        session.setKeepAvailable(true)
        assertTrue(session.start(false) { engine }.accepted)
        session.detachDisplay()
        assertEquals(0, engine.closes); assertEquals(AirPlayPhase.STARTING, session.snapshot.phase)
    }
    @Test fun backgroundVideoDetachKeepsNetworkingAndRequestsDisplayInsteadOfClosingEngine() {
        val session = ReceiverSession {}; val engine = Engine(); var emit: ((ReceiverSession.Signal) -> Unit)? = null
        session.setKeepAvailable(true); session.start(true) { emit = it; engine }
        emit!!(ReceiverSession.Signal.VIDEO); session.detachDisplay()
        assertEquals(0, engine.closes); assertTrue(session.snapshot.keepAvailable)
        assertEquals(AirPlayIssue.DISPLAY_REQUIRED, session.snapshot.issue)
        session.attachDisplay(); emit!!(ReceiverSession.Signal.VIDEO)
        assertNull(session.snapshot.issue); assertEquals(AirPlayConnection.PLAYING, session.snapshot.connection)
    }
    @Test fun disablingBackgroundWithoutDisplayWaitsForCleanupAndDoesNotRestart() {
        val session = ReceiverSession {}; val engine = Engine()
        session.setKeepAvailable(true); session.start(false) { engine }
        session.setKeepAvailable(false)
        assertEquals(1, engine.closes); assertFalse(session.snapshot.keepAvailable)
        assertEquals(AirPlayPhase.STOPPING, session.snapshot.phase)
        engine.completeStop(); assertEquals(AirPlayPhase.STOPPED, session.snapshot.phase)
        assertEquals(AirPlayIssue.DISPLAY_REQUIRED, session.start(false) { Engine() }.issue)
    }
    @Test fun disablingBackgroundWithDisplayKeepsForegroundPlaybackUntilDisplayLeaves() {
        val session = ReceiverSession {}; val engine = Engine()
        session.setKeepAvailable(true); session.start(true) { engine }
        session.setKeepAvailable(false); assertEquals(0, engine.closes)
        session.detachDisplay(); assertEquals(1, engine.closes)
    }
    @Test fun explicitStopClearsOptInAndNewOwnerDefaultsOff() {
        val session = ReceiverSession {}; val engine = Engine()
        session.setKeepAvailable(true); session.start(false) { engine }; session.stop()
        assertFalse(session.snapshot.keepAvailable); assertEquals(1, engine.closes)
        assertFalse(ReceiverSession {}.snapshot.keepAvailable)
    }
    @Test fun audioFailureIsNonFatalAndDoesNotDropWorkingVideo() {
        val session = ReceiverSession {}; val engine = Engine(); var emit: ((ReceiverSession.Signal) -> Unit)? = null
        session.start(true) { emit = it; engine }; emit!!(ReceiverSession.Signal.VIDEO)
        emit!!(ReceiverSession.Signal.AUDIO); assertTrue(session.snapshot.audioActive)
        emit!!(ReceiverSession.Signal.AUDIO_UNAVAILABLE)
        assertFalse(session.snapshot.audioActive); assertEquals(AirPlayIssue.AUDIO_UNAVAILABLE, session.snapshot.issue)
        assertEquals(AirPlayPhase.READY, session.snapshot.phase); assertEquals(0, engine.closes)
    }
    @Test fun audioCanContinueAfterVideoSurfaceLeavesAndFocusLossDoesNotStopReceiver() {
        val session = ReceiverSession {}; val engine = Engine(); var emit: ((ReceiverSession.Signal) -> Unit)? = null
        session.setBackgroundOptions(true, true, true)
        session.setKeepAvailable(true); session.start(false) { emit = it; engine }
        emit!!(ReceiverSession.Signal.AUDIO); session.detachDisplay()
        assertTrue(session.snapshot.audioActive)
        emit!!(ReceiverSession.Signal.AUDIO_STOPPED)
        assertFalse(session.snapshot.audioActive); assertTrue(session.snapshot.keepAvailable)
        assertEquals(AirPlayPhase.READY, session.snapshot.phase); assertEquals(0, engine.closes)
    }


    @Test fun retainedSessionDoesNotRequireBackgroundDiscoveryOrAudio() {
        val session = ReceiverSession {}; val engine = Engine(); lateinit var emit: (ReceiverSession.Signal) -> Unit
        session.start(true) { emit = it; engine }; emit(ReceiverSession.Signal.CONNECTED)
        session.detachDisplay()
        assertTrue(session.heldInBackground); assertFalse(session.discoverable); assertFalse(session.audioAllowed)
        assertFalse(session.snapshot.keepAvailable); assertEquals(0, engine.closes)
        session.attachDisplay(); assertTrue(session.discoverable); assertTrue(session.audioAllowed)
        session.detachDisplay(); emit(ReceiverSession.Signal.DISCONNECTED)
        assertEquals(1, engine.closes); assertFalse(session.heldInBackground)
    }
    @Test fun sessionRetentionOffEndsConnectionButPreservesIdleListeningChoice() {
        val session = ReceiverSession {}; val engine = Engine(); lateinit var emit: (ReceiverSession.Signal) -> Unit
        session.setKeepAvailable(true); session.setBackgroundOptions(false, false, true)
        session.start(true) { emit = it; engine }; emit(ReceiverSession.Signal.CONNECTED)
        session.detachDisplay(); assertEquals(1, engine.closes); assertTrue(session.snapshot.keepAvailable)
        engine.completeStop(); assertEquals(AirPlayPhase.STOPPED, session.snapshot.phase)
        assertTrue(session.start(false) { Engine() }.accepted)
    }
    @Test fun backgroundAudioRequiresRetentionButDoesNotEnableDiscoveryOrPresentation() {
        val session = ReceiverSession {}; lateinit var emit: (ReceiverSession.Signal) -> Unit
        session.setBackgroundOptions(true, true, false)
        session.start(true) { emit = it; Engine() }; emit(ReceiverSession.Signal.CONNECTED)
        session.detachDisplay()
        assertTrue(session.audioAllowed); assertFalse(session.discoverable); assertFalse(session.snapshot.autoOpenVideo)
        session.setBackgroundOptions(true, false, false)
        assertFalse(session.audioAllowed); assertTrue(session.heldInBackground)
    }
    @Test fun idlePoliciesAloneDoNotCreateAnEngine() {
        val session = ReceiverSession {}
        session.setBackgroundOptions(true, true, true)
        assertFalse(session.heldInBackground); assertFalse(session.audioAllowed)
        assertEquals(AirPlayIssue.DISPLAY_REQUIRED, session.start(false) { fail("idle policy must not start engine"); Engine() }.issue)
    }

    @Test fun displayAndDiscoveryFactsDoNotConflateAudioWithRenderedVideo() {
        val session = ReceiverSession {}; lateinit var emit: (ReceiverSession.Signal) -> Unit
        session.start(true) { emit = it; Engine() }; emit(ReceiverSession.Signal.READY)
        assertTrue(session.snapshot.discoverable); assertFalse(session.snapshot.videoActive)
        emit(ReceiverSession.Signal.AUDIO)
        assertTrue(session.snapshot.audioActive); assertFalse(session.snapshot.videoActive)
        emit(ReceiverSession.Signal.VIDEO); assertTrue(session.snapshot.videoActive)
        session.detachDisplay()
        assertFalse(session.snapshot.discoverable); assertFalse(session.snapshot.videoActive)
        session.attachDisplay()
        assertTrue(session.snapshot.discoverable); assertFalse(session.snapshot.videoActive)
        assertNull(session.snapshot.issue)
        emit(ReceiverSession.Signal.VIDEO); assertTrue(session.snapshot.videoActive)
        session.stop(); assertFalse(session.snapshot.videoActive); assertFalse(session.snapshot.discoverable)
    }
}
