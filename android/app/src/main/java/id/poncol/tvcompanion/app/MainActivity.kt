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
        ui.text(root, "TVCOMPANION", 13f)
        ui.text(root, "Make yourself at home.", 36f, true)
        ui.text(root, "Your room, connected. Your TV, uninterrupted.", 18f)
        val home = ui.panel(root, "Smart home")
        ui.text(home, "Control your Bardi light over your local network.")
        val lights = ui.button(home, "Open light controls") { showLight() }
        val alerts = ui.panel(root, "Quiet notifications")
        ui.text(alerts, "Choose your sources, preview cards and manage the receiver.")
        ui.button(alerts, "Open notification settings") {
            startActivity(Intent(this, NotificationSettingsActivity::class.java))
        }
        ui.text(root, "Use arrows to explore · OK to select · Back to return", 14f)
        setContentView(scroll); lights.requestFocus()
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
