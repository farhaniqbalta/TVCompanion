package id.poncol.tvcompanion.ambilight

import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.URL
import org.json.JSONObject

internal class WledClient(private val address: InetAddress) {
    fun probe(expectedLeds: Int): String? = request("/json/info") { http ->
        val bytes = ByteArray(16 * 1024)
        var size = 0
        val deadline = System.nanoTime() + 1_500_000_000
        http.inputStream.use { input ->
            while (size < bytes.size) {
                if (System.nanoTime() >= deadline) throw IOException("WLED read timed out")
                val count = input.read(bytes, size, bytes.size - size)
                if (count == -1) break
                size += count
            }
        }
        if (size == bytes.size) return@request null
        val info = JSONObject(String(bytes, 0, size, Charsets.UTF_8))
        val name = info.optString("name").take(64)
        val count = info.optJSONObject("leds")?.optInt("count") ?: 0
        name.takeIf { info.optString("brand").equals("WLED", ignoreCase = true) && info.optString("ver").isNotBlank() && it.isNotBlank() && count >= expectedLeds }
    }

    fun exit(turnOff: Boolean): Boolean = request("/json/state", if (turnOff) {
        """{"live":false,"on":false}"""
    } else {
        """{"live":false}"""
    }) { true } ?: false

    private inline fun <T> request(path: String, body: String? = null, read: (HttpURLConnection) -> T): T? {
        return runCatching {
            val http = URL("http", address.hostAddress, 80, path).openConnection(Proxy.NO_PROXY) as HttpURLConnection
            try {
                http.connectTimeout = 1200
                http.readTimeout = 1200
                http.instanceFollowRedirects = false
                if (body != null) {
                    val bytes = body.toByteArray()
                    http.requestMethod = "POST"
                    http.doOutput = true
                    http.setFixedLengthStreamingMode(bytes.size)
                    http.setRequestProperty("Content-Type", "application/json")
                    http.outputStream.use { it.write(bytes) }
                }
                if (http.responseCode in 200..299) read(http) else null
            } finally {
                http.disconnect()
            }
        }.getOrNull()
    }
}
