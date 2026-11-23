package id.poncol.tvcompanion.ambilight.engine

/**
 * Final per-send scaling of pipeline RGB into [out] at [brightness] percent. Dramatic is relative to the frame's
 * brightest LED: LEDs at least half as bright stay exactly as in Normal, dimmer ones fade by twice their ratio
 * (a quarter of the peak gets half its Normal level). One gain per LED, keyed on its brightest channel, keeps hue,
 * and a uniformly dark scene is left as is. shortcut: bright subtitles or logos in the edge band set the peak and dim
 * the rest; exclude them from the peak if that shows up in practice.
 */
fun scaleOutput(rgb: ByteArray, out: ByteArray, brightness: Int, dramatic: Boolean) {
    if (!dramatic) {
        for (i in rgb.indices) out[i] = (((rgb[i].toInt() and 255) * brightness + 50) / 100).toByte()
        return
    }
    var top = 1
    for (value in rgb) top = maxOf(top, value.toInt() and 255)
    val divisor = top * 100
    var i = 0
    while (i + 2 < rgb.size) {
        val r = rgb[i].toInt() and 255
        val g = rgb[i + 1].toInt() and 255
        val b = rgb[i + 2].toInt() and 255
        val gain = minOf(top, 2 * maxOf(r, g, b)) * brightness
        out[i] = ((r * gain + divisor / 2) / divisor).toByte()
        out[i + 1] = ((g * gain + divisor / 2) / divisor).toByte()
        out[i + 2] = ((b * gain + divisor / 2) / divisor).toByte()
        i += 3
    }
}
