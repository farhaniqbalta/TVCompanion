package id.poncol.tvcompanion.airplay.runtime

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.view.Surface
import id.poncol.tvcompanion.airplay.ipc.*
import id.poncol.tvcompanion.airplay.runtime.ambient.AmbientCallback
import id.poncol.tvcompanion.airplay.runtime.ambient.AmbientSource
import id.poncol.tvcompanion.domain.airplay.*
import id.poncol.tvcompanion.domain.AutoStartSettings

/** Owns receiver lifetime. UI bindings and rendering surfaces have independent lifetimes. */
class AirPlayService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val clients = RemoteCallbackList<IAirPlayEvents>()
    private var revision = 0L
    private var surface: Surface? = null
    private var engine: ReceiverEngine? = null
    private var displayObserver: ((AirPlaySnapshot) -> Unit)? = null
    private var destroyed = false
    private var foreground = false
    private val videoPresentation = VideoPresentationPolicy()
    private lateinit var session: ReceiverSession
    override fun onCreate() {
        super.onCreate()
        session = ReceiverSession((application as ReceiverEngineProvider).receiverCapabilities()) { state -> revision++; publish(state) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.airplay_runtime_channel), NotificationManager.IMPORTANCE_LOW))
    }
    private fun sendState(client: IAirPlayEvents, state: AirPlaySnapshot) {
        try { client.onState(AirPlayWire.VERSION, revision, AirPlayWire.phase(state.phase), AirPlayWire.connection(state.connection), AirPlayWire.issue(state.issue),
            (if (state.capabilities.video) 1 else 0) or (if (state.capabilities.audio) 2 else 0) or (if (state.capabilities.appVideo) 4 else 0), state.capabilities.width ?: 0, state.capabilities.height ?: 0, state.capabilities.maxFps ?: 0,
            (if (state.keepAvailable) 1 else 0) or (if (state.audioActive) 2 else 0) or (if (state.keepSession) 4 else 0) or
            (if (state.backgroundAudio) 8 else 0) or (if (state.autoOpenVideo) 16 else 0) or
            (if (state.discoverable) 32 else 0) or (if (state.videoActive) 64 else 0)) }
        catch (_: RemoteException) { }
    }
    private fun publish(state: AirPlaySnapshot) {
        if (destroyed) return
        displayObserver?.invoke(state)
        val count = clients.beginBroadcast()
        try { for (index in 0 until count) sendState(clients.getBroadcastItem(index), state) } finally { clients.finishBroadcast() }
        engine?.setDiscoverable(session.discoverable)
        engine?.setAudioEnabled(session.audioAllowed)
        val hold = session.heldInBackground && state.phase in listOf(AirPlayPhase.STARTING, AirPlayPhase.READY, AirPlayPhase.STOPPING)
        if (hold) {
            try {
                if (!foreground) { startForegroundService(Intent(this, AirPlayService::class.java).setAction(ACTION_HOLD)); foreground = true }
                updateForeground(state)
            } catch (_: RuntimeException) { main.post { if (!destroyed) session.stop() } }
        } else if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false }
        if (state.phase == AirPlayPhase.STOPPED && state.keepAvailable) main.post {
            if (!destroyed && session.snapshot.phase == AirPlayPhase.STOPPED && session.snapshot.keepAvailable) start(getString(R.string.airplay_runtime_receiver_name))
        }
        if (surface == null && !session.heldInBackground && state.phase in listOf(AirPlayPhase.STOPPED, AirPlayPhase.FAILED)) stopSelf()
    }
    private fun updateForeground(state: AirPlaySnapshot) {
        val open = PendingIntent.getActivity(this, 10, Intent(this, AirPlayPlaybackActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 11, Intent(this, AirPlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_airplay_status).setContentTitle(getString(R.string.airplay_runtime_notification_title))
            .setContentText(getString(if (state.connection == AirPlayConnection.NONE) R.string.airplay_runtime_notification_idle else R.string.airplay_runtime_notification_stream))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.airplay_runtime_notification_open), open).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.airplay_runtime_notification_stop), stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                (if (state.audioActive || (surface != null && state.connection == AirPlayConnection.PLAYING)) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
            startForeground(NOTIFICATION, notification, type)
        } else startForeground(NOTIFICATION, notification)
    }
    private fun result(client: IAirPlayEvents, id: Long, value: AirPlayResult) {
        try { client.onResult(id, AirPlayWire.issue(value.issue)) } catch (_: RemoteException) { }
    }
    private fun setKeepAvailable(enabled: Boolean, fromBoot: Boolean = false): AirPlayResult {
        if (enabled) {
            if (session.snapshot.phase == AirPlayPhase.STOPPING) return AirPlayResult(false, AirPlayIssue.BUSY)
            // Notification permission/channel availability is a product requirement. TV firmware may hide the notification; app controls remain available.
            if (!canHoldInBackground(this)) return AirPlayResult(false, AirPlayIssue.UNAVAILABLE)
            try {
                if (!foreground) {
                    if (!fromBoot) startForegroundService(Intent(this, AirPlayService::class.java).setAction(ACTION_HOLD))
                    updateForeground(session.snapshot); foreground = true
                }
            } catch (_: RuntimeException) { return AirPlayResult(false, AirPlayIssue.START_FAILED) }
            session.setKeepAvailable(true)
            return start(getString(R.string.airplay_runtime_receiver_name))
        }
        return session.setKeepAvailable(false)
    }
    /** A startForegroundService caller must see startForeground even when the service then declines to hold. */
    private fun settleForegroundStart(foregroundStart: Boolean) {
        if (foregroundStart && !foreground) try { updateForeground(session.snapshot); stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: RuntimeException) { }
    }
    private fun stopIfIdle() {
        if (surface == null && !session.heldInBackground && session.snapshot.phase in listOf(AirPlayPhase.STOPPED, AirPlayPhase.FAILED)) stopSelf()
    }
    private fun presentIncomingVideo() {
        // A foreground service alone is not a background Activity-launch exemption.
        // Use the existing user-granted overlay permission on the validated Android TV versions.
        // On other/restricted platforms the ongoing notification remains the user-driven fallback.
        val platformAllows = Build.VERSION.SDK_INT < 29 ||
            (Build.VERSION.SDK_INT <= 34 && android.provider.Settings.canDrawOverlays(this))
        if (session.snapshot.phase != AirPlayPhase.READY) return
        if (!videoPresentation.videoRequested(session.snapshot.autoOpenVideo, surface?.isValid == true, platformAllows)) return
        try {
            startActivity(Intent(this, AirPlayPlaybackActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (_: RuntimeException) { /* No retry loop; notification Open remains available. */ }
    }
    private fun start(name: String): AirPlayResult {
        try { AirPlayOptions(name) } catch (_: IllegalArgumentException) { return AirPlayResult(false, AirPlayIssue.UNSUPPORTED) }
        return session.start(surface?.isValid == true) { emit ->
            videoPresentation.disconnected()
            val created = (application as ReceiverEngineProvider).createReceiverEngine(); engine = created
            try {
                created.start(name) { state -> main.post {
                    if (destroyed || engine !== created) return@post
                    emit(when (state) {
                        ReceiverEngine.State.SESSION_REPLACED -> ReceiverSession.Signal.REPLACED
                        ReceiverEngine.State.APP_VIDEO_ENDED -> ReceiverSession.Signal.DISCONNECTED
                        ReceiverEngine.State.APP_VIDEO_FAILED -> ReceiverSession.Signal.APP_VIDEO_FAILED
                        ReceiverEngine.State.APP_VIDEO_REQUESTED -> ReceiverSession.Signal.APP_VIDEO
                        ReceiverEngine.State.MEDIA_UNSUPPORTED -> ReceiverSession.Signal.MEDIA_UNSUPPORTED
                        ReceiverEngine.State.PROTECTED_CONTENT -> ReceiverSession.Signal.PROTECTED_CONTENT
                        ReceiverEngine.State.DISCOVERABLE -> ReceiverSession.Signal.READY
                        ReceiverEngine.State.CONNECTED, ReceiverEngine.State.SESSION_CONNECTED -> ReceiverSession.Signal.CONNECTED
                        ReceiverEngine.State.VIDEO_OUTPUT -> ReceiverSession.Signal.VIDEO
                        ReceiverEngine.State.AUDIO_PLAYING -> ReceiverSession.Signal.AUDIO
                        ReceiverEngine.State.AUDIO_STOPPED -> ReceiverSession.Signal.AUDIO_STOPPED
                        ReceiverEngine.State.AUDIO_UNAVAILABLE -> ReceiverSession.Signal.AUDIO_UNAVAILABLE
                        ReceiverEngine.State.DISPLAY_REQUIRED -> ReceiverSession.Signal.DISPLAY_REQUIRED
                        ReceiverEngine.State.DISCONNECTED -> ReceiverSession.Signal.DISCONNECTED
                        ReceiverEngine.State.FAILED -> ReceiverSession.Signal.FAILED
                    })
                    when (state) {
                        ReceiverEngine.State.CONNECTED, ReceiverEngine.State.DISPLAY_REQUIRED, ReceiverEngine.State.APP_VIDEO_REQUESTED -> presentIncomingVideo()
                        ReceiverEngine.State.SESSION_REPLACED -> videoPresentation.disconnected()
                        ReceiverEngine.State.DISCONNECTED, ReceiverEngine.State.APP_VIDEO_ENDED -> videoPresentation.disconnected()
                        else -> Unit
                    }
                } }
                created.setAudioEnabled(session.audioAllowed)
                created.setDiscoverable(session.discoverable)
                created.attachSurface(surface)
            } catch (failure: Exception) { engine = null; created.close(); throw failure }
            object : ReceiverSession.Lease { override fun stop(released: () -> Unit) {
                created.stop { main.post { if (engine === created) engine = null; released() } }
            } }
        }
    }
    inner class ReceiverBinder : IAirPlayReceiver.Stub() {
        override fun observe(client: IAirPlayEvents) { main.post {
            if (!destroyed && clients.registeredCallbackCount < 8) { clients.register(client); sendState(client, session.snapshot) }
        } }
        override fun removeObserver(client: IAirPlayEvents) { main.post { if (!destroyed) clients.unregister(client) } }
        override fun start(id: Long, name: String, client: IAirPlayEvents) { main.post { if (!destroyed) result(client, id, start(name)) } }
        override fun setKeepAvailable(id: Long, enabled: Boolean, client: IAirPlayEvents) { main.post { if (!destroyed) result(client, id, setKeepAvailable(enabled)) } }
        override fun setBackgroundOptions(id: Long, keepSession: Boolean, backgroundAudio: Boolean, autoOpenVideo: Boolean, client: IAirPlayEvents) { main.post { if (!destroyed) result(client, id, session.setBackgroundOptions(keepSession, backgroundAudio, autoOpenVideo)) } }
        override fun stop(id: Long, client: IAirPlayEvents) { main.post { if (!destroyed) result(client, id, session.stop()) } }
        // Local methods are deliberately absent from AIDL; a Surface never crosses processes.
        fun attachDisplay(target: Surface, observer: (AirPlaySnapshot) -> Unit) {
            check(Looper.myLooper() == Looper.getMainLooper())
            val changed = surface !== target
            surface = target; displayObserver = observer; session.attachDisplay(); if (changed) engine?.attachSurface(target); observer(session.snapshot)
        }
        fun beginPlayback(name: String): AirPlayResult = start(name)
        fun stopPlayback(): AirPlayResult = session.stop()
        /** Main thread. The callback always runs on main, with null when no receiver can serve it. */
        fun openAmbient(callback: AmbientCallback) {
            (engine as? AmbientSource)?.openAmbient(callback) ?: main.post { callback.onOpened(null) }
        }
        fun detachDisplay() { displayObserver = null; surface = null; engine?.attachSurface(null); session.detachDisplay() }
    }
    private val binder = ReceiverBinder()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) session.stop()
        else if (intent?.action == ACTION_BOOT_START) {
            if ((application as AutoStartSettings).airplayOnBoot) {
                val started = setKeepAvailable(true, fromBoot = true)
                if (started.accepted) session.setBackgroundOptions(true, true, true) else { settleForegroundStart(true); stopSelf() }
            } else { settleForegroundStart(true); stopSelf() }
        }
        // Background listening default; the main process sends this once per process so a user Off is kept.
        // Failure (e.g. notifications blocked) leaves listening Off silently; the settings screen shows the hint.
        else if (intent?.action == ACTION_APP_START) {
            if (!setKeepAvailable(true, fromBoot = true).accepted) { settleForegroundStart(intent.getBooleanExtra(EXTRA_FOREGROUND_START, false)); stopIfIdle() }
        }
        else if (intent?.action == ACTION_HOLD && session.heldInBackground) updateForeground(session.snapshot)
        return START_NOT_STICKY
    }
    override fun onUnbind(intent: Intent?): Boolean { stopIfIdle(); return false }
    override fun onBind(intent: Intent?) = binder
    override fun onDestroy() {
        destroyed = true; surface = null; displayObserver = null
        session.close(); clients.kill(); main.removeCallbacksAndMessages(null)
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        const val ACTION_BOOT_START = "id.poncol.tvcompanion.airplay.BOOT_START"
        /** Started by the app (launcher open or package update): applies the Background listening default. */
        const val ACTION_APP_START = "id.poncol.tvcompanion.airplay.APP_START"
        /** Set by callers that used startForegroundService, so a declined start still satisfies startForeground. */
        const val EXTRA_FOREGROUND_START = "id.poncol.tvcompanion.airplay.FOREGROUND_START"
        /** The gate Background listening applies; check it before startForegroundService so the service can always hold. */
        fun canHoldInBackground(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java)
            return foregroundAllowed(manager.areNotificationsEnabled(), manager.getNotificationChannel(CHANNEL)?.importance)
        }
        /** A missing channel is fine: the service creates it before posting. */
        internal fun foregroundAllowed(notificationsEnabled: Boolean, channelImportance: Int?) =
            notificationsEnabled && channelImportance != NotificationManager.IMPORTANCE_NONE
        private const val CHANNEL = "airplay_receiver"
        private const val NOTIFICATION = 200
        private const val ACTION_STOP = "id.poncol.tvcompanion.airplay.STOP"
        private const val ACTION_HOLD = "id.poncol.tvcompanion.airplay.HOLD"
    }
}
