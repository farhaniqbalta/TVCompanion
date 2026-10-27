package id.poncol.tvcompanion.notifications.infrastructure

import id.poncol.tvcompanion.domain.NotificationPreferences

import android.content.Context

/** Non-content preferences; the per-install PIN stays in pairing preferences. */
class ReceiverSettings(context: Context) : NotificationPreferences {
    private val prefs = context.getSharedPreferences("receiver_settings", Context.MODE_PRIVATE)
    override var port: Int
        get() = prefs.getInt("port", 8766)
        set(value) { require(value in 1024..65535); prefs.edit().putInt("port", value).apply() }
    override var duration: Int
        get() = prefs.getInt("duration", 8)
        set(value) { require(value in 3..30); prefs.edit().putInt("duration", value).apply() }
    override var mode: String
        get() = prefs.getString("mode", "privacy") ?: "privacy"
        set(value) { prefs.edit().putString("mode", value).apply() }
    override var reducedMotion: Boolean
        get() = prefs.getBoolean("reduced_motion", false)
        set(value) { prefs.edit().putBoolean("reduced_motion", value).apply() }
    override fun enabled(source: String) = prefs.getBoolean("source_$source", true)
    override fun setEnabled(source: String, enabled: Boolean) { prefs.edit().putBoolean("source_$source", enabled).apply() }
}
