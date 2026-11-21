package id.poncol.tvcompanion.ambilight

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import org.json.JSONObject

internal data class WledInfo(val name: String, val ledCount: Int)

internal class WledClient(private val address: InetAddress) {
    fun probe(): WledInfo? {
        val data = request("/json/info") ?: return null
        return runCatching {
            val info = JSONObject(String(data, Charsets.UTF_8))
            val name = info.optString("name").take(64)
            val count = info.optJSONObject("leds")?.optInt("count") ?: 0
            WledInfo(name, count).takeIf {
                info.optString("brand").equals("WLED", ignoreCase = true) &&
                    info.optString("ver").isNotBlank() && name.isNotBlank() && count > 0
            }
        }.getOrNull()
    }

    fun exit(turnOff: Boolean): Boolean = request("/json/state", if (turnOff) {
        """{"live":false,"on":false}"""
    } else {
        """{"live":false}"""
    }) != null

    private fun request(path: String, body: String? = null): ByteArray? = try {
        // Keep the app network policy intact. Only a validated LAN address gets this direct HTTP request.
        val host = checkNotNull(address.hostAddress)
        require(ipv4Bytes(host) != null)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, 80), 1200)
            socket.soTimeout = 1200
            val writer = socket.getOutputStream().buffered()
            val method = if (body == null) "GET" else "POST"
            writer.write("$method $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n".toByteArray())
            if (body != null) {
                val bytes = body.toByteArray()
                writer.write("Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
                writer.write(bytes)
            } else writer.write("\r\n".toByteArray())
            writer.flush()
            readWledResponse(socket.getInputStream().buffered(), readBody = body == null)
        }
    } catch (error: IOException) {
        Log.w("Ambilight", "WLED $path failed: ${error.message?.takeIf { it.startsWith("HTTP ") } ?: error.javaClass.simpleName}")
        null
    }
}

internal fun readWledResponse(input: InputStream, readBody: Boolean): ByteArray {
    val deadline = System.nanoTime() + 1_500_000_000L
    var headerBytes = 0

    fun checkDeadline() {
        if (System.nanoTime() >= deadline) throw IOException("WLED response timed out")
    }

    fun line(): String {
        val text = StringBuilder()
        while (headerBytes++ < 8192) {
            checkDeadline()
            val byte = input.read()
            if (byte < 0) throw IOException("Incomplete HTTP headers")
            if (byte == 13) {
                if (input.read() != 10) throw IOException("Invalid HTTP line")
                headerBytes++
                return text.toString()
            }
            if (byte < 32 && byte != 9) throw IOException("Invalid HTTP header")
            text.append(byte.toChar())
        }
        throw IOException("HTTP headers too large")
    }

    val status = line().split(' ', limit = 3)
    val code = status.getOrNull(1)?.toIntOrNull() ?: 0
    if (status.firstOrNull() !in listOf("HTTP/1.0", "HTTP/1.1") || code !in 200..299) {
        throw IOException("HTTP status $code")
    }
    if (!readBody) return ByteArray(0)

    var length: Int? = null
    while (true) {
        val header = line()
        if (header.isEmpty()) break
        val separator = header.indexOf(':')
        if (separator <= 0) throw IOException("Invalid HTTP header")
        val name = header.substring(0, separator)
        if (name.equals("transfer-encoding", ignoreCase = true)) throw IOException("Unsupported HTTP transfer encoding")
        if (name.equals("content-length", ignoreCase = true)) {
            if (length != null) throw IOException("Repeated HTTP content length")
            length = header.substring(separator + 1).trim().toIntOrNull()
                ?: throw IOException("Invalid HTTP content length")
        }
    }
    val size = length ?: throw IOException("Missing HTTP content length")
    if (size !in 1..16384) throw IOException("Invalid HTTP content length")
    // shortcut: stock WLED returns Content-Length, add chunk decoding if supported firmware requires it.
    val bytes = ByteArray(size)
    var offset = 0
    while (offset < size) {
        checkDeadline()
        val count = input.read(bytes, offset, size - offset)
        if (count <= 0) throw IOException("Incomplete HTTP body")
        offset += count
    }
    return bytes
}
