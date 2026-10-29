package id.poncol.tvcompanion.tuya

import id.poncol.tvcompanion.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TuyaColorTest {
    private val blue = LightColor(240, 1000, 500)
    private fun dps(mode: String = "white", color: Any? = "000003e803e8", on: Boolean = true) =
        JSONObject().put("20", on).put("21", mode).put("22", 406).apply { if (color != null) put("24", color) }
    private class Session(private val state: JSONObject, private val writes: MutableList<Map<String, Any>>) : LampSession {
        override fun connect() {}
        override fun status() = state
        override fun write(dp: String, value: Any) { writes.add(mapOf(dp to value)) }
        override fun writeValues(values: Map<String, Any>) { writes.add(values) }
        override fun close() {}
    }
    @Test fun codecMatchesHsv16AndRejectsUnknownOrOutOfRangeFormats() {
        assertEquals("00f003e801f4", TuyaColorCodec.encode(blue))
        assertEquals(blue, TuyaColorCodec.decode("00F003E801F4"))
        assertEquals(LightColor(360, 0, 0), TuyaColorCodec.decode("016800000000"))
        for (invalid in listOf(null, 42, "", "ff00000000ffff", "016903e803e8", "000003e903e8", "000003e803e9", "000003e803ez")) {
            assertNull(TuyaColorCodec.decode(invalid))
        }
    }
    @Test fun modeAndColorAreSentTogetherAndBothRequireReadback() {
        val writes = mutableListOf<Map<String, Any>>()
        val states = ArrayDeque(listOf(dps(), dps("colour", "00F003E801F4")))
        val gateway = TuyaLightGateway({ Session(states.removeFirst(), writes) }, {})
        val result = gateway.execute(LightCommand.Color(blue))
        assertEquals(listOf(mapOf("21" to "colour", "24" to "00f003e801f4")), writes)
        assertTrue(result.colorMode); assertFalse(result.whiteMode); assertEquals(blue, result.color)
        gateway.close()
    }
    @Test fun matchingColorWithoutConfirmedModeIsUnconfirmedAndNeverRetried() {
        val writes = mutableListOf<Map<String, Any>>()
        val states = ArrayDeque(listOf(dps(), dps("white", "00f003e801f4")))
        val gateway = TuyaLightGateway({ Session(states.removeFirst(), writes) }, {})
        try { gateway.execute(LightCommand.Color(blue)); fail() } catch (_: IllegalStateException) {}
        assertEquals(1, writes.size); assertTrue(states.isEmpty()); gateway.close()
    }
    @Test fun matchingModeWithoutConfirmedColorIsUnconfirmed() {
        val writes = mutableListOf<Map<String, Any>>()
        val states = ArrayDeque(listOf(dps(), dps("colour")))
        val gateway = TuyaLightGateway({ Session(states.removeFirst(), writes) }, {})
        try { gateway.execute(LightCommand.Color(blue)); fail() } catch (_: IllegalStateException) {}
        assertEquals(1, writes.size); gateway.close()
    }
    @Test fun unsupportedColorNeverWritesOrPowersOnTheLamp() {
        for (state in listOf(dps(on = false), dps(color = null), dps(color = "invalid"), dps("scene"))) {
            val writes = mutableListOf<Map<String, Any>>()
            val gateway = TuyaLightGateway({ Session(state, writes) }, {})
            try { gateway.execute(LightCommand.Color(blue)); fail() } catch (_: IllegalStateException) {}
            assertTrue(writes.isEmpty()); gateway.close()
        }
    }
    @Test fun whiteModeOnlyChangesModeAndPreservesStoredWhiteBrightness() {
        val writes = mutableListOf<Map<String, Any>>()
        val states = ArrayDeque(listOf(dps("colour"), dps("white")))
        val gateway = TuyaLightGateway({ Session(states.removeFirst(), writes) }, {})
        val result = gateway.execute(LightCommand.WhiteMode)
        assertEquals(listOf(mapOf("21" to "white")), writes)
        assertTrue(result.whiteMode); assertEquals(40, result.brightness); gateway.close()
    }
    @Test fun packetValidationAllowsOnlyTheNarrowAtomicColorGroup() {
        val result = Tuya35.controlValues(mapOf("21" to "colour", "24" to TuyaColorCodec.encode(blue)))
        assertEquals(2, result.length()); assertEquals("colour", result.getString("21"))
        for (bad in listOf(mapOf("24" to "00f003e801f4"), mapOf("21" to "colour"), mapOf("21" to "scene"),
                mapOf("21" to "colour", "24" to "broken"), mapOf("20" to true, "21" to "colour", "24" to "00f003e801f4"))) {
            try { Tuya35.controlValues(bad); fail() } catch (_: java.io.IOException) {}
        }
    }
}
