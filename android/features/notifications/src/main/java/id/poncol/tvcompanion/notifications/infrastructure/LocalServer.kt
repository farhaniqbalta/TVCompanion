package id.poncol.tvcompanion.notifications.infrastructure

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

/** One worker, bounded input, short timeouts; no body logging and no unbounded queue. */
internal class LocalServer(private val token: String, private val receive: (String) -> EventGate.Result,
                  private val failure: () -> Unit, private val port: Int = 8766) : NotificationListener {
    private val server = ServerSocket()
    val boundPort: Int get() = server.localPort
    @Volatile private var running = true
    private val connectionLock = Any()
    private var active: Socket? = null
    override fun start() {
        server.reuseAddress = true
        server.bind(InetSocketAddress(port), 4)
        Thread({
            try {
                while (running) server.accept().use { socket ->
                    synchronized(connectionLock) {
                        if (!running) { socket.close(); return@Thread }
                        active = socket
                    }
                    try { handle(socket) } catch (_: Exception) { /* Drop malformed/timed-out connection. */ }
                    finally { synchronized(connectionLock) { active = null } }
                }
            } catch (_: Exception) { if (running) failure() }
        }, "tvcompanion-notifications").start()
    }
    override fun close() {
        running = false
        server.close()
        synchronized(connectionLock) { active?.close(); active = null }
    }
    private fun handle(socket: Socket) {
        socket.soTimeout = 2000
        val address = socket.inetAddress
        if (!address.isSiteLocalAddress && !address.isLinkLocalAddress && !address.isLoopbackAddress) return
        val input = socket.getInputStream().buffered(4096)
        // One fixed header buffer; never copy the accumulated bytes per input byte.
        val headerBytes = ByteArray(8192)
        val deadline = System.nanoTime() + 2_000_000_000L
        var configuredTimeout = 2000
        fun timeRemaining(): Boolean {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return false
            val timeout = ((remaining + 999_999) / 1_000_000).coerceAtMost(2000).toInt()
            if (timeout != configuredTimeout) { socket.soTimeout = timeout; configuredTimeout = timeout }
            return true
        }
        var count = 0
        var terminator = 0
        while (count < headerBytes.size) {
            if (!timeRemaining()) return
            val byte = input.read()
            if (byte < 0) return
            headerBytes[count++] = byte.toByte()
            terminator = (terminator shl 8) or byte
            if (count >= 4 && terminator == 0x0d0a0d0a) break
        }
        val header = String(headerBytes, 0, count, Charsets.US_ASCII)
        if (!header.endsWith("\r\n\r\n")) { reply(socket, 431, "headers_too_large"); return }
        val lines = header.split("\r\n")
        val request = lines.first().split(' ')
        if (request.size != 3 || request[2] != "HTTP/1.1") { reply(socket, 400, "bad_request"); return }
        val headers = mutableMapOf<String, String>()
        for (line in lines.drop(1).filter { it.isNotEmpty() }) {
            val colon = line.indexOf(':')
            if (colon < 1) { reply(socket, 400, "bad_header"); return }
            val key = line.substring(0, colon).lowercase()
            if (headers.put(key, line.substring(colon + 1).trim()) != null) { reply(socket, 400, "duplicate_header"); return }
        }
        if (request[0] == "GET" && request[1] == "/health") { reply(socket, 200, "ok", true); return }
        if (request[0] != "POST" || request[1] != "/api/v1/notifications") { reply(socket, 404, "not_found"); return }
        val auth = headers["authorization"].orEmpty().toByteArray()
        if (!MessageDigest.isEqual(auth, "Bearer $token".toByteArray())) { reply(socket, 401, "unauthorized"); return }
        if (headers.containsKey("transfer-encoding")) { reply(socket, 400, "unsupported_encoding"); return }
        if (headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() != "application/json") {
            reply(socket, 415, "json_required"); return
        }
        val size = headers["content-length"]?.toIntOrNull()
        if (size == null || size !in 1..4096) { reply(socket, 413, "invalid_length"); return }
        val body = ByteArray(size)
        var offset = 0
        while (offset < size) {
            if (!timeRemaining()) return
            val read = input.read(body, offset, size - offset)
            if (read < 0) return
            offset += read
        }
        val result = receive(body.toString(Charsets.UTF_8))
        reply(socket, result.code, result.status)
    }
    private fun reply(socket: Socket, code: Int, status: String, health: Boolean = false) {
        val body = (if (health) "{\"status\":\"ok\",\"app\":\"TVCompanion\"}" else "{\"status\":\"$status\"}").toByteArray()
        val reason = when (code) { 200 -> "OK"; 202 -> "Accepted"; else -> "Rejected" }
        socket.getOutputStream().write(("HTTP/1.1 $code $reason\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n").toByteArray() + body)
    }
}
