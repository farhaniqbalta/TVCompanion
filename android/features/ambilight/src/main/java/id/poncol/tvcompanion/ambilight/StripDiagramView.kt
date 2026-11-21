package id.poncol.tvcompanion.ambilight

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import id.poncol.tvcompanion.ambilight.engine.LedLayout
import kotlin.math.hypot

/** Clockwise position (top left → right, then down, back, up) of a physical strip LED. */
internal fun clockwiseIndexOf(layout: LedLayout, physical: Int): Int =
    (0 until layout.total).first { layout.outputIndex(it) == physical }

/** Static drawing of the layout: edge LEDs in their test colours, a ring on LED 0 and an arrow toward LED 1. */
internal class StripDiagramView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var layout: LedLayout = LedLayout(listOf(1, 0, 0, 0))
        set(value) {
            field = value
            positions = FloatArray(value.total * 2)
            contentDescription = describe(value)
            invalidate()
        }
    private var positions = FloatArray(2)

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val screen = RectF()

    private fun describe(layout: LedLayout): String {
        val (top, right, bottom, left) = layout.counts
        return context.getString(R.string.ambilight_diagram_description, top, right, bottom, left) + " " +
            resources.getQuantityString(R.plurals.ambilight_total, layout.total, layout.total)
    }

    override fun onDraw(canvas: Canvas) {
        val gap = 14 * density
        val margin = gap + 18 * density
        val availableW = width - 2 * margin
        val availableH = height - 2 * margin
        val screenW = minOf(availableW, availableH * 16 / 9)
        val screenH = screenW * 9 / 16
        screen.set((width - screenW) / 2, (height - screenH) / 2, (width + screenW) / 2, (height + screenH) / 2)

        fill.color = 0xff0e171c.toInt()
        canvas.drawRoundRect(screen, 8 * density, 8 * density, fill)
        stroke.color = 0xff6f818a.toInt(); stroke.strokeWidth = 1.8f * density
        canvas.drawRoundRect(screen, 8 * density, 8 * density, stroke)

        val counts = layout.counts
        var index = 0
        for (side in 0..3) for (i in 0 until counts[side]) {
            val t = (i + 0.5f) / counts[side]
            val x = when (side) { 0 -> screen.left + t * screenW; 1 -> screen.right + gap; 2 -> screen.right - t * screenW; else -> screen.left - gap }
            val y = when (side) { 0 -> screen.top - gap; 1 -> screen.top + t * screenH; 2 -> screen.bottom + gap; else -> screen.bottom - t * screenH }
            positions[index * 2] = x
            positions[index * 2 + 1] = y
            index++
            fill.color = EDGE_COLORS[side]
            canvas.drawCircle(x, y, 2.6f * density, fill)
        }

        text.textSize = 17 * density
        text.isFakeBoldText = true
        val inset = 24 * density
        val baseline = text.textSize / 3
        drawCount(canvas, counts[0], 0, screen.centerX(), screen.top + inset + baseline)
        drawCount(canvas, counts[1], 1, screen.right - inset, screen.centerY() + baseline)
        drawCount(canvas, counts[2], 2, screen.centerX(), screen.bottom - inset + baseline)
        drawCount(canvas, counts[3], 3, screen.left + inset, screen.centerY() + baseline)
        text.color = 0xfff2f5f4.toInt(); text.textSize = 32 * density
        canvas.drawText(layout.total.toString(), screen.centerX(), screen.centerY() + 4 * density, text)
        text.isFakeBoldText = false; text.color = 0xffa7b6bb.toInt(); text.textSize = 14 * density
        canvas.drawText(context.getString(R.string.ambilight_diagram_leds), screen.centerX(), screen.centerY() + 24 * density, text)

        val first = clockwiseIndexOf(layout, 0) * 2
        val firstX = positions[first]
        val firstY = positions[first + 1]
        stroke.color = 0xfff2f5f4.toInt(); stroke.strokeWidth = 2f * density
        canvas.drawCircle(firstX, firstY, 6.5f * density, stroke)
        if (layout.total > 1) {
            val next = clockwiseIndexOf(layout, 1) * 2
            val length = hypot(positions[next] - firstX, positions[next + 1] - firstY).coerceAtLeast(0.01f)
            val dx = (positions[next] - firstX) / length
            val dy = (positions[next + 1] - firstY) / length
            // Draw the arrow just outside the strip, on the side away from the screen.
            val outX = if (firstX < screen.left) -1f else if (firstX > screen.right) 1f else 0f
            val outY = if (firstY < screen.top) -1f else if (firstY > screen.bottom) 1f else 0f
            val sx = firstX + outX * 13 * density
            val sy = firstY + outY * 13 * density
            val ex = sx + dx * 26 * density
            val ey = sy + dy * 26 * density
            canvas.drawLine(sx, sy, ex, ey, stroke)
            val head = 6 * density
            canvas.drawLine(ex, ey, ex - dx * head - dy * head, ey - dy * head + dx * head, stroke)
            canvas.drawLine(ex, ey, ex - dx * head + dy * head, ey - dy * head - dx * head, stroke)
        }
    }

    private fun drawCount(canvas: Canvas, count: Int, side: Int, x: Float, y: Float) {
        text.color = if (count == 0) 0xff6f818a.toInt() else EDGE_COLORS[side]
        canvas.drawText(count.toString(), x, y, text)
    }

    private companion object {
        // Matches testColors(): red top, green right, blue bottom, yellow left, lightened for the dark surface.
        val EDGE_COLORS = intArrayOf(0xffff8a8a.toInt(), 0xff7fe0a0.toInt(), 0xff7fb2ff.toInt(), 0xfff5d76e.toInt())
    }
}
