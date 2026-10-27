package id.poncol.tvcompanion.notifications.infrastructure
import id.poncol.tvcompanion.domain.*

import org.junit.Assert.*
import org.junit.Test
import java.net.Socket

class LocalServerTest {
    @Test fun authenticatedTransportRejectsBadRequests() {
        var calls = 0
        val server = LocalServer("012345", { calls++; EventGate.Result(202, "accepted") }, { fail("Server failed") }, port = 0)
        server.start()
        try {
            fun request(headers: String, body: String = "{}"): String = Socket("127.0.0.1", server.boundPort).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().write(("POST /api/v1/notifications HTTP/1.1\r\nHost: localhost\r\n$headers\r\n\r\n$body").toByteArray())
                val reader = socket.getInputStream().bufferedReader()
                val status = reader.readLine()
                var length = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:")) length = line.substringAfter(':').trim().toInt()
                }
                val bodyChars = CharArray(length)
                var offset = 0
                while (offset < length) {
                    val count = reader.read(bodyChars, offset, length - offset)
                    if (count < 0) break
                    offset += count
                }
                status + String(bodyChars)
            }
            assertTrue(request("Content-Length: 2\r\nContent-Type: application/json").contains("401"))
            assertTrue(request("Authorization: Bearer wrong\r\nContent-Length: 2").contains("401"))
            assertTrue(request("Authorization: Bearer 012345\r\nContent-Type: application/json\r\nContent-Length: 4097").contains("413"))
            assertTrue(request("Authorization: Bearer 012345\r\nContent-Type: text/plain\r\nContent-Length: 2").contains("415"))
            assertTrue(request("Authorization: Bearer 012345\r\nContent-Type: application/json\r\nContent-Length: 2\r\nContent-Length: 2").contains("400"))
            assertTrue(request("Authorization: Bearer 012345\r\nContent-Type: application/json\r\nContent-Length: 2").contains("202"))
            assertEquals(1, calls)
            val health = Socket("127.0.0.1", server.boundPort).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().write("GET /health HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                socket.getInputStream().bufferedReader().readText()
            }
            assertTrue(health.contains("200 OK"))
            assertTrue(health.contains("\"status\":\"ok\""))
            assertTrue(health.contains("\"app\":\"TVCompanion\""))
            assertEquals(1, calls)
        } finally { server.close() }
    }

    @Test fun oversizedHeaderIsRejectedWithoutSubmittingAnEvent() {
        var calls = 0
        val server = LocalServer("012345", { calls++; EventGate.Result(202, "accepted") }, {}, port = 0)
        server.start()
        try {
            Socket("127.0.0.1", server.boundPort).use { socket ->
                socket.soTimeout = 3000
                val prefix = "POST /api/v1/notifications HTTP/1.1\r\nX-Test: "
                socket.getOutputStream().write((prefix + "x".repeat(8192 - prefix.length)).toByteArray())
                assertTrue(socket.getInputStream().bufferedReader().readLine().contains("431"))
            }
            assertEquals(0, calls)
        } finally { server.close() }
    }
    @Test fun pauseClosesAnIncompleteConnectionAndReleasesThePort() {
        val server = LocalServer("012345", { EventGate.Result(202, "accepted") }, {}, port = 0)
        server.start()
        val port = server.boundPort
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().write("POST /api/".toByteArray())
            server.close()
            try { assertEquals(-1, socket.getInputStream().read()) }
            catch (_: java.net.SocketException) { /* Reset also confirms connection closure. */ }
        }
        val replacement = LocalServer("012345", { EventGate.Result(202, "accepted") }, {}, port = port)
        try { replacement.start(); assertEquals(port, replacement.boundPort) }
        finally { replacement.close() }
    }
}
