package id.poncol.tvcompanion.notifications.infrastructure

import android.app.*
import android.content.Intent
import android.os.*
import android.provider.Settings
import id.poncol.tvcompanion.notifications.presentation.AndroidNotificationOverlay
import id.poncol.tvcompanion.notifications.presentation.NotificationSettingsActivity
import id.poncol.tvcompanion.notifications.presentation.NotificationThemes

/** Android foreground-service edge. A single testable session owns listener/queue/presentation. */
class ReceiverService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: ReceiverSettings
    private lateinit var session: ReceiverSession
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        settings = ReceiverSettings(this)
        session = ReceiverSession(
            settings, { Settings.canDrawOverlays(this) },
            { receive, failed -> LocalServer(pairingPin(this), receive, failed, settings.port) },
            AndroidNotificationOverlay(this, settings),
            { task -> main.post { task() }; Unit },
            { snapshot -> ReceiverStatusStore.publish(snapshot); notifyState() },
            { stopSelf() },
            lastAcceptedAt = ReceiverStatusStore.snapshot.lastAcceptedAt
        )
        try {
            startForegroundNotification()
            if (!session.start()) stopSelf()
        } catch (_: Exception) { session.failStart(); stopSelf() }
    }
    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("receiver", "Local receiver", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, NotificationSettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ReceiverService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "receiver")
            .setSmallIcon(id.poncol.tvcompanion.designsystem.R.drawable.ic_companion_notification)
            .setContentTitle("TVCompanion is listening").setContentText("Local notifications · port ${settings.port}")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Pause", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "stop" -> { session.stop(); stopSelf() }
            "test" -> {
                val source = intent.getStringExtra("source")?.takeIf { it in NotificationThemes.all } ?: "alert"
                session.preview(NotificationThemes.demo(source, settings.duration))
            }
        }
        return START_NOT_STICKY
    }
    private fun notifyState() { sendBroadcast(Intent("$packageName.STATE_CHANGED").setPackage(packageName)) }
    override fun onDestroy() {
        if (::session.isInitialized) session.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
