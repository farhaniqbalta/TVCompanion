package id.poncol.tvcompanion.airplay.runtime

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.view.Surface
import id.poncol.tvcompanion.airplay.ipc.*
import id.poncol.tvcompanion.domain.airplay.*

/** Bound, foreground-screen-owned host. No native work occurs merely from binding. */
class AirPlayService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val clients = RemoteCallbackList<IAirPlayEvents>()
    private var revision = 0L
    private var surface: Surface? = null
    private var displayObserver: ((AirPlaySnapshot) -> Unit)? = null
    private var destroyed = false
    private lateinit var session: ReceiverSession
    override fun onCreate() {
        super.onCreate()
        session = ReceiverSession((application as ReceiverEngineProvider).receiverCapabilities()) { state -> revision++; publish(state) }
    }
    private fun sendState(client: IAirPlayEvents, state: AirPlaySnapshot) {
        try { client.onState(AirPlayWire.VERSION, revision, AirPlayWire.phase(state.phase), AirPlayWire.connection(state.connection), AirPlayWire.issue(state.issue), (if (state.capabilities.video) 1 else 0) or (if (state.capabilities.audio) 2 else 0), state.capabilities.width ?: 0, state.capabilities.height ?: 0, state.capabilities.maxFps ?: 0) }
        catch (_: RemoteException) { /* RemoteCallbackList removes dead observers. */ }
    }
    private fun publish(state: AirPlaySnapshot) {
        if (destroyed) return
        displayObserver?.invoke(state)
        val count = clients.beginBroadcast()
        try { for (index in 0 until count) sendState(clients.getBroadcastItem(index), state) }
        finally { clients.finishBroadcast() }
        if (surface == null && state.phase in listOf(AirPlayPhase.STOPPED, AirPlayPhase.FAILED)) stopSelf()
    }
    private fun result(client: IAirPlayEvents, id: Long, result: AirPlayResult) {
        try { client.onResult(id, AirPlayWire.issue(result.issue)) } catch (_: RemoteException) { }
    }
    private fun start(name: String): AirPlayResult {
        try { AirPlayOptions(name) } catch (_: IllegalArgumentException) { return AirPlayResult(false, AirPlayIssue.UNSUPPORTED) }
        val target = surface
        return session.start(target?.isValid == true) { emit ->
            val provider = application as? ReceiverEngineProvider ?: error("Receiver engine not composed")
            val engine = provider.createReceiverEngine()
            try {
                engine.start(target!!, name) { state ->
                    main.post {
                        emit(when (state) {
                            ReceiverEngine.State.DISCOVERABLE -> ReceiverSession.Signal.READY
                            ReceiverEngine.State.CONNECTED -> ReceiverSession.Signal.CONNECTED
                            ReceiverEngine.State.VIDEO_OUTPUT -> ReceiverSession.Signal.VIDEO
                            ReceiverEngine.State.DISCONNECTED -> ReceiverSession.Signal.DISCONNECTED
                            ReceiverEngine.State.FAILED -> ReceiverSession.Signal.FAILED
                        })
                    }
                }
            } catch (error: Exception) { engine.close(); throw error }
            object : ReceiverSession.Lease { override fun stop(released: () -> Unit) { engine.stop { main.post(released) } } }
        }
    }
    inner class ReceiverBinder : IAirPlayReceiver.Stub() {
        override fun observe(client: IAirPlayEvents) { main.post {
            if (!destroyed && (clients.registeredCallbackCount < 8 || clients.registeredCallbackCount == 0)) {
                clients.register(client); sendState(client, session.snapshot)
            }
        } }
        override fun removeObserver(client: IAirPlayEvents) { main.post { if (!destroyed) clients.unregister(client) } }
        override fun start(id: Long, name: String, client: IAirPlayEvents) { main.post {
            if (!destroyed) result(client, id, start(name))
        } }
        override fun stop(id: Long, client: IAirPlayEvents) { main.post {
            if (!destroyed) result(client, id, session.stop())
        } }
        // Android-local methods; not declared in AIDL and never callable across processes.
        fun attachDisplay(target: Surface, observer: (AirPlaySnapshot) -> Unit) {
            check(Looper.myLooper() == Looper.getMainLooper())
            surface = target; displayObserver = observer; observer(session.snapshot)
        }
        fun beginPlayback(name: String): AirPlayResult = start(name)
        fun detachDisplay() { displayObserver = null; surface = null; session.stop() }
    }
    private val binder = ReceiverBinder()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onUnbind(intent: Intent?): Boolean {
        if (surface == null && session.snapshot.phase in listOf(AirPlayPhase.STOPPED, AirPlayPhase.FAILED)) stopSelf()
        return false
    }
    override fun onBind(intent: Intent?) = binder
    override fun onDestroy() {
        destroyed = true; surface = null; displayObserver = null
        session.close(); clients.kill(); main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
