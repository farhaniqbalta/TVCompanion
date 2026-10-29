package id.poncol.tvcompanion.tuya

import id.poncol.tvcompanion.domain.LightColor
import java.util.Locale

/** Only the observed DPS24/colour_data_v2 12-hex HSV profile, not the older RGB+HSV layout. */
internal object TuyaColorCodec {
    fun decode(value: Any?): LightColor? {
        val text = value as? String ?: return null
        if (text.length != 12 || !text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val h = text.substring(0, 4).toInt(16)
        val s = text.substring(4, 8).toInt(16)
        val v = text.substring(8, 12).toInt(16)
        if (h !in 0..360 || s !in 0..1000 || v !in 0..1000) return null
        return LightColor(h, s, v)
    }
    fun encode(color: LightColor): String = String.format(Locale.ROOT, "%04x%04x%04x", color.hue, color.saturation, color.value)
}
