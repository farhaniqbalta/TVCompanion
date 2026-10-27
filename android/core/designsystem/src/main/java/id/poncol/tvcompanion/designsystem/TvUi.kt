package id.poncol.tvcompanion.designsystem

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.widget.*

/** Native TV primitives. Static surfaces, explicit focus, no background animation. */
class TvUi(private val context: Context) {
    val ink = 0xffedf3f6.toInt()
    val muted = 0xff9cacba.toInt()
    val accent = 0xff93dcc9.toInt()
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun surface(color: Int = 0xff172531.toInt(), stroke: Int = 0xff2c414e.toInt()) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(16).toFloat(); setStroke(dp(1), stroke)
    }
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun page(): LinearLayout = column().apply {
        setPadding(dp(48), dp(30), dp(48), dp(30))
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xff0b1721.toInt(), 0xff142c32.toInt()))
    }
    fun text(parent: LinearLayout, copy: String, size: Float = 18f, bold: Boolean = false): TextView = TextView(context).apply {
        text = copy; textSize = size; setTextColor(if (bold) ink else muted)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(12)); parent.addView(this)
    }
    fun panel(parent: LinearLayout, title: String): LinearLayout = column().apply {
        setPadding(dp(24), dp(20), dp(24), dp(20)); background = surface()
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
        text(this, title, 24f, true)
    }
    fun button(parent: LinearLayout, copy: String, action: () -> Unit): Button = Button(context).apply {
        text = copy; textSize = 17f; isAllCaps = false; gravity = Gravity.CENTER_VERTICAL
        setTextColor(ink); setPadding(dp(18), dp(12), dp(18), dp(12)); minHeight = dp(56)
        background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), surface(0xff17232c.toInt(), 0xff21313a.toInt()))
            addState(intArrayOf(android.R.attr.state_focused), surface(0xff284b50.toInt(), accent))
            addState(intArrayOf(android.R.attr.state_pressed), surface(0xff284b50.toInt(), accent))
            addState(intArrayOf(), surface(0xff203440.toInt()))
        }
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        setOnClickListener { action() }
    }
}
