package id.poncol.tvcompanion.notifications.infrastructure
import id.poncol.tvcompanion.domain.*

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.time.Instant

class EventGateTest {
    private var time = 1_800_000_000_000L
    private val gate = EventGate(now = { time })
    private fun event(id: String = "one", source: String = "grab", sent: Long = time, ttl: String = "120") =
        """{"id":"$id","source":"$source","sentAt":"${Instant.ofEpochMilli(sent)}","ttlSeconds":$ttl}"""
    @Test fun acceptsAllowedSourceAndDeduplicates() {
        assertEquals(202, gate.accept(event()).code)
        assertEquals("duplicate", gate.accept(event()).status)
    }
    @Test fun rateLimitsBurstsWithoutBlockingSequentialQueue() {
        repeat(12) { assertEquals(202, gate.accept(event("$it")).code) }
        assertEquals(429, gate.accept(event("next")).code)
        time += 8000
        assertEquals(202, gate.accept(event("next")).code)
    }
    @Test fun rejectsExpiredAndFutureEvents() {
        assertEquals(410, gate.accept(event(sent = time - 121000)).code)
        assertEquals(410, gate.accept(event(sent = time + 31000)).code)
    }
    @Test fun rejectsInvalidSourceAndPayloads() {
        assertEquals(400, gate.accept(event(source = "other")).code)
        assertEquals(400, gate.accept("not json").code)
        assertEquals(400, gate.accept("{}").code)
        assertEquals(400, gate.accept(event(ttl = "1.5")).code)
        assertEquals(400, gate.accept(event(ttl = "301")).code)
    }
    @Test fun acceptsHandoffContractForAllThemes() {
        for (source in listOf("grab", "shopeefood", "gojek", "alert")) {
            val result = gate.accept(JSONObject().put("source", source).put("title", "Hello\u0000there")
                .put("body", "Demo body").put("timestamp", Instant.ofEpochMilli(time).toString()).put("durationSeconds", 8).toString())
            assertEquals(202, result.code)
            assertEquals(source, result.event?.source)
            assertEquals("Hellothere", result.event?.title)
            assertEquals("Demo body", result.event?.body)
        }
    }
    @Test fun supportsLegacyMessageAndSourceAliases() {
        val raw = JSONObject(event(source = "test")).put("message", "halo sayang ayo makan").toString()
        val result = gate.accept(raw)
        assertEquals("halo sayang ayo makan", result.message)
        assertEquals("alert", result.source)
        assertEquals("shopeefood", gate.accept(event("two", "shopee")).source)
    }
    @Test fun rejectsInvalidTextAndDuration() {
        for (value in listOf(123, "x".repeat(513), JSONObject.NULL)) {
            assertEquals(400, gate.accept(JSONObject(event()).put("message", value).toString()).code)
        }
        for (value in listOf(0, 31, 8.5, "8")) {
            assertEquals(400, gate.accept(JSONObject(event()).put("durationSeconds", value).toString()).code)
        }
        assertEquals(400, gate.accept(JSONObject(event()).put("timestamp", "bad").toString()).code)
    }
    @Test fun filteredAndOverflowEventsAreNotSubmittedOrRemembered() {
        var enabled = false
        var capacity = false
        var calls = 0
        val gate = EventGate(now = { time }, enabled = { enabled }, submit = { calls++; capacity })
        assertEquals("filtered", gate.accept(event()).status)
        assertEquals(0, calls)
        enabled = true
        assertEquals("queue_full", gate.accept(event()).status)
        capacity = true
        assertEquals(202, gate.accept(event()).code)
        assertEquals("duplicate", gate.accept(event()).status)
        assertEquals(2, calls)
    }
    @Test fun expiredIdsCanBeReused() {
        gate.accept(event(ttl = "1"))
        time += 8000
        assertEquals(202, gate.accept(event()).code)
    }
}
