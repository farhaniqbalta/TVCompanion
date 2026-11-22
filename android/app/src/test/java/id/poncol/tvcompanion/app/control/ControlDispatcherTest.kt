package id.poncol.tvcompanion.app.control

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ControlDispatcherTest {
    private val fake = FakeFeatures()
    private val dispatcher = ControlDispatcher(fake)
    private fun handle(c: ControlCommand) = JSONObject(String(dispatcher.handle(c)))
    private fun assertOutcome(expected: String, c: ControlCommand) = assertEquals(expected, handle(c).getString("outcome"))

    @Test fun statusSerialisesContractShape() {
        val s = handle(ControlCommand.Status).also { assertEquals("ok", it.getString("outcome")) }.getJSONObject("status")
        val ambient = s.getJSONObject("ambient")
        assertEquals("available", ambient.getString("state")); assertEquals("aurora", ambient.getString("look"))
        assertEquals(2, ambient.getJSONArray("looks").length())
        assertEquals("off", s.getJSONObject("ambilight").getString("state"))
        val light = s.getJSONObject("light")
        assertEquals("on", light.getString("state")); assertEquals("blue", light.getString("preset")); assertEquals(3, light.getJSONArray("presets").length())
        assertEquals("listening", s.getJSONObject("notifications").getString("state"))
        val airplay = s.getJSONObject("airplay")
        assertEquals("ready", airplay.getString("state")); assertTrue(airplay.getBoolean("available"))
        assertFalse(airplay.has("connected")); assertFalse(airplay.has("keepSession"))
    }
    @Test fun oneFailingFeatureDoesNotBlankStatus() {
        fake.throwOn += listOf("light", "ambilight")
        val s = dispatcher.status()
        assertEquals("unavailable", s.getJSONObject("light").getString("state"))
        assertEquals("error", s.getJSONObject("ambilight").getString("state"))
        assertEquals("listening", s.getJSONObject("notifications").getString("state"))
    }
    @Test fun invalidLookAndPresetAreRejectedWithoutCalls() {
        assertOutcome("invalid", ControlCommand.Ambient(true, "neon"))
        assertOutcome("invalid", ControlCommand.Ambient(true, null))
        assertOutcome("invalid", ControlCommand.LightPreset("teal"))
        assertTrue(fake.calls.none { it.startsWith("set") || it == "lightPreset" })
        assertOutcome("pending", ControlCommand.Ambient(false, null))   // close needs no look
    }
    @Test fun airplayOffConfirmRule() {
        fake.airplay = AirPlayStatus("playing", true, connected = true, keepSession = false)
        val r = handle(ControlCommand.AirPlay(false, false))
        assertEquals("invalid", r.getString("outcome")); assertEquals("would_end_playback", r.getString("reason")); assertFalse("setAirPlay" in fake.calls)
        assertOutcome("pending", ControlCommand.AirPlay(false, true))
        fake.airplay = AirPlayStatus("playing", true, connected = true, keepSession = true); fake.calls.clear()
        assertOutcome("pending", ControlCommand.AirPlay(false, false)); assertTrue("setAirPlay" in fake.calls)
    }
    @Test fun setterExceptionIsUnavailableAndOutcomesPassThrough() {
        fake.throwOn += "setLight"; assertOutcome("unavailable", ControlCommand.Light(true))
        fake.next = Outcome.BUSY; assertOutcome("busy", ControlCommand.Notifications(true))
        fake.next = Outcome.NEEDS_SETUP; assertOutcome("needs_setup", ControlCommand.Ambilight(true))
    }
    @Test fun statusIsReadAfterTheCommand() {
        handle(ControlCommand.Notifications(false))
        assertTrue(fake.calls.indexOf("setNotifications") < fake.calls.indexOf("notifications"))
    }
    @Test fun presetIsValidatedWithoutReadingTheLampFirst() {
        assertOutcome("pending", ControlCommand.LightPreset("purple"))
        assertEquals("lightPreset", fake.calls.first())   // no lamp read before the write
    }
}
