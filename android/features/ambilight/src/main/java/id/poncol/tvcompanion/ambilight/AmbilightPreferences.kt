package id.poncol.tvcompanion.ambilight

import android.content.Context
import id.poncol.tvcompanion.ambilight.engine.LedLayout
import id.poncol.tvcompanion.ambilight.engine.LightingProfile
import java.net.InetAddress

internal fun ipv4Bytes(text: String): ByteArray? {
    val parts = text.split('.')
    if (parts.size != 4) return null
    val bytes = ByteArray(4)
    for ((index, part) in parts.withIndex()) {
        if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return null
        val octet = part.toInt()
        if (octet > 255) return null
        bytes[index] = octet.toByte()
    }
    val first = bytes[0].toInt() and 255
    val second = bytes[1].toInt() and 255
    val local = first == 10 || (first == 172 && second in 16..31) ||
        (first == 192 && second == 168) || (first == 169 && second == 254)
    return bytes.takeIf { local }
}

internal data class AmbilightConfig(
    val address: String = "",
    val layout: LedLayout = LedLayout(listOf(20, 10, 20, 10)),
    val profile: LightingProfile = LightingProfile.CINEMA,
    val brightness: Int = 50,
    val turnOff: Boolean = false,
) {
    fun controllerAddress(): InetAddress = InetAddress.getByAddress(requireNotNull(ipv4Bytes(address)))
}

internal class AmbilightPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("ambilight_v1", Context.MODE_PRIVATE)

    fun load(): AmbilightConfig = try {
        val defaults = AmbilightConfig()
        val address = prefs.getString("address", "").orEmpty()
        val counts = (0..3).map { prefs.getInt("side_$it", defaults.layout.counts[it]) }
        val layout = runCatching {
            LedLayout(counts, prefs.getInt("corner", 0), prefs.getBoolean("clockwise", true), prefs.getInt("first", 0))
        }.getOrDefault(defaults.layout)
        AmbilightConfig(
            address.takeIf { ipv4Bytes(it) != null }.orEmpty(),
            layout,
            LightingProfile.entries.getOrElse(prefs.getInt("profile", 0)) { defaults.profile },
            prefs.getInt("brightness", defaults.brightness).coerceIn(1, 100),
            prefs.getBoolean("turn_off", false),
        )
    } catch (_: ClassCastException) {
        AmbilightConfig()
    }

    fun save(config: AmbilightConfig) {
        val edit = prefs.edit()
            .putString("address", config.address)
            .putInt("corner", config.layout.startCorner)
            .putBoolean("clockwise", config.layout.clockwise)
            .putInt("first", config.layout.firstLed)
            .putInt("profile", config.profile.ordinal)
            .putInt("brightness", config.brightness)
            .putBoolean("turn_off", config.turnOff)
        config.layout.counts.forEachIndexed { index, value -> edit.putInt("side_$index", value) }
        edit.apply()
    }
}

internal fun testColors(config: AmbilightConfig): ByteArray {
    val peak = (255 * minOf(config.brightness, 25) / 100).toByte()
    val output = ByteArray(config.layout.total * 3)
    var clockwise = 0
    config.layout.counts.forEachIndexed { side, count ->
        repeat(count) {
            val index = config.layout.outputIndex(clockwise++) * 3
            if (side == 0 || side == 3) output[index] = peak
            if (side == 1 || side == 3) output[index + 1] = peak
            if (side == 2) output[index + 2] = peak
        }
    }
    return output
}
