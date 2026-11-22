package id.poncol.tvcompanion.app.control

/** Synthetic TV: settable state, a call log, and per-method failures. */
class FakeFeatures : ControlFeatures {
    val calls = mutableListOf<String>()
    val throwOn = mutableSetOf<String>()
    var next = Outcome.PENDING
    var ambient = AmbientStatus("available", "aurora", listOf("aurora", "dusk"))
    var ambilight = "off"
    var light = LightStatus("on", "blue", listOf("red", "blue", "white"))
    var notifications = "listening"
    var airplay = AirPlayStatus("ready", true, connected = false, keepSession = true)
    private fun <T> call(name: String, value: () -> T): T {
        synchronized(calls) { calls += name }
        if (name in throwOn) error("synthetic $name failure")
        return value()
    }
    override fun ambient() = call("ambient") { ambient }
    override fun ambilight() = call("ambilight") { ambilight }
    override fun light() = call("light") { light }
    override fun notifications() = call("notifications") { notifications }
    override fun airplay() = call("airplay") { airplay }
    override fun setAmbient(open: Boolean, look: String?) = call("setAmbient") { next }
    override fun setAmbilight(on: Boolean) = call("setAmbilight") { next }
    override fun setLight(on: Boolean) = call("setLight") { next }
    override fun lightPreset(name: String) = call("lightPreset") { next }
    override fun setNotifications(on: Boolean) = call("setNotifications") { next }
    override fun setAirPlay(available: Boolean) = call("setAirPlay") { next }
}
