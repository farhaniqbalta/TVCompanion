package id.poncol.tvcompanion.tuya

import android.content.Context
import id.poncol.tvcompanion.domain.*
import org.json.JSONObject

class TuyaLightGateway(context: Context) : LightGateway {
    private val context = context.applicationContext
    private fun connect(): Tuya35 {
        val config = Credentials.load(context) ?: error("Lamp setup required")
        val key = config.getString("key").toByteArray(Charsets.UTF_8)
        return try { Tuya35(config.getString("ip"), key) } finally { key.fill(0) }
    }
    private fun state(dps: JSONObject) = LightState(
        dps.getBoolean("20"), dps.optString("21") == "white",
        (dps.opt("22") as? Number)?.toInt()?.takeIf { it in 10..1000 }?.let { Math.round((it - 10) * 100f / 990) }
    )
    override fun read(): LightState = connect().use { state(it.status()) }
    override fun execute(command: LightCommand): LightState {
        val dp: String
        val value: Any
        when (command) {
            is LightCommand.Power -> { dp = "20"; value = command.on }
            is LightCommand.Brightness -> { dp = "22"; value = 10 + Math.round(command.percent * 990f / 100) }
        }
        connect().use { client ->
            val before = state(client.status())
            check(command !is LightCommand.Brightness || before.supportsBrightness) { "Brightness unavailable" }
            client.write(dp, value)
        }
        Thread.sleep(600)
        return connect().use { client ->
            val result = client.status()
            check(result.opt(dp) == value) { "Result unconfirmed" }
            state(result)
        }
    }
}
