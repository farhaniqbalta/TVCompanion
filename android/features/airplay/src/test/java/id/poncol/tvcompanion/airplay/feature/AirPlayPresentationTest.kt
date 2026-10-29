package id.poncol.tvcompanion.airplay.feature

import id.poncol.tvcompanion.domain.airplay.*
import org.junit.Assert.*
import org.junit.Test

class AirPlayPresentationTest {
    private val ready = AirPlaySnapshot(availability = AirPlayAvailability.AVAILABLE, phase = AirPlayPhase.READY)
    @Test fun audioNeverImpliesVideoIsVisible() {
        val audio = ready.copy(connection = AirPlayConnection.PLAYING, audioActive = true)
        assertEquals(AirPlayPresentation.Status.AUDIO, AirPlayPresentation.from(audio).status)
        assertEquals(AirPlayPresentation.Status.VIDEO, AirPlayPresentation.from(audio.copy(videoActive = true)).status)
    }
    @Test fun retainedConnectionIsNotAdvertisedAsListening() {
        val retained = ready.copy(connection = AirPlayConnection.CONNECTING, discoverable = false)
        assertEquals(AirPlayPresentation.Status.CONNECTED, AirPlayPresentation.from(retained).status)
        assertEquals(AirPlayPresentation.Status.IDLE, AirPlayPresentation.from(ready).status)
        assertEquals(AirPlayPresentation.Status.LISTENING, AirPlayPresentation.from(ready.copy(discoverable = true)).status)
    }
    @Test fun audioChoiceCanBeStoredButInactiveWithoutSessionRetention() {
        assertFalse(AirPlayPresentation.from(ready.copy(keepSession = false, backgroundAudio = true)).backgroundAudioEffective)
        assertTrue(AirPlayPresentation.from(ready.copy(keepSession = true, backgroundAudio = true)).backgroundAudioEffective)
    }
    @Test fun failuresAndTransitionsTakePriorityOverOldMediaFlags() {
        val stopped = ready.copy(phase = AirPlayPhase.STOPPING, videoActive = true, audioActive = true)
        val model = AirPlayPresentation.from(stopped)
        assertEquals(AirPlayPresentation.Status.STOPPING, model.status)
        assertFalse(model.canOpen); assertFalse(model.canStop); assertFalse(model.canChangePreferences)
        assertEquals(AirPlayPresentation.Status.FAILED, AirPlayPresentation.from(stopped.copy(phase = AirPlayPhase.FAILED)).status)
    }
    @Test fun disconnectedControlsDoNotClaimReceiverStopped() {
        val model = AirPlayPresentation.from(AirPlaySnapshot(issue = AirPlayIssue.UNAVAILABLE))
        assertEquals(AirPlayPresentation.Status.UNAVAILABLE, model.status)
        assertFalse(model.canOpen); assertFalse(model.canChangePreferences)
        assertEquals(AirPlayPresentation.Status.BINDING, AirPlayPresentation.from(AirPlaySnapshot()).status)
    }
}
