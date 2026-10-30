package id.poncol.tvcompanion.notifications.presentation

import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.widget.*
import id.poncol.tvcompanion.designsystem.TvUi
import id.poncol.tvcompanion.notifications.R
import id.poncol.tvcompanion.domain.NotificationEvent

/** Large urgent presentation only. The existing session still owns admission, queue and lifetime. */
internal class UrgentNotificationCard(context: Context, event: NotificationEvent, mode: String, private val dismiss: () -> Unit) : FrameLayout(context) {
    init {
        val ui = TvUi(context)
        val contentPolicy = UrgentContentPolicy.from(mode)
        setPadding(ui.dp(32), ui.dp(24), ui.dp(32), ui.dp(24))
        background = ui.surface(ui.base, ui.warm, 24, 2)
        val content = ui.column()
        addView(content, LayoutParams(-1, -1))
        ui.eyebrow(content, context.getString(R.string.urgent_label))
        ui.text(content, if (!contentPolicy.titleVisible) context.getString(R.string.urgent_private) else event.title, 32f, true).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END }
        val scroll = ScrollView(context).apply { isFillViewport = false; isFocusable = true }
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(16); bottomMargin = ui.dp(16) })
        val message = TextView(context).apply {
            text = if (!contentPolicy.titleVisible) context.getString(R.string.urgent_private_help) else if (contentPolicy.bodyVisible) event.body else context.getString(R.string.urgent_compact_help)
            textSize = 27f; setTextColor(ui.ink); setPadding(0, 0, ui.dp(12), ui.dp(12))
        }
        scroll.addView(message)
        val button = ui.button(content, context.getString(R.string.urgent_dismiss), dismiss)
        ui.text(content, context.getString(R.string.urgent_timeout), 15f)
        post { button.requestFocus() }
        contentDescription = context.getString(R.string.urgent_label)
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) dismiss()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
