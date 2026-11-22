package id.poncol.tvcompanion.ambilight.engine

/** Sides are top/right/bottom/left, starting at the top-left seam. */
class LedLayout(
    counts: List<Int>,
    val startCorner: Int = 0,
    val clockwise: Boolean = true,
    val firstLed: Int = 0,
) {
    val counts = counts.toList()
    val total: Int

    init {
        require(this.counts.size == 4 && this.counts.all { it in 0..MAX_LEDS })
        total = this.counts.sum()
        require(total in 1..MAX_LEDS && startCorner in 0..3 && firstLed in 0 until total)
    }

    private val seam = this.counts.take(startCorner).sum()
    fun outputIndex(clockwiseIndex: Int): Int = Math.floorMod(
        firstLed + if (clockwise) clockwiseIndex - seam else seam - 1 - clockwiseIndex,
        total,
    )

    companion object {
        const val MAX_LEDS = 4096
    }
}
