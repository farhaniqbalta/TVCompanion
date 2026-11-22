package id.poncol.tvcompanion.app.control

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Fixtures are test-only identities, never used by the app:
 * `keytool -genkeypair -alias <name> -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=test-<name>"
 *  -storetype PKCS12 -keystore <name>.p12 -storepass testpass -keypass testpass` for server, client, stranger.
 */
class ControlServerTest {
    private fun store(name: String) = KeyStore.getInstance("PKCS12").apply {
        ControlServerTest::class.java.getResourceAsStream("/control/$name.p12").use { load(it, "testpass".toCharArray()) }
    }
    private fun keys(name: String) = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store(name), "testpass".toCharArray()) }.keyManagers
    private fun pkcs1(name: String) = ControlWire.pkcs1((store(name).getCertificate(name) as X509Certificate).publicKey as RSAPublicKey)
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
    }

    private val fake = FakeFeatures()
    private val paired = mutableSetOf<String>()
    private val registry = ClientRegistry({ paired.toSet() }, { paired.clear(); paired.addAll(it) })
    private var window: PairingWindow? = null
    private var pairedEvents = 0
    private var server: ControlServer? = null

    private fun start(deadlineMs: Int = 10_000): ControlServer {
        val ssl = SSLContext.getInstance("TLS").apply { init(keys("server"), arrayOf(acceptAnyClientCertificate()), null) }
        return ControlServer(ssl, pkcs1("server"), registry, ControlDispatcher(fake), { window }, { pairedEvents++ }, port = 0, deadlineMs = deadlineMs)
            .also { it.start(); server = it }
    }
    private fun connect(who: String, port: Int): SSLSocket {
        val ssl = SSLContext.getInstance("TLS").apply { init(keys(who), arrayOf(trustAll), null) }
        return (ssl.socketFactory.createSocket(InetAddress.getLoopbackAddress(), port) as SSLSocket).apply { soTimeout = 3000; startHandshake() }
    }
    private fun exchange(who: String, port: Int, request: String): JSONObject = connect(who, port).use { s ->
        ControlWire.writeFrame(s.outputStream, request.toByteArray())
        JSONObject(String(ControlWire.readFrame(s.inputStream, ControlWire.MAX_RESPONSE)))
    }

    @After fun stop() { server?.close() }

    @Test fun pairedClientGetsStatus() {
        registry.add(ControlWire.hex(ControlWire.sha256(pkcs1("client"))))
        val r = exchange("client", start().boundPort, """{"v":1,"cmd":"status"}""")
        assertEquals("ok", r.getString("outcome")); assertEquals("listening", r.getJSONObject("status").getJSONObject("notifications").getString("state"))
    }
    @Test fun unpairedClientIsDeniedWithoutFeatureCalls() {
        registry.add(ControlWire.hex(ControlWire.sha256(pkcs1("client"))))
        val r = exchange("stranger", start().boundPort, """{"v":1,"cmd":"light.set","on":false}""")
        assertEquals("denied", r.getString("outcome")); assertFalse(r.has("status")); assertTrue(fake.calls.isEmpty())
    }
    @Test fun pairingWithCorrectProofRegistersClient() {
        val port = start().boundPort
        window = PairingWindow({ 0L })
        val proof = ControlWire.proof(window!!.code, pkcs1("client"), pkcs1("server"))
        assertEquals("ok", exchange("client", port, """{"v":1,"cmd":"pair","proof":"$proof"}""").getString("outcome"))
        assertEquals(1, pairedEvents)
        assertEquals("ok", exchange("client", port, """{"v":1,"cmd":"status"}""").getString("outcome"))
    }
    @Test fun wrongProofIsDeniedAndNotRegistered() {
        val port = start().boundPort
        window = PairingWindow({ 0L })
        assertEquals("denied", exchange("client", port, """{"v":1,"cmd":"pair","proof":"${"0".repeat(64)}"}""").getString("outcome"))
        assertTrue(registry.isEmpty); assertEquals(0, pairedEvents)
        assertEquals("denied", exchange("client", port, """{"v":1,"cmd":"status"}""").getString("outcome"))
    }
    @Test fun malformedRequestIsInvalidWithoutStatus() {
        registry.add(ControlWire.hex(ControlWire.sha256(pkcs1("client"))))
        val r = exchange("client", start().boundPort, """{"v":1,"cmd":"toggle"}""")
        assertEquals("invalid", r.getString("outcome")); assertFalse(r.has("status"))
    }
    @Test fun thirdConcurrentConnectionIsClosed() {
        val port = start(deadlineMs = 3000).boundPort
        val held = listOf(connect("client", port), connect("client", port))
        Thread.sleep(200)
        val started = System.nanoTime()
        val refused = runCatching { connect("client", port).use { it.inputStream.read() } }
        assertTrue(refused.isFailure || refused.getOrNull() == -1)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1500)
        held.forEach { it.close() }
    }
    @Test fun slowClientIsCutAtDeadline() {
        registry.add(ControlWire.hex(ControlWire.sha256(pkcs1("client"))))
        val port = start(deadlineMs = 300).boundPort
        val started = System.nanoTime()
        Socket(InetAddress.getLoopbackAddress(), port).use { raw ->
            raw.soTimeout = 3000
            // The server may send a TLS alert before closing; what matters is that the stream ends.
            val drained = runCatching { val input = raw.getInputStream(); while (input.read() >= 0) Unit }
            assertTrue(drained.isSuccess || drained.exceptionOrNull() !is java.net.SocketTimeoutException)
        }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1500)
        Thread.sleep(100)
        assertEquals("ok", exchange("client", port, """{"v":1,"cmd":"status"}""").getString("outcome"))
    }
    @Test fun registryKeepsAtMostFourClients() {
        (1..5).forEach { registry.add("hash$it") }
        assertFalse(registry.isPaired("hash1")); assertTrue(registry.isPaired("hash5")); assertEquals(4, paired.size)
    }
}
