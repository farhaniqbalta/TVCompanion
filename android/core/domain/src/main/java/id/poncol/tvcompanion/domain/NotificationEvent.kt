package id.poncol.tvcompanion.domain

/** Transient content only: never written to disk. */
data class NotificationEvent(
    val id: String, val source: String, val title: String, val body: String,
    val receivedAt: Long, val expiresAt: Long, val durationSeconds: Int
)

/** Ten waiting events plus one visible card. Drop newest on overflow. */
class EventQueue(private val now: () -> Long = { System.currentTimeMillis() }) {
    private val waiting = ArrayDeque<NotificationEvent>()
    @Synchronized fun offer(event: NotificationEvent): Boolean {
        waiting.removeAll { it.expiresAt < now() }
        if (waiting.size >= 10) return false
        waiting.addLast(event)
        return true
    }
    @Synchronized fun next(): NotificationEvent? {
        while (waiting.isNotEmpty()) {
            val event = waiting.removeFirst()
            if (event.expiresAt >= now()) return event
        }
        return null
    }
    @Synchronized fun clear() = waiting.clear()
}
