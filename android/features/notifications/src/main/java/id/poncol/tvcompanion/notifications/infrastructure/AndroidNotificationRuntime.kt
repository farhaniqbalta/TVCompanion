package id.poncol.tvcompanion.notifications.infrastructure

import android.content.Context
import android.content.Intent
import android.provider.Settings
import id.poncol.tvcompanion.domain.NotificationRuntime
import java.net.NetworkInterface

class AndroidNotificationRuntime(context: Context, private val settings: ReceiverSettings) : NotificationRuntime {
    private val context = context.applicationContext
    override val running get() = ReceiverService.running
    override val state get() = ReceiverService.state
    override val lastReceipt get() = ReceiverService.lastReceipt
    override val overlayAllowed get() = Settings.canDrawOverlays(context)
    override val pin get() = pairingPin(context)
    override val addresses: String get() = try {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .filter { it is java.net.Inet4Address && it.isSiteLocalAddress }
            .joinToString("\n") { "http://${it.hostAddress}:${settings.port}" }
            .ifBlank { "Connect the TV to Wi-Fi, then reopen this screen." }
    } catch (_: Exception) { "Local address unavailable" }
    override fun start() { context.startForegroundService(Intent(context, ReceiverService::class.java)) }
    override fun pause() { context.stopService(Intent(context, ReceiverService::class.java)) }
    override fun preview(source: String) {
        context.startService(Intent(context, ReceiverService::class.java).setAction("test").putExtra("source", source))
    }
    override fun rotatePin(): String {
        context.getSharedPreferences("pairing", Context.MODE_PRIVATE).edit().remove("token").apply()
        return pin
    }
}
