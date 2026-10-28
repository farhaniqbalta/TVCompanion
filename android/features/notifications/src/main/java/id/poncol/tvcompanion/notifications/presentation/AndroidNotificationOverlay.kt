package id.poncol.tvcompanion.notifications.presentation

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import id.poncol.tvcompanion.domain.NotificationEvent
import id.poncol.tvcompanion.domain.NotificationPreferences
import id.poncol.tvcompanion.notifications.infrastructure.NotificationOverlay
import kotlin.math.min

/** Android rendering edge; keeps the established 280dp card, placement, flags and motion. */
internal class AndroidNotificationOverlay(
    private val context: Context,
    private val settings: NotificationPreferences
) : NotificationOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var card: View? = null
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun motion() = !settings.reducedMotion && ValueAnimator.areAnimatorsEnabled()
    override fun show(event: NotificationEvent, dismissed: () -> Unit) {
        check(card == null)
        val metrics = context.resources.displayMetrics
        val width = min(dp(280), metrics.widthPixels - dp(96)).coerceAtLeast(dp(200))
        val view = NotificationCard(context, event, settings.mode)
        val params = WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.END; x = dp(48); y = dp(48)
        }
        context.getSystemService(WindowManager::class.java).addView(view, params)
        card = view
        if (motion()) {
            view.alpha = 0f; view.translationY = -dp(12).toFloat()
            view.animate().alpha(1f).translationY(0f).setDuration(180).start()
        }
        main.postDelayed({
            if (card === view) {
                if (motion()) view.animate().alpha(0f).setDuration(180).withEndAction { clear(); dismissed() }.start()
                else { clear(); dismissed() }
            }
        }, min(event.durationSeconds * 1000L, (event.expiresAt - System.currentTimeMillis()).coerceAtLeast(1)))
    }
    override fun clear() {
        main.removeCallbacksAndMessages(null)
        card?.let { view ->
            view.animate().withEndAction(null).setListener(null); view.animate().cancel()
            try { context.getSystemService(WindowManager::class.java).removeView(view) } catch (_: Exception) {}
        }
        card = null
    }
}
