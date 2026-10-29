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

}
