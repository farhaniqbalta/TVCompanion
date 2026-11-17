package id.poncol.tvcompanion.airplay.runtime

/** Lets a default run once: the first claim wins, later claims are refused. */
class OnceGate {
    private var claimed = false
    fun claim(): Boolean = !claimed.also { claimed = true }
    /** Claims only when [allowed], so a refused permission check leaves the default for a later attempt. */
    fun claimIf(allowed: Boolean): Boolean = allowed && claim()
}
