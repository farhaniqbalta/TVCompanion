package id.poncol.tvcompanion.application

import id.poncol.tvcompanion.domain.*
import org.junit.Assert.*
import org.junit.Test

class NotificationControllerTest {
    private class Preferences : NotificationPreferences {
        override var port = 8766
        override var duration = 8
        override var mode = "privacy"
        override var reducedMotion = false
        val sources = mutableMapOf<String, Boolean>()
        override fun enabled(source: String) = sources[source] ?: true
        override fun setEnabled(source: String, enabled: Boolean) { sources[source] = enabled }
    }
    private class Runtime : NotificationRuntime {
        override var snapshot = ReceiverSnapshot()
        override var running: Boolean
            get() = snapshot.running
            set(value) { snapshot = snapshot.copy(phase = if (value) ReceiverPhase.LISTENING else ReceiverPhase.PAUSED) }
        override var overlayAllowed = false
        override var pin = "012345"
        override val addresses = "Synthetic address"
        var starts = 0
        var pauses = 0
        val previews = mutableListOf<String>()
        val rotationPaused = mutableListOf<Boolean>()
        override fun start() { starts++; running = true }
        override fun pause() { pauses++; running = false }
        override fun preview(source: String) { previews.add(source) }
        override fun rotatePin(): String {
            rotationPaused.add(!running)
            pin = "654321"
            return pin
        }
    }
    @Test fun startRequiresOverlayPermissionAndNeverHappensAtConstruction() {
        val runtime = Runtime()
        val controller = NotificationController(Preferences(), runtime)
        assertEquals(0, runtime.starts)
        assertFalse(controller.start())
        assertEquals(0, runtime.starts)
        runtime.overlayAllowed = true
        assertTrue(controller.start())
        assertEquals(1, runtime.starts)
    }
    @Test fun changingPortPausesWithoutRestartingTheReceiver() {
        val runtime = Runtime().apply { running = true }
        val preferences = Preferences()
        val controller = NotificationController(preferences, runtime)
        controller.changePort(8767)
        assertEquals(8767, preferences.port)
        assertFalse(runtime.running)
        assertEquals(1, runtime.pauses)
        assertEquals(0, runtime.starts)
        try { controller.changePort(80); fail("Privileged port accepted") }
        catch (_: IllegalArgumentException) {}
        assertEquals(8767, preferences.port)
        assertEquals(1, runtime.pauses)
    }
    @Test fun pinRotationPausesBeforeChangingCredentials() {
        val runtime = Runtime().apply { running = true }
        val controller = NotificationController(Preferences(), runtime)
        assertEquals("654321", controller.rotatePin())
        assertEquals(listOf(true), runtime.rotationPaused)
        assertFalse(runtime.running)
        assertEquals(0, runtime.starts)
    }
    @Test fun previewRequiresActiveReceiverOverlayPermissionAndEnabledSource() {
        val runtime = Runtime()
        val controller = NotificationController(Preferences(), runtime)
        assertFalse(controller.preview("alert"))
        runtime.running = true
        assertFalse(controller.preview("alert"))
        runtime.overlayAllowed = true
        controller.toggleSource("alert")
        assertFalse(controller.preview("alert"))
        controller.toggleSource("alert")
        assertTrue(controller.preview("alert"))
        assertEquals(listOf("alert"), runtime.previews)
    }
    @Test fun directAppearanceChoicesValidateBeforeChangingPreferences() {
        val preferences = Preferences()
        val runtime = Runtime()
        val controller = NotificationController(preferences, runtime)
        controller.selectMode("compact"); controller.selectDuration(12)
        assertEquals("compact", preferences.mode)
        assertEquals(12, preferences.duration)
        try { controller.selectMode("unsupported"); fail("Invalid style accepted") }
        catch (_: IllegalArgumentException) {}
        try { controller.selectDuration(31); fail("Invalid duration accepted") }
        catch (_: IllegalArgumentException) {}
        assertEquals("compact", preferences.mode)
        assertEquals(12, preferences.duration)
        assertEquals(0, runtime.starts)
    }
}
