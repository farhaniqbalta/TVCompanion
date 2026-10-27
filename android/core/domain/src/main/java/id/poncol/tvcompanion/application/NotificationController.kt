package id.poncol.tvcompanion.application

import id.poncol.tvcompanion.domain.*

/** Feature policy independent of Activities, services, JSON, and storage. */
class NotificationController(val preferences: NotificationPreferences, val runtime: NotificationRuntime) {
    fun start(): Boolean {
        if (!runtime.overlayAllowed) return false
        runtime.start()
        return true
    }
    fun pause() = runtime.pause()
    fun changePort(port: Int) {
        require(port in 1024..65535)
        pause()
        preferences.port = port
    }
    fun rotatePin(): String { pause(); return runtime.rotatePin() }
    fun toggleSource(source: String) { preferences.setEnabled(source, !preferences.enabled(source)) }
    fun selectMode(mode: String) {
        require(mode in listOf("expanded", "compact", "privacy"))
        preferences.mode = mode
    }
    fun selectDuration(seconds: Int) {
        require(seconds in 3..30)
        preferences.duration = seconds
    }
    fun cycleMode() {
        val modes = listOf("expanded", "compact", "privacy")
        preferences.mode = modes[(modes.indexOf(preferences.mode) + 1) % modes.size]
    }
    fun cycleDuration() {
        val values = listOf(5, 8, 12, 20, 30)
        preferences.duration = values[(values.indexOf(preferences.duration) + 1) % values.size]
    }
    fun toggleMotion() { preferences.reducedMotion = !preferences.reducedMotion }
    fun preview(source: String): Boolean {
        if (!runtime.running || !runtime.overlayAllowed || !preferences.enabled(source)) return false
        runtime.preview(source)
        return true
    }
}
