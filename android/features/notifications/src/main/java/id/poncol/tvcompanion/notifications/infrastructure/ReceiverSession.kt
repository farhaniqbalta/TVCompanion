package id.poncol.tvcompanion.notifications.infrastructure

import id.poncol.tvcompanion.domain.*

internal interface NotificationListener : AutoCloseable {
    fun start()
    override fun close()
}
internal interface NotificationOverlay {
    fun show(event: NotificationEvent, dismissed: () -> Unit)
    fun clear()
}

/** Single-session owner. Lifecycle/presentation methods run on the supplied event thread.
 * receive() is called by the listener worker; admission and stop are synchronized.
 * No Android types, threads, scheduled polling, or generic capability infrastructure.
 */
internal class ReceiverSession(
    private val settings: NotificationPreferences,
    private val permission: () -> Boolean,
    private val createListener: ((String) -> EventGate.Result, () -> Unit) -> NotificationListener,
    private val overlay: NotificationOverlay,
    private val dispatch: (() -> Unit) -> Unit,
    private val changed: (ReceiverSnapshot) -> Unit,
    private val fatal: () -> Unit = {},
    private val now: () -> Long = { System.currentTimeMillis() },
    lastAcceptedAt: Long? = null
) {
    @Volatile var snapshot = ReceiverSnapshot(port = settings.port, lastAcceptedAt = lastAcceptedAt)
        private set
    private val lock = Any()
    private val queue = EventQueue(now)
    private var accepting = false
    private var started = false
    @Volatile private var stopped = false
    private var listener: NotificationListener? = null
    private var visible = false
    private var displayToken = 0L
    private val gate = EventGate(now, { settings.duration }, { settings.enabled(it) }, { event ->
        synchronized(lock) { accepting && queue.offer(event) }
    })
    private fun publish(next: ReceiverSnapshot) { snapshot = next; changed(next) }
    fun start(): Boolean {
        if (started || stopped) return snapshot.running
        started = true
        publish(snapshot.copy(phase = ReceiverPhase.STARTING, issue = null))
        if (!permission()) { fail(ReceiverIssue.OVERLAY_PERMISSION); return false }
        try {
            // Assign before starting so bind/start failure also closes the allocated listener.
            listener = createListener(::receive) {
                dispatch { if (!stopped) fail(ReceiverIssue.LISTENER_FAILED) }
            }
            listener!!.start()
            if (stopped) return false
            synchronized(lock) { accepting = true }
            publish(snapshot.copy(phase = ReceiverPhase.LISTENING))
            return true
        } catch (_: Exception) { fail(ReceiverIssue.START_FAILED); return false }
    }
    private fun receive(raw: String): EventGate.Result {
        if (!snapshot.running || stopped) return EventGate.Result(503, "receiver_paused")
        if (!permission()) return EventGate.Result(503, "overlay_permission_needed")
        val result = gate.accept(raw)
        if (result.code == 202) {
            val acceptedAt = now()
            dispatch {
                if (!stopped && snapshot.running) {
                    publish(snapshot.copy(lastAcceptedAt = acceptedAt))
                    presentNext()
                }
            }
        }
        return result
    }
    fun preview(event: NotificationEvent): Boolean {
        if (stopped || !snapshot.running || !settings.enabled(event.source)) return false
        val accepted = synchronized(lock) { accepting && queue.offer(event) }
        if (accepted) presentNext()
        return accepted
    }
    private fun presentNext() {
        if (stopped || !snapshot.running || visible) return
        var event = queue.next()
        while (event != null && !settings.enabled(event.source)) event = queue.next()
        event ?: return
        if (!permission()) {
            queue.clear(); publish(snapshot.copy(issue = ReceiverIssue.OVERLAY_PERMISSION)); return
        }
        val token = ++displayToken
        visible = true
        try {
            overlay.show(event) {
                dispatch {
                    if (!stopped && token == displayToken && visible) {
                        visible = false; presentNext()
                    }
                }
            }
            if (snapshot.issue != null) publish(snapshot.copy(issue = null))
        } catch (_: Exception) {
            visible = false; ++displayToken; queue.clear()
            try { overlay.clear() } catch (_: Exception) {}
            publish(snapshot.copy(issue = ReceiverIssue.OVERLAY_FAILED))
        }
    }
    fun failStart() { if (!stopped) fail(ReceiverIssue.START_FAILED) }
    private fun fail(issue: ReceiverIssue) {
        publish(snapshot.copy(phase = ReceiverPhase.FAILED, issue = issue))
        stop(); fatal()
    }
    fun stop() {
        if (stopped) return
        stopped = true
        val failed = snapshot.phase == ReceiverPhase.FAILED
        if (!failed) publish(snapshot.copy(phase = ReceiverPhase.STOPPING))
        synchronized(lock) { accepting = false; queue.clear() }
        ++displayToken; visible = false
        val current = listener; listener = null
        try { current?.close() } catch (_: Exception) {}
        try { overlay.clear() } catch (_: Exception) {}
        if (!failed) publish(snapshot.copy(phase = ReceiverPhase.PAUSED, issue = null))
    }
}
