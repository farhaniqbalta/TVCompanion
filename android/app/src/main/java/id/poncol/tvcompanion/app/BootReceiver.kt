package id.poncol.tvcompanion.app

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import id.poncol.tvcompanion.airplay.runtime.AirPlayService
import id.poncol.tvcompanion.domain.AutoStartSettings
import id.poncol.tvcompanion.notifications.infrastructure.ReceiverService

/** Restores only the two receivers explicitly opted in by the TV user. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val choices = context.applicationContext as AutoStartSettings
        val notifications = context.getSystemService(NotificationManager::class.java)
        if (!notifications.areNotificationsEnabled()) return
        if (choices.notificationsOnBoot && Settings.canDrawOverlays(context)) {
            try { context.startForegroundService(Intent(context, ReceiverService::class.java)) }
            catch (_: RuntimeException) { /* The user can start it from settings after boot. */ }
        }
        if (choices.airplayOnBoot) {
            try { context.startForegroundService(Intent(context, AirPlayService::class.java).setAction(AirPlayService.ACTION_BOOT_START)) }
            catch (_: RuntimeException) { /* Keep boot work bounded; foreground controls remain available. */ }
        }
    }
}
