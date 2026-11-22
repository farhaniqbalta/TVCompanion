package id.poncol.tvcompanion.app.control

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import id.poncol.tvcompanion.app.TVCompanionApplication

/** Process-local pairing state shared by ControlService and ControlPairingActivity. Main thread only. */
object ControlPairing {
    // Read by connection threads.
    @Volatile var window: PairingWindow? = null
        internal set
    @Volatile var pairedAt = 0L
        internal set
    @Volatile var failed = false
        internal set
    private val listeners = linkedSetOf<() -> Unit>()
    fun listen(listener: () -> Unit): AutoCloseable { listeners.add(listener); listener(); return AutoCloseable { listeners.remove(listener) } }
    internal fun changed() = listeners.toList().forEach { it() }
}

/** Runs the iPhone control listener only while an iPhone is paired or a pairing window is open. */
class ControlService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var server: ControlServer? = null
    private var features: AndroidControlFeatures? = null
    private lateinit var registry: ClientRegistry

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        registry = registry(this)
        startForegroundNotification()
        try {
            val (ssl, serverKey) = ControlKeys.context()
            val created = AndroidControlFeatures(this, (application as TVCompanionApplication).light)
            features = created
            server = ControlServer(ssl, serverKey, registry, ControlDispatcher(created),
                pairing = { ControlPairing.window },
                onPaired = { main.post { ControlPairing.window = null; ControlPairing.pairedAt = SystemClock.elapsedRealtime(); ControlPairing.changed() } }
            ).also { it.start() }
            ControlPairing.failed = false
        } catch (_: Exception) {
            ControlPairing.failed = true
            ControlPairing.changed()
            stopSelf()
        }
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "iPhone controls", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, ControlPairingActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(id.poncol.tvcompanion.designsystem.R.drawable.ic_companion_notification)
            .setContentTitle("iPhone controls ready").setContentText("Paired iPhones can control TVCompanion features")
            .setContentIntent(open).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAIR -> if (server != null) {
                ControlPairing.window = PairingWindow({ SystemClock.elapsedRealtime() })
                ControlPairing.changed()
                main.postDelayed({ ControlPairing.changed(); stopIfIdle() }, PairingWindow.LIFETIME_MS + 500)
            }
            ACTION_FORGET -> { registry.clear(); ControlPairing.window = null; ControlPairing.changed(); stopIfIdle() }
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    private fun stopIfIdle() { if (registry.isEmpty && ControlPairing.window?.open != true) stopSelf() }

    override fun onDestroy() {
        server?.close(); server = null
        features?.close(); features = null
        main.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_PAIR = "id.poncol.tvcompanion.controls.PAIR"
        const val ACTION_FORGET = "id.poncol.tvcompanion.controls.FORGET"
        private const val CHANNEL = "controls"
        private const val NOTIFICATION_ID = 7380

        fun registry(context: Context): ClientRegistry {
            val prefs = context.applicationContext.getSharedPreferences("controls", Context.MODE_PRIVATE)
            // One ordered string so "oldest first" survives storage.
            return ClientRegistry(
                load = { prefs.getString("clients", "").orEmpty().split(',').filter { it.isNotEmpty() }.toCollection(LinkedHashSet()) },
                save = { prefs.edit().putString("clients", it.joinToString(",")).commit() })
        }
        fun hasPairedClients(context: Context) = !registry(context).isEmpty
    }
}
