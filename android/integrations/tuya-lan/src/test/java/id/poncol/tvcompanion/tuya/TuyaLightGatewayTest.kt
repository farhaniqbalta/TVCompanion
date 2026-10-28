package id.poncol.tvcompanion.tuya

import id.poncol.tvcompanion.domain.LightCommand
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TuyaLightGatewayTest {
    private class Session(private val name: String, private val result: JSONObject, private val log: MutableList<String>) : LampSession {
        override fun connect() { log.add("$name.connect") }
        override fun status(): JSONObject { log.add("$name.read"); return result }
        override fun write(dp: String, value: Any) { log.add("$name.write:$dp=$value") }
        override fun close() { log.add("$name.close") }
    }
    private fun dps(on: Boolean = true, mode: String = "white", raw: Int = 406) =
        JSONObject().put("20", on).put("21", mode).put("22", raw)
    @Test fun commandReadsBeforeWritingAndOnlyConfirmsAfterSeparateReadback() {
        val log = mutableListOf<String>()
        val sessions = ArrayDeque<LampSession>().apply {
            add(Session("before", dps(), log)); add(Session("after", dps(on = false), log))
        }
        val gateway = TuyaLightGateway({ sessions.removeFirst() }, { log.add("settle:$it") })
        assertFalse(gateway.execute(LightCommand.Power(false)).on)
        assertEquals(listOf("before.connect", "before.read", "before.write:20=false", "before.close", "settle:600", "after.connect", "after.read", "after.close"), log)
        gateway.close()
    }
    @Test fun readbackMismatchIsUnconfirmedWithoutAnotherWrite() {
        val log = mutableListOf<String>()
        var opens = 0
        val gateway = TuyaLightGateway({ Session("${++opens}", dps(), log) }, {})
        try { gateway.execute(LightCommand.Power(false)); fail("Send alone must not count as confirmation") }
        catch (_: IllegalStateException) {}
        assertEquals(2, opens)
        assertEquals(1, log.count { it.contains("write:") })
        assertEquals(2, log.count { it.endsWith("close") })
        gateway.close()
    }
    @Test fun unsupportedBrightnessClosesSessionWithoutWriting() {
        for (state in listOf(dps(on = false), dps(mode = "colour"))) {
            val log = mutableListOf<String>()
            val gateway = TuyaLightGateway({ Session("lamp", state, log) }, {})
            try { gateway.execute(LightCommand.Brightness(50)); fail() }
            catch (_: IllegalStateException) {}
            assertFalse(log.any { it.contains("write:") })
            assertEquals("lamp.close", log.last())
            gateway.close()
        }
    }
    @Test fun brightnessMapsToTheExistingValidatedRawScale() {
        val log = mutableListOf<String>()
        val sessions = ArrayDeque<LampSession>().apply {
            add(Session("before", dps(), log)); add(Session("after", dps(raw = 505), log))
        }
        val gateway = TuyaLightGateway({ sessions.removeFirst() }, {})
        assertEquals(50, gateway.execute(LightCommand.Brightness(50)).brightness)
        assertTrue(log.contains("before.write:22=505"))
        gateway.close()
    }
    @Test fun closingDuringConnectReleasesIoBeforeHandshakeCanFinish() {
        val entered = CountDownLatch(1); val released = CountDownLatch(1)
        val session = object : LampSession {
            override fun connect() { entered.countDown(); check(released.await(3, TimeUnit.SECONDS)) }
            override fun status(): JSONObject { error("Cancelled connection must not read state") }
            override fun write(dp: String, value: Any) { error("Must not write") }
            override fun close() { released.countDown() }
        }
        val gateway = TuyaLightGateway({ session }, {})
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Boolean> { try { gateway.read(); false } catch (_: java.io.InterruptedIOException) { true } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            gateway.close()
            assertTrue(result.get(2, TimeUnit.SECONDS))
        } finally { gateway.close(); worker.shutdownNow() }
    }
    @Test fun closingDuringSessionCreationClosesItBeforeAnyConnect() {
        val creating = CountDownLatch(1); val finish = CountDownLatch(1)
        val log = mutableListOf<String>()
        val gateway = TuyaLightGateway({ creating.countDown(); check(finish.await(3, TimeUnit.SECONDS)); Session("late", dps(), log) }, {})
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Boolean> { try { gateway.read(); false } catch (_: java.io.InterruptedIOException) { true } }
            assertTrue(creating.await(2, TimeUnit.SECONDS)); gateway.close(); finish.countDown()
            assertTrue(result.get(2, TimeUnit.SECONDS))
            assertEquals(listOf("late.close"), log)
        } finally { finish.countDown(); gateway.close(); worker.shutdownNow() }
    }
    @Test fun cancellationAfterWriteDoesNotOpenReadbackOrRetry() {
        val log = mutableListOf<String>()
        lateinit var gateway: TuyaLightGateway
        gateway = TuyaLightGateway({ Session("lamp", dps(), log) }, { gateway.close() })
        try { gateway.execute(LightCommand.Power(false)); fail() }
        catch (_: java.io.InterruptedIOException) {}
        assertEquals(1, log.count { it.endsWith("connect") })
        assertEquals(1, log.count { it.contains("write:") })
    }
}
