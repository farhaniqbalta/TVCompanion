package id.poncol.tvcompanion.airplay.feature

import android.content.*
import android.os.*
import id.poncol.tvcompanion.airplay.ipc.*
import id.poncol.tvcompanion.domain.airplay.*

/** Screen-scoped private-service adapter; no native dependency or recurring polling. */
internal class AndroidAirPlayControl(context: Context) : AirPlayReceiverControl {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val observers = linkedSetOf<(AirPlaySnapshot) -> Unit>()
    private data class Pending(val reply: (AirPlayResult) -> Unit, val timeout: Runnable)
    private val pending = linkedMapOf<Long, Pending>()
    private var nextId = 0L
    private var remote: IAirPlayReceiver? = null
    private var bound = false
    private var closed = false
    private var callbacks: IAirPlayEvents? = null
    private var epoch = 0L
    private var revision = -1L
    override var snapshot = AirPlaySnapshot()
        private set
    private fun publish(value: AirPlaySnapshot) {
        if (closed) return
        snapshot = value; observers.toList().forEach { it(value) }
    }
    private fun disconnected(issue: AirPlayIssue = AirPlayIssue.UNAVAILABLE) {
        epoch++; remote = null; callbacks = null; revision = -1
        val replies = pending.values.toList(); pending.clear(); main.removeCallbacksAndMessages(null)
        replies.forEach { it.reply(AirPlayResult(false, issue)) }
        publish(AirPlaySnapshot(issue = issue))
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (closed) return
            val token = ++epoch; revision = -1
            val service = IAirPlayReceiver.Stub.asInterface(binder)
            val listener = object : IAirPlayEvents.Stub() {
                override fun onState(version: Int, rev: Long, phase: Int, connection: Int, issue: Int, capabilities: Int, width: Int, height: Int, maxFps: Int) { main.post {
                    if (closed || epoch != token || rev < revision) return@post
                    if (version != AirPlayWire.VERSION) {
                        publish(AirPlaySnapshot(availability = AirPlayAvailability.UNSUPPORTED, issue = AirPlayIssue.UNSUPPORTED)); return@post
                    }
                    val state = AirPlayWire.snapshot(phase, connection, issue, capabilities, width, height, maxFps)
                    if (state == null) { publish(AirPlaySnapshot(availability = AirPlayAvailability.UNSUPPORTED, issue = AirPlayIssue.UNSUPPORTED)); return@post }
                    revision = rev; publish(state)
                } }
                override fun onResult(id: Long, issue: Int) { main.post {
                    if (closed || epoch != token) return@post
                    pending.remove(id)?.let { main.removeCallbacks(it.timeout); it.reply(AirPlayResult(issue == 0, AirPlayWire.issue(issue))) }
                } }
            }
            remote = service; callbacks = listener
            try { service.observe(listener) } catch (_: RemoteException) { disconnected() }
        }
        override fun onServiceDisconnected(name: ComponentName?) { if (!closed) disconnected() }
        override fun onBindingDied(name: ComponentName?) { if (!closed) disconnected() }
        override fun onNullBinding(name: ComponentName?) { if (!closed) disconnected(AirPlayIssue.UNSUPPORTED) }
    }
    init {
        bound = try { this.context.bindService(Intent().setClassName(this.context.packageName, AirPlayWire.SERVICE), connection, Context.BIND_AUTO_CREATE) }
        catch (_: RuntimeException) { false }
        if (!bound) publish(AirPlaySnapshot(issue = AirPlayIssue.UNAVAILABLE))
    }
    private fun command(reply: (AirPlayResult) -> Unit, send: (IAirPlayReceiver, Long, IAirPlayEvents) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) { reply(AirPlayResult(false, AirPlayIssue.CLOSED)); return }
        val service = remote; val listener = callbacks
        if (service == null || listener == null) { reply(AirPlayResult(false, AirPlayIssue.UNAVAILABLE)); return }
        if (pending.size >= 4) { reply(AirPlayResult(false, AirPlayIssue.BUSY)); return }
        val id = ++nextId
        val timeout = Runnable { if (!closed) pending.remove(id)?.reply?.invoke(AirPlayResult(false, AirPlayIssue.TIMEOUT)) }
        pending[id] = Pending(reply, timeout); main.postDelayed(timeout, 5000)
        try { send(service, id, listener) } catch (_: RemoteException) { disconnected() }
    }
    override fun start(options: AirPlayOptions, reply: (AirPlayResult) -> Unit) = command(reply) { service, id, listener -> service.start(id, options.receiverName, listener) }
    override fun stop(reply: (AirPlayResult) -> Unit) = command(reply) { service, id, listener -> service.stop(id, listener) }
    override fun observe(observer: (AirPlaySnapshot) -> Unit): AutoCloseable {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!closed) { observers.add(observer); observer(snapshot) }
        return AutoCloseable { observers.remove(observer) }
    }
    override fun close() {
        if (closed) return
        closed = true; epoch++
        try { callbacks?.let { remote?.removeObserver(it) } } catch (_: RemoteException) { }
        if (bound) { context.unbindService(connection); bound = false }
        remote = null; callbacks = null; observers.clear(); pending.clear(); main.removeCallbacksAndMessages(null)
    }
}
