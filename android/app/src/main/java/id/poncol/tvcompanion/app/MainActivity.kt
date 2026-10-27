package id.poncol.tvcompanion.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import id.poncol.tvcompanion.application.LightController
import id.poncol.tvcompanion.designsystem.TvUi
import id.poncol.tvcompanion.notifications.presentation.NotificationSettingsActivity
import id.poncol.tvcompanion.smarthome.LightScreen
import id.poncol.tvcompanion.tuya.TuyaLightGateway

class MainActivity : Activity() {
    private var lightController: LightController? = null
    private val main = Handler(Looper.getMainLooper())
    private var showingLight = false
    private var restoreLight = false
    private var homeFocusNotifications = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.getBoolean("light") == true ||
            (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 && intent.getBooleanExtra("open_light", false))) showLight() else showHome()
    }
    private fun releaseLight() { lightController?.close(); lightController = null; main.removeCallbacksAndMessages(null) }
    private fun showHome() {
        releaseLight(); showingLight = false
        val ui = TvUi(this)
        val root = ui.page()
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(root) }
        ui.brandHeader(root, getString(R.string.home_eyebrow))
        ui.spacer(root, 12)
        ui.text(root, getString(R.string.home_title), 36f, true)
        ui.text(root, getString(R.string.home_subtitle), 18f)
        ui.spacer(root, 22)
        val cards = ui.row().apply { gravity = android.view.Gravity.TOP; clipChildren = false }
        root.addView(cards)
        val lights = ui.featureCard(cards, id.poncol.tvcompanion.designsystem.R.drawable.ic_bulb, ui.warm,
            getString(R.string.home_light_category), getString(R.string.home_light_title),
            getString(R.string.home_light_description), getString(R.string.home_light_action)) { showLight() }
        cards.addView(android.view.View(this), android.widget.LinearLayout.LayoutParams(ui.dp(20), 1))
        val notifications = ui.featureCard(cards, id.poncol.tvcompanion.designsystem.R.drawable.ic_bell, ui.accent,
            getString(R.string.home_notifications_category), getString(R.string.home_notifications_title),
            getString(R.string.home_notifications_description), getString(R.string.home_notifications_action)) {
            homeFocusNotifications = true
            startActivity(Intent(this, NotificationSettingsActivity::class.java))
        }
        lights.setOnFocusChangeListener { _, focused -> if (focused) homeFocusNotifications = false }
        notifications.setOnFocusChangeListener { _, focused -> if (focused) homeFocusNotifications = true }
        ui.spacer(root, 20)
        ui.text(root, getString(R.string.home_hint), 13f)
        setContentView(scroll)
        (if (homeFocusNotifications) notifications else lights).requestFocus()
    }
    private fun showLight() {
        releaseLight(); showingLight = true
        val controller = LightController(TuyaLightGateway(applicationContext), { task -> main.post { task() }; Unit })
        lightController = controller
        setContentView(LightScreen(this, controller) { showHome() })
        controller.refresh()
    }
    @Deprecated("Platform Back callback for API 26+")
    override fun onBackPressed() { if (showingLight) showHome() else super.onBackPressed() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("light", showingLight); super.onSaveInstanceState(outState) }
    override fun onStop() {
        super.onStop()
        restoreLight = showingLight
        releaseLight()
        // Remove views and their observers while the foreground receiver keeps the process alive.
        setContentView(android.widget.FrameLayout(this))
    }
    override fun onRestart() { super.onRestart(); if (restoreLight) showLight() else showHome() }
    override fun onDestroy() { releaseLight(); super.onDestroy() }
}
