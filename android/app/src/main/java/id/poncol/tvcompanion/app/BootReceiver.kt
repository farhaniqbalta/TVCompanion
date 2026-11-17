package id.poncol.tvcompanion.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import id.poncol.tvcompanion.airplay.runtime.AirPlayService
import id.poncol.tvcompanion.airplay.runtime.OnceGate
import id.poncol.tvcompanion.domain.AutoStartSettings
import id.poncol.tvcompanion.notifications.infrastructure.ReceiverService

/** Boot restores only the receivers opted in by the TV user; an app update restarts both defaults. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            startDefaults(context, foregroundStart = true)
            return
        }
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val choices = context.applicationContext as AutoStartSettings
        if (choices.notificationsOnBoot && notificationsDefault.claimIf(ReceiverService.canStart(context))) {
            try { context.startForegroundService(Intent(context, ReceiverService::class.java)) }
            catch (_: RuntimeException) { /* The user can start it from settings after boot. */ }
        }
        if (choices.airplayOnBoot && airplayDefault.claimIf(AirPlayService.canHoldInBackground(context))) {
            try { context.startForegroundService(Intent(context, AirPlayService::class.java).setAction(AirPlayService.ACTION_BOOT_START)) }
            catch (_: RuntimeException) { /* Keep boot work bounded; foreground controls remain available. */ }
        }
    }

    companion object {
        // Main-process gates, also claimed by boot: a receiver the user stops stays stopped until the app process restarts.
        private val notificationsDefault = OnceGate()
        private val airplayDefault = OnceGate()
        /** Starts each receiver default once per process, claiming its gate only when it passes its permission check
         *  (only start what can go foreground: a declined startForegroundService start would crash the app). */
        fun startDefaults(context: Context, foregroundStart: Boolean) {
            if (notificationsDefault.claimIf(ReceiverService.canStart(context))) {
                try { context.startForegroundService(Intent(context, ReceiverService::class.java)) }
                catch (_: RuntimeException) { /* The settings Start button remains available. */ }
            }
            if (airplayDefault.claimIf(AirPlayService.canHoldInBackground(context))) try {
                val intent = Intent(context, AirPlayService::class.java).setAction(AirPlayService.ACTION_APP_START)
                if (foregroundStart) context.startForegroundService(intent.putExtra(AirPlayService.EXTRA_FOREGROUND_START, true))
                else context.startService(intent)
            } catch (_: RuntimeException) { /* The AirPlay screen remains available. */ }
        }
    }
}
