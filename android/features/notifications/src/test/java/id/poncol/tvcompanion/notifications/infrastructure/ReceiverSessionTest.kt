package id.poncol.tvcompanion.notifications.infrastructure

import id.poncol.tvcompanion.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket

class ReceiverSessionTest {
    private class Preferences : NotificationPreferences {
        override var port = 8766
        override var duration = 8
        override var mode = "privacy"
        override var reducedMotion = false
        private val muted = mutableSetOf<String>()
        override fun enabled(source: String) = source !in muted
        override fun setEnabled(source: String, enabled: Boolean) { if (enabled) muted.remove(source) else muted.add(source) }
    }
    private class Listener : NotificationListener {
        var starts = 0; var closes = 0; var failStart = false
        override fun start() { starts++; if (failStart) error("Synthetic bind failure") }
        override fun close() { closes++ }
    }
    private class Overlay : NotificationOverlay {
        val shown = mutableListOf<NotificationEvent>()
        val dismissals = mutableListOf<() -> Unit>()
        var clears = 0; var failShow = false
        override fun show(event: NotificationEvent, dismissed: () -> Unit) {
            if (failShow) error("Synthetic overlay failure")
            shown.add(event); dismissals.add(dismissed)
        }
        override fun clear() { clears++ }
    }
    private class Fixture {
        val settings = Preferences()
        var permission = true
        var time = 1_800_000_000_000L
        val tasks = ArrayDeque<() -> Unit>()
        val snapshots = mutableListOf<ReceiverSnapshot>()
        val listener = Listener()
        val overlay = Overlay()
        var factories = 0; var fatals = 0
        lateinit var receive: (String) -> EventGate.Result
        lateinit var listenerFailed: () -> Unit
        val session = ReceiverSession(settings, { permission }, { receive, failure ->
            factories++; this.receive = receive; listenerFailed = failure; listener
        }, overlay, { tasks.addLast(it) }, { snapshots.add(it) }, { fatals++ }, { time })
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().invoke() }
        fun event(id: String, source: String = "alert", expires: Long = time + 120_000) =
            NotificationEvent(id, source, "Synthetic title", "Synthetic body", time, expires, 8)
        fun raw(id: String, source: String = "alert") = JSONObject().put("id", id).put("source", source).toString()
    }
    @Test fun startsOnlyAfterPermissionAndSuccessfulBindWithTypedTransitions() {
        val f = Fixture()
        assertEquals(0, f.factories)
        assertTrue(f.session.start())
        assertEquals(listOf(ReceiverPhase.STARTING, ReceiverPhase.LISTENING), f.snapshots.map { it.phase })
        assertTrue(f.session.snapshot.running)
        assertTrue(f.session.start())
        assertEquals(1, f.factories)
        f.session.stop()
        assertEquals(listOf(ReceiverPhase.STOPPING, ReceiverPhase.PAUSED), f.snapshots.takeLast(2).map { it.phase })
        assertFalse(f.session.snapshot.running)
        f.session.stop()
        assertEquals(1, f.listener.closes)
    }
    @Test fun deniedPermissionDoesNotAllocateAListener() {
        val f = Fixture(); f.permission = false
        assertFalse(f.session.start())
        assertEquals(0, f.factories)
        assertEquals(ReceiverPhase.FAILED, f.session.snapshot.phase)
        assertEquals(ReceiverIssue.OVERLAY_PERMISSION, f.session.snapshot.issue)
        assertEquals(1, f.fatals)
    }
    @Test fun bindFailureClosesTheAllocatedListenerAndPreservesFailureAfterStop() {
        val f = Fixture(); f.listener.failStart = true
        assertFalse(f.session.start())
        assertEquals(1, f.listener.closes)
        assertEquals(ReceiverIssue.START_FAILED, f.session.snapshot.issue)
        f.session.stop()
        assertEquals(ReceiverPhase.FAILED, f.session.snapshot.phase)
        assertEquals(1, f.listener.closes)
    }
    @Test fun platformStartupFailureCanBeReportedBeforeListenerAllocation() {
        val f = Fixture(); f.session.failStart()
        assertEquals(0, f.factories)
        assertEquals(ReceiverIssue.START_FAILED, f.session.snapshot.issue)
        assertFalse(f.session.start())
    }
    @Test fun listenerFailureStopsResourcesAndCannotBeOverwrittenByLateCallbacks() {
        val f = Fixture(); f.session.start()
        f.session.preview(f.event("visible"))
        val oldDismissal = f.overlay.dismissals.single()
        f.listenerFailed(); f.drain()
        assertEquals(ReceiverIssue.LISTENER_FAILED, f.session.snapshot.issue)
        assertFalse(f.session.snapshot.running)
        assertEquals(1, f.listener.closes)
        oldDismissal(); f.listenerFailed(); f.drain()
        assertEquals(1, f.overlay.shown.size)
        assertEquals(1, f.fatals)
    }
    @Test fun stopDiscardsQueuedAndAlreadyDispatchedEventsWithoutNewOverlays() {
        val f = Fixture(); f.session.start()
        assertEquals(202, f.receive(f.raw("pending-main-thread")).code)
        f.session.stop(); f.drain()
        assertTrue(f.overlay.shown.isEmpty())
        assertNull(f.session.snapshot.lastAcceptedAt)
        assertEquals(503, f.receive(f.raw("after-stop")).code)
    }
    @Test fun overlaysRemainOneAtATimeAndQueueKeepsOrder() {
        val f = Fixture(); f.session.start()
        f.session.preview(f.event("first")); f.session.preview(f.event("second")); f.session.preview(f.event("third"))
        assertEquals(listOf("first"), f.overlay.shown.map { it.id })
        f.overlay.dismissals[0](); f.drain()
        assertEquals(listOf("first", "second"), f.overlay.shown.map { it.id })
        f.overlay.dismissals[1](); f.drain()
        assertEquals(listOf("first", "second", "third"), f.overlay.shown.map { it.id })
        f.session.stop()
    }
    @Test fun anOldDismissalCannotDismissTheFollowingCard() {
        val f = Fixture(); f.session.start()
        repeat(3) { f.session.preview(f.event("$it")) }
        val first = f.overlay.dismissals.first()
        first(); f.drain(); first(); f.drain()
        assertEquals(listOf("0", "1"), f.overlay.shown.map { it.id })
        f.session.stop()
    }
    @Test fun expiredAndNewlyMutedWaitingEventsAreSkipped() {
        val f = Fixture(); f.session.start()
        f.session.preview(f.event("visible"))
        f.session.preview(f.event("expired", expires = f.time + 10))
        f.session.preview(f.event("muted", "grab"))
        f.session.preview(f.event("next"))
        f.time += 11; f.settings.setEnabled("grab", false)
        f.overlay.dismissals.first()(); f.drain()
        assertEquals(listOf("visible", "next"), f.overlay.shown.map { it.id })
        f.session.stop()
    }
    @Test fun overlayFailureClearsPendingContentButKeepsListenerAvailable() {
        val f = Fixture(); f.session.start(); f.overlay.failShow = true
        assertEquals(202, f.receive(f.raw("failed-show")).code); f.drain()
        assertEquals(ReceiverIssue.OVERLAY_FAILED, f.session.snapshot.issue)
        assertTrue(f.session.snapshot.running)
        assertEquals(0, f.listener.closes)
        f.overlay.failShow = false
        assertEquals(202, f.receive(f.raw("next")).code); f.drain()
        assertNull(f.session.snapshot.issue)
        assertEquals(listOf("next"), f.overlay.shown.map { it.id })
        f.session.stop()
    }
    @Test fun permissionLossRejectsNetworkInputAndDropsPendingPresentation() {
        val f = Fixture(); f.session.start()
        f.permission = false
        assertEquals("overlay_permission_needed", f.receive(f.raw("denied")).status)
        f.session.preview(f.event("preview"))
        assertEquals(ReceiverIssue.OVERLAY_PERMISSION, f.session.snapshot.issue)
        assertTrue(f.overlay.shown.isEmpty())
        f.permission = true
        f.session.preview(f.event("allowed"))
        assertNull(f.session.snapshot.issue)
        assertEquals(listOf("allowed"), f.overlay.shown.map { it.id })
        f.session.stop()
    }
    @Test fun ingressStillDeduplicatesFiltersAndReportsAcceptanceNotVisibility() {
        val f = Fixture(); f.session.start()
        assertEquals(202, f.receive(f.raw("one")).code)
        assertTrue(f.overlay.shown.isEmpty())
        assertEquals("duplicate", f.receive(f.raw("one")).status)
        f.settings.setEnabled("grab", false)
        assertEquals("filtered", f.receive(f.raw("two", "grab")).status)
        f.drain()
        assertEquals(listOf("one"), f.overlay.shown.map { it.id })
        assertEquals(f.time, f.session.snapshot.lastAcceptedAt)
        f.session.stop()
    }
    @Test fun waitingCapacityIsStillTenWithOneVisibleCard() {
        val f = Fixture(); f.session.start()
        assertTrue(f.session.preview(f.event("visible")))
        repeat(10) { assertTrue(f.session.preview(f.event("$it"))) }
        assertFalse(f.session.preview(f.event("overflow")))
        f.session.stop()
        f.overlay.dismissals.first()(); f.drain()
        assertEquals(1, f.overlay.shown.size)
    }
    @Test fun aFreshOwnerDoesNotInheritQueueOrDeduplicationState() {
        val first = Fixture(); first.session.start(); first.receive(first.raw("id")); first.session.stop(); first.drain()
        val next = Fixture(); next.session.start()
        assertEquals(202, next.receive(next.raw("id")).code)
        next.drain()
        assertEquals(listOf("id"), next.overlay.shown.map { it.id })
        next.session.stop()
    }
    @Test fun stopClosesTheRealListenersIncompleteConnectionAndReleasesItsPort() {
        val settings = Preferences()
        val overlay = Overlay()
        lateinit var server: LocalServer
        val session = ReceiverSession(settings, { true }, { receive, failed ->
            LocalServer("012345", receive, failed, 0).also { server = it }
        }, overlay, {}, {})
        assertTrue(session.start())
        val port = server.boundPort
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().write("POST /api/".toByteArray())
            session.stop()
            try { assertEquals(-1, socket.getInputStream().read()) }
            catch (_: java.net.SocketException) {}
        }
        val replacement = LocalServer("012345", { EventGate.Result(202, "accepted") }, {}, port)
        try { replacement.start(); assertEquals(port, replacement.boundPort) } finally { replacement.close() }
        assertTrue(overlay.shown.isEmpty())
    }

    @Test fun urgentAndNormalMessagesUseTheSameBoundedFifoAndDismissalOwnership() {
        val f = Fixture(); f.session.start()
        assertEquals(202, f.receive(f.raw("normal", "alert")).code); f.drain()
        assertEquals(202, f.receive(f.raw("urgent", "alert2")).code); f.drain()
        assertEquals(listOf("alert"), f.overlay.shown.map { it.source })
        f.overlay.dismissals[0](); f.drain()
        assertEquals(listOf("alert", "alert2"), f.overlay.shown.map { it.source })
        f.overlay.dismissals[0](); f.drain() // A stale timeout cannot dismiss the urgent overlay.
        assertEquals(2, f.overlay.shown.size)
        f.session.stop(); assertEquals(1, f.overlay.clears)
    }
}
