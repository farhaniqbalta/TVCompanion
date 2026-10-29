package id.poncol.tvcompanion.airplay.runtime

/** One presentation attempt per video connection; never fight Back or steal focus for audio-only. */
internal class VideoPresentationPolicy {
    private var handled = false
    fun videoRequested(keepAvailable: Boolean, displayPresent: Boolean, platformAllows: Boolean): Boolean {
        if (displayPresent) { handled = true; return false }
        if (handled || !keepAvailable || !platformAllows) return false
        handled = true
        return true
    }
    fun disconnected() { handled = false }
}
