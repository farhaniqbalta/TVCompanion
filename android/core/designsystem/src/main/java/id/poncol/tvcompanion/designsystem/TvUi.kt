package id.poncol.tvcompanion.designsystem

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

/** Shared TV tokens and static native components. No timers, bitmap art or animation loops. */
class TvUi(private val context: Context) {
    val ink = 0xfff2f5f4.toInt()
    val muted = 0xffa7b6bb.toInt()
    val accent = 0xffa2e3d0.toInt()
    val warm = 0xffefd29a.toInt()
    val base = 0xff0e171c.toInt()
    val raised = 0xff19272e.toInt()
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun surface(color: Int = raised, stroke: Int = 0xff30424b.toInt(), radius: Int = 20, border: Int = 1) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); setStroke(dp(border), stroke)
    }
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun page() = column().apply {
        setPadding(dp(48), dp(27), dp(48), dp(27))
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(base, 0xff14282d.toInt()))
    }
    fun text(parent: LinearLayout, copy: String, size: Float = 18f, bold: Boolean = false) = TextView(context).apply {
        text = copy; textSize = size; setTextColor(if (bold) ink else muted)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
        setPadding(0, dp(3), 0, dp(9)); parent.addView(this)
    }
    fun eyebrow(parent: LinearLayout, copy: String) = text(parent, copy, 12f, true).apply {
        letterSpacing = .14f; setTextColor(accent)
    }
    fun brandHeader(parent: LinearLayout, label: String) = row().apply {
        parent.addView(this, LinearLayout.LayoutParams(-1, dp(28)))
        icon(this, R.drawable.ic_companion_mark, accent, 24).apply { imageTintList = null }
        text(this, label, 12f, true).apply {
            letterSpacing = .12f; setTextColor(accent)
            setPadding(dp(10), 0, 0, 0)
        }
    }
    fun spacer(parent: LinearLayout, height: Int) { parent.addView(View(context), LinearLayout.LayoutParams(1, dp(height))) }
    fun panel(parent: LinearLayout, title: String) = column().apply {
        setPadding(dp(24), dp(20), dp(24), dp(20)); background = surface()
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        if (title.isNotBlank()) text(this, title, 23f, true)
    }
    fun focusSurface(tint: Int = accent) = StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), surface(0xff182127.toInt(), 0xff26343b.toInt(), 14))
        addState(intArrayOf(android.R.attr.state_pressed), surface(0xff385b59.toInt(), tint, 14, 2))
        addState(intArrayOf(android.R.attr.state_focused), surface(0xff2c4549.toInt(), tint, 14, 2))
        addState(intArrayOf(android.R.attr.state_selected), surface(0xff243a3e.toInt(), 0xff52766e.toInt(), 14))
        addState(intArrayOf(), surface(0xff20323a.toInt(), 0xff30454e.toInt(), 14))
    }
    fun button(parent: LinearLayout, copy: String, action: () -> Unit) = Button(context).apply {
        id = View.generateViewId(); text = copy; textSize = 17f; isAllCaps = false
        gravity = Gravity.CENTER_VERTICAL; includeFontPadding = false
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(0xff6f818a.toInt(), ink)))
        setPadding(dp(18), dp(12), dp(18), dp(12)); minHeight = dp(52); minimumWidth = 0
        background = focusSurface(); stateListAnimator = null
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        setOnClickListener { action() }
    }
    fun icon(parent: LinearLayout, drawable: Int, tint: Int = accent, size: Int = 36) = ImageView(context).apply {
        setImageResource(drawable); imageTintList = ColorStateList.valueOf(tint)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        parent.addView(this, LinearLayout.LayoutParams(dp(size), dp(size)))
    }
    fun featureCard(parent: LinearLayout, iconRes: Int, tint: Int, category: String, title: String, description: String, action: String, onClick: () -> Unit): LinearLayout {
        val card = column().apply {
            id = View.generateViewId(); isFocusable = true; isClickable = true
            setPadding(dp(26), dp(24), dp(26), dp(22))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), surface(0xff30484b.toInt(), tint, 24, 2))
                addState(intArrayOf(android.R.attr.state_focused), surface(0xff253c40.toInt(), tint, 24, 2))
                addState(intArrayOf(), surface(0xff192a31.toInt(), 0xff34474c.toInt(), 24))
            }
            contentDescription = "$title. $description. $action"
            setOnClickListener { onClick() }
        }
        parent.addView(card, LinearLayout.LayoutParams(0, -2, 1f))
        val symbol = row()
        card.addView(symbol)
        icon(symbol, iconRes, tint, 42)
        text(symbol, category, 12f, true).apply { setTextColor(tint); setPadding(dp(16), 0, 0, 0); letterSpacing = .10f }
        spacer(card, 20)
        text(card, title, 28f, true)
        text(card, description, 17f).apply { minLines = 2 }
        spacer(card, 14)
        text(card, action, 16f, true).setTextColor(tint)
        // The whole card is one navigation target; decorative text must not duplicate TalkBack.
        for (index in 0 until card.childCount) card.getChildAt(index).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        return card
    }
}
