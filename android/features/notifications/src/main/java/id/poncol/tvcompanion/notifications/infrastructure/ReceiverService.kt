package id.poncol.tvcompanion.notifications.infrastructure

import id.poncol.tvcompanion.domain.NotificationEvent
import id.poncol.tvcompanion.domain.EventQueue
import id.poncol.tvcompanion.notifications.R
import id.poncol.tvcompanion.notifications.presentation.NotificationSettingsActivity
import id.poncol.tvcompanion.notifications.presentation.NotificationCard
import id.poncol.tvcompanion.notifications.presentation.NotificationThemes

import android.animation.ValueAnimator
import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.view.*
import kotlin.math.min

class ReceiverService : Service() {
    companion object {
        @Volatile var running = false
        @Volatile var state = "Paused"
        @Volatile var lastReceipt = "No network test received this session"
    }
    private val main = Handler(Looper.getMainLooper())
    private val queue = EventQueue()
    private lateinit var settings: ReceiverSettings
    private var server: LocalServer? = null
    private var overlay: View? = null
    private val hide = Runnable { dismiss() }
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        settings = ReceiverSettings(this)
        val gate = EventGate(duration = { settings.duration }, enabled = { settings.enabled(it) }, submit = { event ->
            queue.offer(event).also { accepted -> if (accepted) main.post { presentNext() } }
        })
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("receiver", "Local receiver", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, NotificationSettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ReceiverService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "receiver").setSmallIcon(id.poncol.tvcompanion.designsystem.R.drawable.ic_companion_notification)
            .setContentTitle("TVCompanion is listening").setContentText("Local notifications · port ${settings.port}")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Pause", stop).build()).build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(1, notification)
            check(Settings.canDrawOverlays(this))
            server = LocalServer(pairingPin(this), { raw ->
                if (!Settings.canDrawOverlays(this)) EventGate.Result(503, "overlay_permission_needed")
                else gate.accept(raw).also { if (it.code == 202) { lastReceipt = "Network event accepted at ${java.text.DateFormat.getTimeInstance().format(java.util.Date())}"; notifyState() } }
            }, { main.post { state = "Listener failed; restart required"; stopSelf() } }, settings.port).also { it.start() }
            running = true; state = "Listening on port ${settings.port}"; notifyState()
        } catch (_: Exception) { state = "Start failed; check overlay permission or port ${settings.port}"; stopSelf() }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "stop" -> stopSelf()
            "test" -> if (running) {
                val source = intent.getStringExtra("source")?.takeIf { it in NotificationThemes.all } ?: "alert"
                if (settings.enabled(source)) queue.offer(NotificationThemes.demo(source, settings.duration))
                presentNext()
            }
        }
        return START_NOT_STICKY
    }
    private fun notifyState() { sendBroadcast(Intent("$packageName.STATE_CHANGED").setPackage(packageName)) }
    private fun motion() = !settings.reducedMotion && ValueAnimator.areAnimatorsEnabled()
    private fun presentNext() {
        if (overlay != null || !running) return
        var event = queue.next()
        while (event != null && !settings.enabled(event.source)) event = queue.next()
        event ?: return
        if (!Settings.canDrawOverlays(this)) { state = "Overlay permission needed; preview available in settings"; notifyState(); queue.clear(); return }
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val metrics = resources.displayMetrics
        val width = min(dp(280), metrics.widthPixels - dp(96)).coerceAtLeast(dp(200))
        val card = NotificationCard(this, event, settings.mode)
        val params = WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.END; x = dp(48); y = dp(48)
        }
        try {
            getSystemService(WindowManager::class.java).addView(card, params)
            overlay = card
            if (motion()) {
                card.alpha = 0f; card.translationY = -dp(12).toFloat()
                card.animate().alpha(1f).translationY(0f).setDuration(180).start()
            }
            main.postDelayed(hide, min(event.durationSeconds * 1000L, (event.expiresAt - System.currentTimeMillis()).coerceAtLeast(1)))
        } catch (_: Exception) {
            state = "Overlay unavailable; use the local preview in settings"; notifyState()
            queue.clear()
        }
    }
    private fun dismiss() {
        val card = overlay ?: return
        if (motion()) card.animate().alpha(0f).setDuration(180).withEndAction { removeOverlay(); presentNext() }.start()
        else { removeOverlay(); presentNext() }
    }
    private fun removeOverlay() {
        main.removeCallbacks(hide)
        overlay?.let {
            it.animate().setListener(null); it.animate().cancel()
            try { getSystemService(WindowManager::class.java).removeView(it) } catch (_: Exception) {}
        }
        overlay = null
    }
    override fun onDestroy() {
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        server?.close(); server = null
        queue.clear(); removeOverlay(); main.removeCallbacksAndMessages(null)
        if (state.startsWith("Listening")) state = "Paused"
        notifyState()
        super.onDestroy()
    }
}
