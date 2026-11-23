package id.poncol.tvcompanion.ambilight.engine

/**
 * Final per-send scaling of pipeline RGB into [out] at [brightness] percent. [contrast] (0–100) dims LEDs relative to
 * the frame's brightest one: LEDs at least [contrast]% as bright stay exactly as at 0, dimmer ones fade in proportion
 * (50: a quarter of the peak gets half its level; 100: output ∝ level²/peak). One gain per LED, keyed on its brightest
 * channel, keeps hue, and a uniformly dark scene is left as is. shortcut: bright subtitles or logos in the edge band
 * set the peak and dim the rest; exclude them from the peak if that shows up in practice.
 */
fun scaleOutput(rgb: ByteArray, out: ByteArray, brightness: Int, contrast: Int) {
    if (contrast <= 0) {
        for (i in rgb.indices) out[i] = (((rgb[i].toInt() and 255) * brightness + 50) / 100).toByte()
        return
    }
    var top = 1
    for (value in rgb) top = maxOf(top, value.toInt() and 255)
    val knee = top * minOf(contrast, 100)
    val divisor = knee * 100
    var i = 0
    while (i + 2 < rgb.size) {
        val r = rgb[i].toInt() and 255
        val g = rgb[i + 1].toInt() and 255
        val b = rgb[i + 2].toInt() and 255
        val gain = minOf(knee, 100 * maxOf(r, g, b)) * brightness
        out[i] = ((r * gain + divisor / 2) / divisor).toByte()
        out[i + 1] = ((g * gain + divisor / 2) / divisor).toByte()
        out[i + 2] = ((b * gain + divisor / 2) / divisor).toByte()
        i += 3
    }
}
