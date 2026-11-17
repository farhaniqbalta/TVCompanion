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

/** Largest size with the picture's aspect ratio that fits the screen; null means fill it. */
internal fun fitVideo(videoW: Int, videoH: Int, screenW: Int, screenH: Int): Pair<Int, Int>? {
    if (videoW <= 0 || videoH <= 0 || screenW <= 0 || screenH <= 0) return null
    return if (videoW.toLong() * screenH > videoH.toLong() * screenW) screenW to (screenW.toLong() * videoH / videoW).toInt()
    else (screenH.toLong() * videoW / videoH).toInt() to screenH
}
