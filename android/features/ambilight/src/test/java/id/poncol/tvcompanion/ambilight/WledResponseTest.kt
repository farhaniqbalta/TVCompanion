package id.poncol.tvcompanion.ambilight

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class WledResponseTest {
    @Test fun readsContentLengthAndStopsAtEndOfResponse() {
        val raw = "HTTP/1.1 200 OK\r\ncontent-length: 2\r\n\r\n{}trailing".byteInputStream()
        assertArrayEquals("{}".toByteArray(), readWledResponse(raw, true))
    }

    @Test fun rejectsRedirectsAndInvalidLengths() {
        for (raw in listOf(
            "HTTP/1.1 302 Found\r\nLocation: http://localhost\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 999999\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\n{}",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Length: 2\r\n\r\n{}",
        )) assertThrows(raw, IOException::class.java) { readWledResponse(raw.byteInputStream(), true) }
    }
}
