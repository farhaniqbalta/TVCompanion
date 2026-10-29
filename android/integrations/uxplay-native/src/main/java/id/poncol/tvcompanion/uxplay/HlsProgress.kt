package id.poncol.tvcompanion.uxplay

import java.net.URI

/** Receiver-local request bounds. Native proxy URLs and direct HTTPS content only; never logged. */
internal object HlsRequestPolicy {
    fun accepts(location: String, proxyPort: Int, start: Double): Boolean {
        if (location.length !in 1..8192 || !start.isFinite() || start !in 0.0..604800.0) return false
        val uri = try { URI(location) } catch (_: Exception) { return false }
        if (uri.rawUserInfo != null || uri.rawFragment != null || uri.host.isNullOrBlank()) return false
        if (uri.scheme.equals("https", true)) return true
        return uri.scheme.equals("http", true) && uri.host in listOf("localhost", "127.0.0.1", "[::1]") &&
            proxyPort in 1..65535 && uri.port == proxyPort
    }
}

/** Immutable progress estimate for synchronous native queries without blocking the player thread. */
internal data class HlsProgress(
    val duration: Double = 0.0, val position: Double = 0.0, val rate: Double = 0.0,
    val ready: Boolean = false, val buffering: Boolean = true, val ended: Boolean = false,
    val sampledNs: Long = 0
) {
    fun values(nowNs: Long): DoubleArray {
        val elapsed = (nowNs - sampledNs).coerceAtLeast(0) / 1_000_000_000.0
        val estimate = if (ready && !buffering && !ended) position + elapsed * rate else position
        val current = if (duration > 0) estimate.coerceIn(0.0, duration) else estimate.coerceAtLeast(0.0)
        return doubleArrayOf(duration, current, rate, if (ready) 1.0 else 0.0, if (buffering) 1.0 else 0.0, if (ended) 1.0 else 0.0)
    }
}
