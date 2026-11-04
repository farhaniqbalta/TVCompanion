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
        val urgent = event.source == "alert2"
        lateinit var view: View
        fun finish() { if (card === view) { clear(); dismissed() } }
        view = if (urgent) UrgentNotificationCard(context, event, settings.mode, ::finish) else NotificationCard(context, event, settings.mode)
        val params = WindowManager.LayoutParams(if (urgent) (metrics.widthPixels * .9f).toInt() else width,
            if (urgent) (metrics.heightPixels * .86f).toInt() else WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            if (urgent) WindowManager.LayoutParams.FLAG_DIM_BEHIND else
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT).apply {
            gravity = if (urgent) Gravity.CENTER else Gravity.TOP or Gravity.END
            x = if (urgent) 0 else dp(48); y = if (urgent) 0 else dp(48); dimAmount = .65f
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
