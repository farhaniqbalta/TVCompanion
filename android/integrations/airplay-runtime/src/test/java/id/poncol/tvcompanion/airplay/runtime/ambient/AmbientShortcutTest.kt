package id.poncol.tvcompanion.airplay.runtime.ambient

import id.poncol.tvcompanion.domain.airplay.AirPlayConnection
import id.poncol.tvcompanion.domain.airplay.AirPlaySnapshot
import org.junit.Assert.*
import org.junit.Test

class AmbientShortcutTest {
    @Test fun unknownLookIsRejected() {
        assertNull(AmbientShortcut.parse("open", "neon"))
        assertNull(AmbientShortcut.parse("open", null))
        assertEquals(AmbientRequest.Open("dusk"), AmbientShortcut.parse("open", "dusk"))
        assertNull(AmbientShortcut.parse("explode", null))
    }
    @Test fun closeRequiresShowing() {
        assertEquals(AmbientRequest.Close, AmbientShortcut.parse("close", null))
        assertFalse(AmbientShortcut.canClose(AirPlaySnapshot(ambientShowing = false)))
        assertTrue(AmbientShortcut.canClose(AirPlaySnapshot(ambientShowing = true)))
    }
    @Test fun openRequiresAudioWithoutVideoAndAKnownLook() {
        val music = AirPlaySnapshot(connection = AirPlayConnection.PLAYING)
        assertTrue(AmbientShortcut.canOpen(music, "aurora"))
        assertFalse(AmbientShortcut.canOpen(music, "neon"))
        assertFalse(AmbientShortcut.canOpen(AirPlaySnapshot(), "aurora"))
        assertFalse(AmbientShortcut.canOpen(music.copy(videoActive = true), "aurora"))
    }
}
