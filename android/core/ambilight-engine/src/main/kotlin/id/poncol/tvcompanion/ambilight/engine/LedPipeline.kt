package id.poncol.tvcompanion.ambilight.engine

import kotlin.math.exp
import kotlin.math.roundToInt

/** Styles set only how quickly colours follow the picture; brightness is a separate user setting. */
enum class LightingProfile(internal val tauMs: Double) {
    CINEMA(95.0), RESPONSIVE(45.0), SOFT(190.0)
}

class LedPipeline(
    private val width: Int,
    private val height: Int,
    layout: LedLayout,
    private val profile: LightingProfile,
    brightness: Int,
) {
    private class Side(val length: Int, val depth: Int, count: Int) {
        val averages = IntArray(length * 3)
        val left = IntArray(count)
        val right = IntArray(count)
        val start = DoubleArray(count)
        val end = DoubleArray(count)
        val fraction = DoubleArray(count)
        val output = IntArray(count)

        init {
            for (zone in 0 until count) {
                if (count < length) {
                    start[zone] = zone.toDouble() * length / count
                    end[zone] = (zone + 1.0) * length / count
                    left[zone] = start[zone].toInt()
                    right[zone] = (kotlin.math.ceil(end[zone]).toInt() - 1).coerceAtMost(length - 1)
                    continue
                }
                val center = ((zone + 0.5) * length / count - 0.5).coerceIn(0.0, length - 1.0)
                left[zone] = center.toInt()
                right[zone] = minOf(left[zone] + 1, length - 1)
                fraction[zone] = center - left[zone]
            }
        }
    }

    private val sides = Array(4) { side ->
        val along = if (side % 2 == 0) width else height
        val across = if (side % 2 == 0) height else width
        Side(along, (across * 0.12).roundToInt().coerceAtLeast(1), layout.counts[side])
    }
    private val samples = sides.sumOf { if (it.output.isEmpty()) 0 else it.length * it.depth }
    private val smoothed = DoubleArray(layout.total * 3)
    private val scale = brightness / 100.0
    private var previous = Long.MIN_VALUE

    init {
        require(width > 0 && height > 0 && brightness in 1..100)
        var index = 0
        for (side in sides) for (zone in side.output.indices) {
            side.output[zone] = layout.outputIndex(index++) * 3
        }
    }

    /** Updates RGB output and returns whether all sampled edges are nearly black. */
    fun process(pixels: PixelAccess, timestampNs: Long, output: ByteArray): Boolean {
        require(pixels.width == width && pixels.height == height && output.size == smoothed.size)
        val last = previous
        previous = timestampNs
        val alpha = if (last == Long.MIN_VALUE) 1.0 else
            1 - exp(-(timestampNs - last).coerceIn(0L, 250_000_000L) / (profile.tauMs * 1_000_000))
        var brightSamples = 0
        for (direction in sides.indices) {
            val side = sides[direction]
            if (side.output.isEmpty()) continue
            for (p in 0 until side.length) {
                var red = 0
                var green = 0
                var blue = 0
                for (d in 0 until side.depth) {
                    val rgb = when (direction) {
                        0 -> pixels.rgb(p, d)
                        1 -> pixels.rgb(width - 1 - d, p)
                        2 -> pixels.rgb(width - 1 - p, height - 1 - d)
                        else -> pixels.rgb(d, height - 1 - p)
                    }
                    val r = rgb shr 16
                    val g = (rgb shr 8) and 255
                    val b = rgb and 255
                    if (maxOf(r, g, b) > 12) brightSamples++
                    red += r
                    green += g
                    blue += b
                }
                side.averages[p * 3] = red
                side.averages[p * 3 + 1] = green
                side.averages[p * 3 + 2] = blue
            }
            for (zone in side.output.indices) {
                val left = side.left[zone]
                val right = side.right[zone]
                for (channel in 0..2) {
                    val a = side.averages[left * 3 + channel]
                    val b = side.averages[right * 3 + channel]
                    val zoneAverage = if (side.end[zone] == 0.0) {
                        a + (b - a) * side.fraction[zone]
                    } else {
                        val start = side.start[zone]
                        val end = side.end[zone]
                        var weighted = 0.0
                        for (p in left..right) {
                            val weight = minOf(end, p + 1.0) - maxOf(start, p.toDouble())
                            weighted += weight * side.averages[p * 3 + channel]
                        }
                        weighted / (end - start)
                    }
                    val target = zoneAverage / side.depth * scale
                    val index = side.output[zone] + channel
                    smoothed[index] += (target - smoothed[index]) * alpha
                    output[index] = smoothed[index].roundToInt().toByte()
                }
            }
        }
        return brightSamples <= samples / 100
    }
}

class FrameCadence(fps: Int) {
    private val period = 1_000_000_000L / fps.also { require(it in 1..30) }
    private var next = Long.MIN_VALUE

    fun reset() { next = Long.MIN_VALUE }

    fun accept(nowNs: Long): Boolean {
        if (next == Long.MIN_VALUE || nowNs >= next + period) next = nowNs
        if (nowNs < next - 1_000_000L) return false
        next += period
        return true
    }
}
