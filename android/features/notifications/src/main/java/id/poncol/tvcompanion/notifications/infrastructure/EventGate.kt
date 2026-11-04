package id.poncol.tvcompanion.notifications.infrastructure

import id.poncol.tvcompanion.domain.NotificationEvent

import org.json.JSONObject
import java.time.Instant
import java.util.UUID

class EventGate(
    private val now: () -> Long = { System.currentTimeMillis() },
    private val duration: () -> Int = { 8 },
    private val enabled: (String) -> Boolean = { true },
    private val submit: (NotificationEvent) -> Boolean = { true }
) {
    private val seen = LinkedHashMap<String, Long>()
    private val recent = ArrayDeque<Long>()
    data class Result(val code: Int, val status: String, val event: NotificationEvent? = null) {
        val source get() = event?.source
        val message get() = event?.body
    }
    @Synchronized fun accept(raw: String): Result {
        val json = try { JSONObject(raw) } catch (_: Exception) { return Result(400, "invalid_json") }
        val id = if (!json.has("id")) UUID.randomUUID().toString() else
            json.opt("id") as? String ?: return Result(400, "invalid_id")
        if (id.isBlank() || id.length > 128) return Result(400, "invalid_id")
        val suppliedSource = json.opt("source") as? String ?: return Result(400, "invalid_source")
        val source = when (suppliedSource) { "shopee" -> "shopeefood"; "test" -> "alert"; else -> suppliedSource }
        if (source !in setOf("grab", "shopeefood", "gojek", "alert", "alert2")) return Result(400, "invalid_source")
        for (key in listOf("title", "body", "message")) {
            if (json.has(key) && (json.opt(key) !is String || json.getString(key).length > 512))
                return Result(400, "invalid_text")
        }
        fun clean(key: String) = json.optString(key).filter { !it.isISOControl() }.trim()
        val ttl = if (!json.has("ttlSeconds")) 120 else json.opt("ttlSeconds") as? Int ?: return Result(400, "invalid_ttl")
        if (ttl !in 1..300) return Result(400, "invalid_ttl")
        val seconds = if (!json.has("durationSeconds")) duration() else
            json.opt("durationSeconds") as? Int ?: return Result(400, "invalid_duration")
        if (seconds !in 3..30) return Result(400, "invalid_duration")
        val time = now()
        val timestampKey = if (json.has("timestamp")) "timestamp" else "sentAt"
        val sent = if (!json.has(timestampKey)) time else try {
            Instant.parse(json.getString(timestampKey)).toEpochMilli()
        } catch (_: Exception) { return Result(400, "invalid_sent_at") }
        if (sent > time + 30_000 || sent < time - ttl * 1000L) return Result(410, "expired_or_future")
        seen.entries.removeAll { it.value < time }
        if (seen.containsKey(id)) return Result(200, "duplicate")
        if (!enabled(source)) return Result(200, "filtered")
        while (recent.isNotEmpty() && recent.first() <= time - 8000) recent.removeFirst()
        if (recent.size >= 12) return Result(429, "rate_limited")
        val event = NotificationEvent(id, source, clean("title").ifBlank { "Delivery update" },
            clean("body").ifBlank { clean("message") }, time, sent + ttl * 1000L, seconds)
        if (!submit(event)) return Result(429, "queue_full")
        if (seen.size >= 256) seen.remove(seen.keys.first())
        seen[id] = event.expiresAt
        recent.addLast(time)
        return Result(202, "accepted", event)
    }
}
