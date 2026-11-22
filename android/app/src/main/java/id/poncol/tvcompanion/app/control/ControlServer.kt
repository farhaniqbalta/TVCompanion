package id.poncol.tvcompanion.app.control

import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** Trust is decided by the paired-client registry after the handshake, never by a CA chain. */
fun acceptAnyClientCertificate(): X509TrustManager = object : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty() || chain[0].publicKey !is RSAPublicKey) throw CertificateException("RSA client certificate required")
    }
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("server role only")
    override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
}

/** Mutual-TLS listener: one request per connection, two at a time, hard deadline, nothing logged. */
class ControlServer(
    private val ssl: SSLContext,
    private val serverPkcs1: ByteArray,
    private val registry: ClientRegistry,
    private val dispatcher: ControlDispatcher,
    private val pairing: () -> PairingWindow?,
    private val onPaired: () -> Unit,
    private val port: Int = ControlWire.PORT,
    private val deadlineMs: Int = 10_000,
) : AutoCloseable {
    private val server = ssl.serverSocketFactory.createServerSocket() as SSLServerSocket
    private val workers = Executors.newCachedThreadPool { Thread(it, "tvcompanion-controls") }
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "tvcompanion-controls-deadline") }
    private val active = AtomicInteger()
    private val open = Collections.synchronizedSet(mutableSetOf<Socket>())
    @Volatile private var running = true
    val boundPort: Int get() = server.localPort

    fun start() {
        server.needClientAuth = true
        server.enabledProtocols = server.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        server.reuseAddress = true
        server.bind(InetSocketAddress(port), 8)
        Thread({
            while (running) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                if (active.incrementAndGet() > MAX_CONNECTIONS) { active.decrementAndGet(); runCatching { socket.close() }; continue }
                open += socket
                val cut = timer.schedule({ runCatching { socket.close() } }, deadlineMs.toLong(), TimeUnit.MILLISECONDS)
                try {
                    workers.execute {
                        try { handle(socket as SSLSocket) } catch (_: Exception) { /* Dropped quietly; never log peers or bodies. */ }
                        finally { cut.cancel(false); runCatching { socket.close() }; open -= socket; active.decrementAndGet() }
                    }
                } catch (_: Exception) { cut.cancel(false); runCatching { socket.close() }; open -= socket; active.decrementAndGet() }
            }
        }, "tvcompanion-controls-accept").start()
    }

    private fun handle(socket: SSLSocket) {
        val peer = socket.inetAddress
        if (!peer.isSiteLocalAddress && !peer.isLinkLocalAddress && !peer.isLoopbackAddress) return
        socket.soTimeout = deadlineMs
        socket.startHandshake()
        val clientKey = socket.session.peerCertificates.first().publicKey as RSAPublicKey
        val clientPkcs1 = ControlWire.pkcs1(clientKey)
        val clientHash = ControlWire.hex(ControlWire.sha256(clientPkcs1))
        val command = ControlWire.parse(ControlWire.readFrame(socket.inputStream))
        val reply = when {
            command == null -> ControlWire.response(Outcome.INVALID.wire, null)
            command is ControlCommand.Pair ->
                if (pairing()?.attempt(command.proof, clientPkcs1, serverPkcs1) == true) {
                    registry.add(clientHash); onPaired()
                    ControlWire.response(Outcome.OK.wire, null)
                } else ControlWire.response(DENIED, null)
            !registry.isPaired(clientHash) -> ControlWire.response(DENIED, null)
            else -> dispatcher.handle(command)
        }
        ControlWire.writeFrame(socket.outputStream, reply)
    }

    override fun close() {
        running = false
        runCatching { server.close() }
        synchronized(open) { open.forEach { runCatching { it.close() } } }
        workers.shutdownNow(); timer.shutdownNow()
    }

    private companion object {
        const val MAX_CONNECTIONS = 2
        const val DENIED = "denied"
    }
}
