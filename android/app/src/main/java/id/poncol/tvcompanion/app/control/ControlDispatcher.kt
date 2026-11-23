package id.poncol.tvcompanion.app.control

import id.poncol.tvcompanion.domain.LightPresets
import org.json.JSONArray
import org.json.JSONObject

enum class Outcome(val wire: String) {
    OK("ok"), PENDING("pending"), BUSY("busy"), UNAVAILABLE("unavailable"), UNCONFIRMED("unconfirmed"),
    NEEDS_SETUP("needs_setup"), NEEDS_APPROVAL("needs_approval"), INVALID("invalid")
}
data class AmbientStatus(val state: String, val look: String?, val looks: List<String>)
data class AmbilightLookStatus(val style: String, val styles: List<String>, val contrast: String, val contrasts: List<String>)
data class LightStatus(val state: String, val preset: String?, val presets: List<String>)
/** `connected` and `keepSession` drive the confirm rule only; they are not on the wire. */
data class AirPlayStatus(val state: String, val available: Boolean, val connected: Boolean, val keepSession: Boolean)

/** Blocking calls made on a connection thread; implementations own main-thread hops and their own timeouts. */
interface ControlFeatures {
    fun ambient(): AmbientStatus
    fun ambilight(): String
    fun ambilightLook(): AmbilightLookStatus
    fun light(): LightStatus
    fun notifications(): String
    fun airplay(): AirPlayStatus
    fun setAmbient(open: Boolean, look: String?): Outcome
    fun setAmbilight(on: Boolean): Outcome
    fun setAmbilightLook(style: String?, contrast: String?): Outcome
    fun setLight(on: Boolean): Outcome
    fun lightPreset(name: String): Outcome
    fun setNotifications(on: Boolean): Outcome
    fun setAirPlay(available: Boolean): Outcome
}

/** Validates and routes paired commands. Each feature fails alone; no feature logic lives here. */
class ControlDispatcher(private val features: ControlFeatures) {
    fun handle(command: ControlCommand): ByteArray {
        var reason: String? = null
        val outcome = attempt {
            when (command) {
                ControlCommand.Status, is ControlCommand.Pair -> Outcome.OK
                is ControlCommand.Ambient ->
                    if (command.open && command.look !in features.ambient().looks) Outcome.INVALID
                    else features.setAmbient(command.open, command.look)
                is ControlCommand.Ambilight -> features.setAmbilight(command.on)
                is ControlCommand.AmbilightLook -> {
                    val look = features.ambilightLook()
                    if ((command.style ?: look.style) !in look.styles || (command.contrast ?: look.contrast) !in look.contrasts) Outcome.INVALID
                    else features.setAmbilightLook(command.style, command.contrast)
                }
                is ControlCommand.Light -> features.setLight(command.on)
                is ControlCommand.LightPreset ->
                    if (command.name !in LightPresets.NAMES) Outcome.INVALID else features.lightPreset(command.name)
                is ControlCommand.Notifications -> features.setNotifications(command.on)
                is ControlCommand.AirPlay -> {
                    val airplay = features.airplay()
                    if (!command.available && !command.confirm && airplay.connected && !airplay.keepSession) {
                        reason = "would_end_playback"; Outcome.INVALID
                    } else features.setAirPlay(command.available)
                }
            }
        }
        return ControlWire.response(outcome.wire, status(), reason)
    }

    fun status(): JSONObject {
        val ambient = read({ features.ambient() }) { AmbientStatus("unavailable", null, emptyList()) }
        val light = read({ features.light() }) { LightStatus("unavailable", null, emptyList()) }
        val airplay = read({ features.airplay() }) { AirPlayStatus("failed", false, connected = false, keepSession = true) }
        val ambilight = JSONObject().put("state", read({ features.ambilight() }) { "error" })
        read({ features.ambilightLook() }) { null }?.let {
            ambilight.put("style", it.style).put("styles", JSONArray(it.styles)).put("contrast", it.contrast).put("contrasts", JSONArray(it.contrasts))
        }
        return JSONObject()
            .put("ambient", JSONObject().put("state", ambient.state).put("look", ambient.look ?: JSONObject.NULL).put("looks", JSONArray(ambient.looks)))
            .put("ambilight", ambilight)
            .put("light", JSONObject().put("state", light.state).put("preset", light.preset ?: JSONObject.NULL).put("presets", JSONArray(light.presets)))
            .put("notifications", JSONObject().put("state", read({ features.notifications() }) { "failed" }))
            .put("airplay", JSONObject().put("state", airplay.state).put("available", airplay.available))
    }

    private inline fun attempt(block: () -> Outcome) = try { block() } catch (_: Exception) { Outcome.UNAVAILABLE }
    private inline fun <T> read(block: () -> T, fallback: () -> T) = try { block() } catch (_: Exception) { fallback() }
}
