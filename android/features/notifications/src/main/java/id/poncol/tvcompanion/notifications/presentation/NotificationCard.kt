package id.poncol.tvcompanion.notifications.presentation

import id.poncol.tvcompanion.domain.NotificationEvent
import id.poncol.tvcompanion.notifications.R

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.*
import java.util.UUID

object NotificationThemes {
    data class Theme(val name: String, val accent: Int, val icon: Int, val title: String, val body: String)
    val all = linkedMapOf(
        "grab" to Theme("Grab", Color.rgb(66,199,131), R.drawable.ic_scooter, "Driver arriving soon", "Your order is near your location."),
        "shopeefood" to Theme("ShopeeFood", Color.rgb(255,149,91), R.drawable.ic_food_bag, "Your order is on the way", "A rider is heading to you."),
        "gojek" to Theme("Gojek", Color.rgb(86,201,186), R.drawable.ic_scooter, "Driver nearby", "Please be ready for pickup."),
        "alert" to Theme("Alert", Color.rgb(243,196,108), R.drawable.ic_alert, "Emergency!", "Your snacks have mysteriously disappeared.")
    )
    fun demo(source: String, duration: Int): NotificationEvent {
        val theme = all.getValue(source)
        val now = System.currentTimeMillis()
        return NotificationEvent(UUID.randomUUID().toString(), source, theme.title, theme.body, now, now + 120_000, duration)
    }
}

class NotificationCard(context: Context, event: NotificationEvent, mode: String) : LinearLayout(context) {
    init {
        val theme = NotificationThemes.all.getValue(event.source)
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        orientation = HORIZONTAL; gravity = Gravity.TOP
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = GradientDrawable().apply {
            setColor(Color.argb(235,29,39,50)); cornerRadius = dp(10).toFloat()
            setStroke(dp(1), Color.argb(110, Color.red(theme.accent), Color.green(theme.accent), Color.blue(theme.accent)))
        }
        elevation = dp(8).toFloat()
        addView(ImageView(context).apply {
            setImageResource(theme.icon); setPadding(dp(5), dp(5), dp(5), dp(5))
            background = GradientDrawable().apply { setColor(Color.argb(65, Color.red(theme.accent), Color.green(theme.accent), Color.blue(theme.accent))); cornerRadius = dp(10).toFloat() }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(8) })
        val copy = LinearLayout(context).apply { orientation = VERTICAL }
        addView(copy, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        fun text(value: String, size: Float, color: Int, lines: Int, bold: Boolean = false) = TextView(context).apply {
            text = value; textSize = size; setTextColor(color); maxLines = lines
            ellipsize = TextUtils.TruncateAt.END
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text(theme.name, 12f, Color.rgb(247,248,250), 1), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val age = ((System.currentTimeMillis() - event.receivedAt).coerceAtLeast(0) / 1000)
        header.addView(text(if (age < 60) "Just now" else "${age / 60} min ago", 10f, Color.rgb(180,194,208), 1))
        copy.addView(header)
        val privacy = mode == "privacy"
        copy.addView(text(if (privacy) "Delivery update" else event.title, 16f, Color.rgb(247,248,250), 2, true), LayoutParams(-1,-2).apply { topMargin = dp(3) })
        if (mode == "expanded" && event.body.isNotBlank()) copy.addView(
            text(event.body, 13f, Color.rgb(180,194,208), 1), LayoutParams(-1,-2).apply { topMargin = dp(2) })
    }
}
