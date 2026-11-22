package id.poncol.tvcompanion.app.control

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import id.poncol.tvcompanion.app.R
import id.poncol.tvcompanion.designsystem.TvUi

/** Shows a short-lived code that the iPhone enters to pair for Quick Controls. */
class ControlPairingActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var code: TextView
    private lateinit var detail: TextView
    private lateinit var again: Button
    private lateinit var forget: Button
    private var listening: AutoCloseable? = null
    private var openedAt = 0L
    private val tick = object : Runnable { override fun run() { render(); main.postDelayed(this, 1000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = TvUi(this)
        val root = ui.page()
        ui.brandHeader(root, getString(R.string.controls_eyebrow))
        ui.spacer(root, 12)
        ui.text(root, getString(R.string.controls_title), 34f, true)
        ui.text(root, getString(R.string.controls_instructions), 18f)
        ui.spacer(root, 16)
        code = ui.text(root, "", 64f, true).apply { letterSpacing = 0.2f }
        detail = ui.text(root, "", 18f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        again = ui.button(root, getString(R.string.controls_new_code)) { requestPairing() }
        forget = ui.button(root, getString(R.string.controls_forget)) {
            startForegroundService(Intent(this, ControlService::class.java).setAction(ControlService.ACTION_FORGET))
        }
        ui.button(root, getString(R.string.controls_back)) { finish() }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
    }

    override fun onStart() {
        super.onStart()
        openedAt = SystemClock.elapsedRealtime()
        listening = ControlPairing.listen(::render)
        requestPairing()
        main.post(tick)
    }

    override fun onStop() {
        listening?.close(); listening = null
        main.removeCallbacksAndMessages(null)
        super.onStop()
    }

    private fun requestPairing() {
        try { startForegroundService(Intent(this, ControlService::class.java).setAction(ControlService.ACTION_PAIR)) }
        catch (_: RuntimeException) { detail.setText(R.string.controls_failed) }
    }

    private fun render() {
        val window = ControlPairing.window
        val paired = ControlPairing.pairedAt >= openedAt
        when {
            ControlPairing.failed -> { code.text = ""; detail.setText(R.string.controls_failed) }
            paired -> { code.text = "✓"; detail.setText(R.string.controls_paired) }
            window != null && window.open -> {
                code.text = window.code.chunked(3).joinToString(" ")
                detail.text = getString(R.string.controls_expires)
            }
            else -> { code.text = "––– –––"; detail.setText(R.string.controls_expired) }
        }
        code.contentDescription = window?.code?.toList()?.joinToString(" ")
        again.visibility = if (window?.open == true || ControlPairing.failed) View.GONE else View.VISIBLE
        forget.visibility = if (ControlService.hasPairedClients(this)) View.VISIBLE else View.GONE
    }
}
