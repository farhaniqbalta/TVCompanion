package id.poncol.tvcompanion.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import id.poncol.tvcompanion.airplay.feature.AirPlaySettingsActivity
import id.poncol.tvcompanion.ambilight.AmbilightSettingsActivity
import id.poncol.tvcompanion.ambilight.R as AmbilightR
import id.poncol.tvcompanion.designsystem.TvUi
import id.poncol.tvcompanion.notifications.presentation.NotificationSettingsActivity
import id.poncol.tvcompanion.smarthome.LightScreen

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var showingLight = false
    private var restoreLight = false
    private var homeFocus = 0
    private var ambilightStatus: AutoCloseable? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        homeFocus = savedInstanceState?.getInt("home_focus") ?: 0
        BootReceiver.startDefaults(this, foregroundStart = false)
        if (savedInstanceState?.getBoolean("light") == true ||
            (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 && intent.getBooleanExtra("open_light", false))) showLight() else showHome()
    }
    // The shared controller outlives this screen; detaching LightScreen stops its observation.
    private fun releaseLight() { main.removeCallbacksAndMessages(null) }
    private fun showHome() {
        releaseLight(); showingLight = false
        val ui = TvUi(this)
        val root = ui.page()
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(root) }
        ui.brandHeader(root, getString(R.string.home_eyebrow))
        ui.spacer(root, 12)
        ui.text(root, getString(R.string.home_title), 36f, true)
        ui.text(root, getString(R.string.home_subtitle), 18f)
        ui.spacer(root, 10)
        val cards = ui.row().apply { gravity = android.view.Gravity.TOP; clipChildren = false }
        root.addView(cards)
        val lights = ui.featureCard(cards, id.poncol.tvcompanion.designsystem.R.drawable.ic_bulb, ui.warm,
            getString(R.string.home_light_category), getString(R.string.home_light_title),
            getString(R.string.home_light_description), getString(R.string.home_light_action)) { showLight() }
        cards.addView(android.view.View(this), android.widget.LinearLayout.LayoutParams(ui.dp(20), 1))
        val notifications = ui.featureCard(cards, id.poncol.tvcompanion.designsystem.R.drawable.ic_bell, ui.accent,
            getString(R.string.home_notifications_category), getString(R.string.home_notifications_title),
            getString(R.string.home_notifications_description), getString(R.string.home_notifications_action)) {
            homeFocus = 1
            startActivity(Intent(this, NotificationSettingsActivity::class.java))
        }
        lights.setOnFocusChangeListener { _, focused -> if (focused) homeFocus = 0 }
        notifications.setOnFocusChangeListener { _, focused -> if (focused) homeFocus = 1 }
        ui.spacer(root, 12)
        val media = ui.row().apply { gravity = android.view.Gravity.TOP; clipChildren = false }
        root.addView(media)
        val airplay = ui.featureCard(media, R.drawable.ic_airplay, ui.accent,
            getString(R.string.home_airplay_category), getString(R.string.home_airplay_title),
            getString(R.string.home_airplay_description), getString(R.string.home_airplay_action)) {
            homeFocus = 2
            startActivity(Intent(this, AirPlaySettingsActivity::class.java))
        }
        airplay.setOnFocusChangeListener { _, focused -> if (focused) homeFocus = 2 }
        media.addView(android.view.View(this), android.widget.LinearLayout.LayoutParams(ui.dp(20), 1))
        val description = getString(AmbilightR.string.ambilight_card_description)
        val title = getString(AmbilightR.string.ambilight_title)
        val ambilight = ui.featureCard(media, AmbilightR.drawable.ic_ambilight, ui.warm,
            getString(AmbilightR.string.ambilight_card_category), title, description, action = null) {
            homeFocus = 3
            startActivity(Intent(this, AmbilightSettingsActivity::class.java))
        }
        ambilight.setOnFocusChangeListener { _, focused -> if (focused) homeFocus = 3 }
        val status = ui.text(ambilight, "", 16f, true).apply {
            setTextColor(ui.warm)
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        ambilightStatus?.close()
        ambilightStatus = AmbilightSettingsActivity.observeStatus(this) { copy ->
            val action = getString(AmbilightR.string.ambilight_card_action)
            status.text = getString(AmbilightR.string.ambilight_card_status, copy, action)
            ambilight.contentDescription = "$title. $description $copy. $action"
        }
        ui.spacer(root, 12)
        ui.text(root, getString(R.string.home_hint), 13f)
        val initialFocus = homeFocus
        setContentView(scroll)
        (when (initialFocus) { 1 -> notifications; 2 -> airplay; 3 -> ambilight; else -> lights }).requestFocus()
    }
    private fun showLight() {
        closeAmbilightStatus()
        releaseLight(); showingLight = true
        val controller = (application as TVCompanionApplication).light
        setContentView(LightScreen(this, controller) { showHome() })
        controller.refresh()
    }
    private fun closeAmbilightStatus() {
        ambilightStatus?.close()
        ambilightStatus = null
    }
    @Deprecated("Platform Back callback for API 26+")
    override fun onBackPressed() { if (showingLight) showHome() else super.onBackPressed() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("light", showingLight)
        outState.putInt("home_focus", homeFocus)
        super.onSaveInstanceState(outState)
    }
    override fun onStop() {
        super.onStop()
        closeAmbilightStatus()
        restoreLight = showingLight
        releaseLight()
        // Remove views and their observers while the foreground receiver keeps the process alive.
        setContentView(android.widget.FrameLayout(this))
    }
    override fun onRestart() { super.onRestart(); if (restoreLight) showLight() else showHome() }
    override fun onDestroy() { closeAmbilightStatus(); releaseLight(); super.onDestroy() }
}
