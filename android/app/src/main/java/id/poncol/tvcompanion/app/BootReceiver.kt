package id.poncol.tvcompanion.app

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import id.poncol.tvcompanion.airplay.runtime.AirPlayService
import id.poncol.tvcompanion.airplay.runtime.OnceGate
import id.poncol.tvcompanion.domain.AutoStartSettings
import id.poncol.tvcompanion.notifications.infrastructure.ReceiverService

/** Boot restores only the receivers opted in by the TV user; an app update restarts both defaults. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            startDefaults(context) { context.startForegroundService(it) }
            return
        }
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

    companion object {
        private val defaults = OnceGate()
        /** Starts the notification receiver and AirPlay Background listening defaults once per process, with the boot path's
         *  permission gates, so a receiver the user stops stays stopped until the app process restarts. */
        fun startDefaults(context: Context, startAirPlay: (Intent) -> Unit) {
            if (!context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() || !defaults.claim()) return
            if (Settings.canDrawOverlays(context)) {
                try { context.startForegroundService(Intent(context, ReceiverService::class.java)) }
                catch (_: RuntimeException) { /* The settings Start button remains available. */ }
            }
            try { startAirPlay(Intent(context, AirPlayService::class.java).setAction(AirPlayService.ACTION_APP_START)) }
            catch (_: RuntimeException) { /* The AirPlay screen remains available. */ }
        }
    }
}
