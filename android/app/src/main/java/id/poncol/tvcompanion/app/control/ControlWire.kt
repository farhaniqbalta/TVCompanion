package id.poncol.tvcompanion.app.control

import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey

sealed class ControlCommand {
    object Status : ControlCommand()
    data class Pair(val proof: String) : ControlCommand()
    data class Ambient(val open: Boolean, val look: String?) : ControlCommand()
    data class Ambilight(val on: Boolean) : ControlCommand()
    data class Light(val on: Boolean) : ControlCommand()
    data class LightPreset(val name: String) : ControlCommand()
    data class Notifications(val on: Boolean) : ControlCommand()
    data class AirPlay(val available: Boolean, val confirm: Boolean) : ControlCommand()
}

/** contracts/controls-v1.md: varint-framed UTF-8 JSON. Everything is bounded before allocation. */
object ControlWire {
    const val PORT = 7380
    const val MAX_REQUEST = 4096
    const val MAX_RESPONSE = 16_384
    private val HEX = Regex("[0-9a-f]{64}")

    fun readFrame(input: InputStream, max: Int = MAX_REQUEST): ByteArray {
        var length = 0L
        for (i in 0 until 5) {
            val byte = input.read()
            if (byte < 0) throw EOFException()
            length = length or ((byte and 0x7f).toLong() shl (7 * i))
            if (length > max) throw IOException("frame too large")
            if (byte and 0x80 == 0) {
                val payload = ByteArray(length.toInt())
                var read = 0
                while (read < payload.size) {
                    val n = input.read(payload, read, payload.size - read)
                    if (n < 0) throw EOFException()
                    read += n
                }
                return payload
            }
        }
        throw IOException("bad length")
    }

    fun writeFrame(output: OutputStream, payload: ByteArray) {
        var length = payload.size
        while (length >= 0x80) { output.write((length and 0x7f) or 0x80); length = length ushr 7 }
        output.write(length)
        output.write(payload)
        output.flush()
    }

    /** RSAPublicKey DER (SEQUENCE { n, e }): the bytes iOS gets from SecKeyCopyExternalRepresentation. */
    fun pkcs1(key: RSAPublicKey): ByteArray = der(0x30, integer(key.modulus) + integer(key.publicExponent))
    private fun integer(value: BigInteger) = der(0x02, value.toByteArray())
    private fun der(tag: Int, body: ByteArray): ByteArray {
        val length = if (body.size < 0x80) byteArrayOf(body.size.toByte()) else {
            val bytes = BigInteger.valueOf(body.size.toLong()).toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
            byteArrayOf((0x80 or bytes.size).toByte()) + bytes
        }
        return byteArrayOf(tag.toByte()) + length + body
    }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun proof(code: String, clientPkcs1: ByteArray, serverPkcs1: ByteArray): String =
        hex(sha256(code.toByteArray(Charsets.UTF_8) + sha256(clientPkcs1) + sha256(serverPkcs1)))

    fun parse(bytes: ByteArray): ControlCommand? = try {
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        if (json.opt("v") != 1) null else {
            fun flag(key: String) = json.opt(key) as? Boolean
            when (json.opt("cmd")) {
                "status" -> ControlCommand.Status
                "pair" -> (json.opt("proof") as? String)?.takeIf { HEX.matches(it) }?.let { ControlCommand.Pair(it) }
                "ambient.set" -> {
                    val look = json.opt("look")
                    if (look != null && look !is String) null else flag("open")?.let { ControlCommand.Ambient(it, look as String?) }
                }
                "ambilight.set" -> flag("on")?.let { ControlCommand.Ambilight(it) }
                "light.set" -> flag("on")?.let { ControlCommand.Light(it) }
                "light.preset" -> (json.opt("name") as? String)?.let { ControlCommand.LightPreset(it) }
                "notifications.set" -> flag("on")?.let { ControlCommand.Notifications(it) }
                "airplay.set" -> {
                    val confirm = json.opt("confirm")
                    if (confirm != null && confirm !is Boolean) null
                    else flag("available")?.let { ControlCommand.AirPlay(it, confirm as Boolean? ?: false) }
                }
                else -> null
            }
        }
    } catch (_: Exception) { null }

    fun response(outcome: String, status: JSONObject?, reason: String? = null): ByteArray {
        val json = JSONObject().put("v", 1).put("outcome", outcome)
        if (reason != null) json.put("reason", reason)
        if (status != null) json.put("status", status)
        return json.toString().toByteArray(Charsets.UTF_8)
    }
}
