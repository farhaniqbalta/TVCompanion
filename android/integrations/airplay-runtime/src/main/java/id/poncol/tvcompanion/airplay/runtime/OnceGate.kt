package id.poncol.tvcompanion.airplay.runtime

/** Lets a default run once: the first claim wins, later claims are refused. */
class OnceGate {
    private var claimed = false
    fun claim(): Boolean = !claimed.also { claimed = true }
}
