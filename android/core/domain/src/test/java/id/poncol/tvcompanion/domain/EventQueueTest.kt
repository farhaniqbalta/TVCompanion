package id.poncol.tvcompanion.domain
import id.poncol.tvcompanion.domain.*

import org.junit.Assert.*
import org.junit.Test

class EventQueueTest {
    @Test fun boundsQueueAndKeepsFifoOrder() {
        val queue = EventQueue { 1000L }
        fun event(id: Int) = NotificationEvent("$id", "alert", "Title", "Body", 1000, 5000, 8)
        repeat(10) { assertTrue(queue.offer(event(it))) }
        assertFalse(queue.offer(event(10)))
        repeat(10) { assertEquals("$it", queue.next()?.id) }
        assertNull(queue.next())
    }
    @Test fun discardsExpiredWaitingEventsAndCanClear() {
        var time = 1000L
        val queue = EventQueue { time }
        queue.offer(NotificationEvent("old", "alert", "Title", "Body", time, 2000, 8))
        time = 3000
        assertNull(queue.next())
        queue.offer(NotificationEvent("new", "alert", "Title", "Body", time, 5000, 8))
        queue.clear()
        assertNull(queue.next())
    }
}
