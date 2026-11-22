package id.poncol.tvcompanion.ambilight.engine

import kotlin.math.pow
import kotlin.math.roundToInt

/** Dramatic peak curve v·(v/255)^0.7, ×256 fixed point: 255 stays 255, 128→79, 64→25, 32→8. */
private val dramaticPeak = IntArray(256) { v -> (v * (v / 255.0).pow(0.7) * 256).roundToInt() }

/**
 * Final per-send scaling of pipeline RGB into [out] at [brightness] percent. Dramatic dims each LED by one gain taken
 * from its brightest channel, so hue and saturation hold and saturated colours are not mistaken for dark ones.
 */
fun scaleOutput(rgb: ByteArray, out: ByteArray, brightness: Int, dramatic: Boolean) {
    if (!dramatic) {
        for (i in rgb.indices) out[i] = (((rgb[i].toInt() and 255) * brightness + 50) / 100).toByte()
        return
    }
    var i = 0
    while (i + 2 < rgb.size) {
        val r = rgb[i].toInt() and 255
        val g = rgb[i + 1].toInt() and 255
        val b = rgb[i + 2].toInt() and 255
        val peak = maxOf(r, g, b)
        if (peak == 0) {
            out[i] = 0; out[i + 1] = 0; out[i + 2] = 0
        } else {
            val gain = dramaticPeak[peak] * brightness
            val divisor = peak * 25_600
            out[i] = ((r * gain + divisor / 2) / divisor).toByte()
            out[i + 1] = ((g * gain + divisor / 2) / divisor).toByte()
            out[i + 2] = ((b * gain + divisor / 2) / divisor).toByte()
        }
        i += 3
    }
}
