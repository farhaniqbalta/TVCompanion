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
        (dps.opt("22") as? Number)?.toInt()?.takeIf { it in 10..1000 }?.let { Math.round((it - 10) * 100f / 990) }
    )
    override fun read(): LightState = session { state(it.status()) }
    override fun execute(command: LightCommand): LightState {
        val dp: String
        val value: Any
        when (command) {
            is LightCommand.Power -> { dp = "20"; value = command.on }
            is LightCommand.Brightness -> { dp = "22"; value = 10 + Math.round(command.percent * 990f / 100) }
        }
        session { client ->
            val before = state(client.status())
            check(command !is LightCommand.Brightness || before.supportsBrightness) { "Brightness unavailable" }
            client.write(dp, value)
        }
        checkOpen(); pause(600); checkOpen()
        return session { client ->
            val result = client.status()
            check(result.opt(dp) == value) { "Result unconfirmed" }
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
