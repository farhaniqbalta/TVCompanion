package id.poncol.tvcompanion.app.control

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

/** The TV's control identity: a non-exportable AndroidKeyStore RSA-2048 key (iOS pins RSA keys only). */
object ControlKeys {
    private const val ALIAS = "tvcompanion_controls_v2"
    private const val OLD_ALIAS = "tvcompanion_controls_v1"  // Lacked the raw-RSA purpose Conscrypt needs; never usable.

    /** TLS context for the listener plus the server key in PKCS#1 form, for pairing proofs. */
    fun context(): Pair<SSLContext, ByteArray> {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(OLD_ALIAS)) store.deleteEntry(OLD_ALIAS)
        if (!store.containsAlias(ALIAS)) {
            val day = 86_400_000L
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                // Conscrypt signs TLS handshakes with raw RSA through the keystore Cipher path, so DECRYPT + no padding is required too.
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(2048)
                    // NONE lets TLS 1.2 sign its own digest; PSS is required by TLS 1.3.
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE, KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                    .setCertificateSubject(X500Principal("CN=TVCompanion Controls"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(Date(System.currentTimeMillis() - day))
                    .setCertificateNotAfter(Date(System.currentTimeMillis() + 30 * 365 * day))
                    .build())
            }.generateKeyPair()
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, null) }
        val ssl = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, arrayOf(acceptAnyClientCertificate()), null) }
        val certificate = store.getCertificate(ALIAS) as X509Certificate
        return ssl to ControlWire.pkcs1(certificate.publicKey as RSAPublicKey)
    }
}
