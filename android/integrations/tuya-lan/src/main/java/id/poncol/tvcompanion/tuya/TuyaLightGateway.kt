package id.poncol.tvcompanion.tuya

import android.content.Context
import id.poncol.tvcompanion.domain.*
import org.json.JSONObject
import java.io.InterruptedIOException

/** A session is registered with its owner before connect/negotiation can block. */
internal interface LampSession : AutoCloseable {
    @Throws(Exception::class)
    fun connect()
    @Throws(Exception::class)
    fun status(): JSONObject
    @Throws(Exception::class)
    fun write(dp: String, value: Any)
    /** Multi-DPS mode/color changes must be sent as one control packet. */
    @Throws(Exception::class)
    fun writeValues(values: Map<String, Any>) {
        check(values.size == 1) { "Atomic lamp write unavailable" }
        values.entries.single().let { write(it.key, it.value) }
    }
    override fun close()
}

class TuyaLightGateway internal constructor(
    private val createSession: () -> LampSession,
    private val pause: (Long) -> Unit = { Thread.sleep(it) }
) : LightGateway {
    constructor(context: Context) : this(provider(context.applicationContext))
    private val lock = Any()
    private var closed = false
    private var active: LampSession? = null
    private fun checkOpen() {
        synchronized(lock) {
            if (closed || Thread.currentThread().isInterrupted) throw InterruptedIOException("Light operation cancelled")
        }
    }
    private fun <T> session(block: (LampSession) -> T): T {
        checkOpen()
        val client = createSession()
        synchronized(lock) {
            if (closed || Thread.currentThread().isInterrupted) {
                client.close(); throw InterruptedIOException("Light operation cancelled")
            }
            if (active != null) { client.close(); error("Light operation already active") }
            active = client
        }
        try {
            client.connect()
            checkOpen()
            val result = block(client)
            checkOpen()
            return result
        } finally {
            client.close()
            synchronized(lock) { if (active === client) active = null }
        }
    }
    private fun state(dps: JSONObject) = LightState(
        dps.getBoolean("20"), dps.optString("21") == "white",
        (dps.opt("22") as? Number)?.toInt()?.takeIf { it in 10..1000 }?.let { Math.round((it - 10) * 100f / 990) },
        TuyaColorCodec.decode(dps.opt("24")), dps.optString("21") == "colour"
    )
    override fun read(): LightState = session { state(it.status()) }
    override fun execute(command: LightCommand): LightState {
        val values: Map<String, Any> = when (command) {
            is LightCommand.Power -> mapOf("20" to command.on)
            is LightCommand.Brightness -> mapOf("22" to (10 + Math.round(command.percent * 990f / 100)))
            is LightCommand.Color -> linkedMapOf("21" to "colour", "24" to TuyaColorCodec.encode(command.color))
            LightCommand.WhiteMode -> mapOf("21" to "white")
        }
        session { client ->
            val before = state(client.status())
            when (command) {
                is LightCommand.Brightness -> check(before.supportsBrightness) { "Brightness unavailable" }
                is LightCommand.Color -> check(before.supportsColor) { "Color unavailable" }
                LightCommand.WhiteMode -> check(before.supportsWhiteMode) { "White mode unavailable" }
                is LightCommand.Power -> Unit
            }
            client.writeValues(values)
        }
        checkOpen(); pause(600); checkOpen()
        return session { client ->
            val result = client.status()
            check(values.all { (dp, value) ->
                if (dp == "24") {
                    val actual = TuyaColorCodec.decode(result.opt(dp))
                    val expected = (command as LightCommand.Color).color
                    actual != null && actual.copy(hue = actual.hue % 360) == expected.copy(hue = expected.hue % 360)
                }
                else result.opt(dp) == value
            }) { "Result unconfirmed" }
            state(result)
        }
    }
    override fun close() {
        val client = synchronized(lock) { closed = true; active }
        // Do not hold the owner lock while releasing I/O on another thread.
        client?.close()
    }
    companion object {
        private fun provider(context: Context): () -> LampSession = {
            val config = Credentials.load(context) ?: error("Lamp setup required")
            val key = config.getString("key").toByteArray(Charsets.UTF_8)
            try { Tuya35(config.getString("ip"), key) } finally { key.fill(0) }
        }
    }
}
