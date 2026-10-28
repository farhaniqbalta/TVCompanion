package id.poncol.tvcompanion.domain

interface NotificationPreferences {
    var port: Int
    var duration: Int
    var mode: String
    var reducedMotion: Boolean
    fun enabled(source: String): Boolean
    fun setEnabled(source: String, enabled: Boolean)
}
interface NotificationRuntime {
    val snapshot: ReceiverSnapshot
    val running: Boolean get() = snapshot.running
    val overlayAllowed: Boolean
    val pin: String
    val addresses: String
    fun start()
    fun pause()
    fun preview(source: String)
    fun rotatePin(): String
}
