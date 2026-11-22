package id.poncol.tvcompanion.app.control

import java.security.MessageDigest
import java.security.SecureRandom

/** One pairing attempt window: a 6-digit code, 120 s, at most 3 wrong proofs, closed by the first success. */
class PairingWindow(private val now: () -> Long, random: SecureRandom = SecureRandom()) {
    val code: String = "%06d".format(random.nextInt(1_000_000))
    private val opened = now()
    private var failures = 0
    private var done = false

    val open: Boolean @Synchronized get() = !done && failures < MAX_FAILURES && now() - opened <= LIFETIME_MS

    @Synchronized fun attempt(proof: String, clientPkcs1: ByteArray, serverPkcs1: ByteArray): Boolean {
        if (!open) return false
        val expected = ControlWire.proof(code, clientPkcs1, serverPkcs1)
        val ok = MessageDigest.isEqual(expected.toByteArray(), proof.toByteArray())
        if (ok) done = true else failures++
        return ok
    }

    companion object {
        const val LIFETIME_MS = 120_000L
        private const val MAX_FAILURES = 3
    }
}
