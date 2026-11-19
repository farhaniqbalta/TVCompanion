package id.poncol.tvcompanion.ambilight

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import id.poncol.tvcompanion.ambilight.AmbilightCaptureService.Companion.ACTION_START
import id.poncol.tvcompanion.ambilight.AmbilightCaptureService.Companion.ACTION_TEST
import id.poncol.tvcompanion.ambilight.AmbilightCaptureService.Companion.EXTRA_CONSENT
import id.poncol.tvcompanion.ambilight.AmbilightCaptureService.Companion.EXTRA_EPOCH
import id.poncol.tvcompanion.ambilight.engine.LedLayout
import id.poncol.tvcompanion.ambilight.engine.LightingProfile
import id.poncol.tvcompanion.designsystem.TvUi

class AmbilightSettingsActivity : Activity() {
    private lateinit var ui: TvUi
    private lateinit var prefs: AmbilightPreferences
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var feedback: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var address: EditText
    private lateinit var edges: List<EditText>
    private lateinit var first: EditText
    private lateinit var brightness: EditText
    private lateinit var total: TextView
    private lateinit var corner: Choice
    private lateinit var direction: Choice
    private lateinit var profile: Choice
    private lateinit var stopMode: Choice
    private val settingsControls = mutableListOf<View>()
    private var pendingConsent = 0L
    private val observer: (AmbilightState) -> Unit = ::render

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingConsent = savedInstanceState?.getLong("consent_epoch") ?: 0L
        ui = TvUi(this)
        prefs = AmbilightPreferences(this)
        val config = prefs.load()
        val root = ui.page()
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            descendantFocusability = ScrollView.FOCUS_AFTER_DESCENDANTS
            addView(root)
        }
        ui.brandHeader(root, getString(R.string.ambilight_card_category))
        ui.text(root, getString(R.string.ambilight_title), 34f, true)
        status = ui.text(root, "", 21f, true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        details = ui.text(root, "").apply { visibility = View.GONE }
        feedback = ui.text(root, "").apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val actions = ui.row()
        root.addView(actions)
        start = ui.button(actions, getString(R.string.ambilight_start), ::requestConsent)
        stop = ui.button(actions, getString(R.string.ambilight_stop)) { ambilightSession.stop() }
        val test = ui.button(actions, getString(R.string.ambilight_test), ::test)
        for (button in listOf(start, stop, test)) {
            button.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(10) }
        }
        settingsControls += test
        ui.spacer(root, 12)
        ui.text(root, getString(R.string.ambilight_permission_help))
        ui.text(root, getString(R.string.ambilight_compatibility_help))
        val setup = ui.panel(root, getString(R.string.ambilight_settings))
        address = field(setup, getString(R.string.ambilight_address), config.address, address = true)
        val layout = ui.panel(root, getString(R.string.ambilight_layout))
        ui.text(layout, getString(R.string.ambilight_layout_help))
        val grid = ui.row()
        layout.addView(grid)
        edges = resources.getStringArray(R.array.ambilight_edges).mapIndexed { index, label ->
            val cell = ui.column()
            grid.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(10) })
            field(cell, label, config.layout.counts[index].toString())
        }
        total = ui.text(layout, getString(R.string.ambilight_total, config.layout.total))
        corner = Choice(layout, R.string.ambilight_corner, R.array.ambilight_corners, config.layout.startCorner)
        direction = Choice(layout, R.string.ambilight_direction, R.array.ambilight_directions, if (config.layout.clockwise) 0 else 1)
        first = field(layout, getString(R.string.ambilight_offset), config.layout.firstLed.toString())
        val behavior = ui.panel(root, getString(R.string.ambilight_profile))
        profile = Choice(behavior, R.string.ambilight_profile, R.array.ambilight_profiles, config.profile.ordinal)
        brightness = field(behavior, getString(R.string.ambilight_brightness), config.brightness.toString())
        stopMode = Choice(behavior, R.string.ambilight_stop_mode, R.array.ambilight_stop_modes, if (config.turnOff) 1 else 0)
        ui.text(behavior, getString(R.string.ambilight_resume_help))
        settingsControls += ui.button(behavior, getString(R.string.ambilight_save)) { save(showConfirmation = true) }
        setContentView(scroll)
        render(ambilightSession.state)
        (if (start.isEnabled) start else stop).requestFocus()
    }

    override fun onStart() {
        super.onStart()
        ambilightSession.observe(observer)
    }

    override fun onStop() {
        ambilightSession.remove(observer)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("consent_epoch", pendingConsent)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (isFinishing && pendingConsent != 0L) ambilightSession.cancel(pendingConsent)
        super.onDestroy()
    }

    private fun render(state: AmbilightState) {
        val busy = state.phase in setOf(Phase.STARTING, Phase.ACTIVE, Phase.STOPPING)
        status.text = getString(state.label(prefs.load().address.isNotEmpty()))
        val messages = listOfNotNull(
            state.controllerName?.let { getString(R.string.ambilight_wled_name, it) },
            getString(R.string.ambilight_blank).takeIf { state.blank },
            getString(R.string.ambilight_controller_failed).takeIf { state.controllerUnreachable },
            getString(R.string.ambilight_send_failed).takeIf { state.sendError },
        )
        details.text = messages.joinToString("\n")
        details.visibility = if (messages.isEmpty()) View.GONE else View.VISIBLE
        val moveToStop = start.hasFocus() && busy
        val moveToStart = stop.hasFocus() && !busy
        start.isEnabled = !busy && pendingConsent == 0L
        stop.isEnabled = state.phase == Phase.STARTING || state.phase == Phase.ACTIVE
        settingsControls.forEach { it.isEnabled = !busy }
        if (moveToStop && stop.isEnabled) stop.requestFocus()
        if (moveToStart) start.requestFocus()
    }

    private fun field(parent: LinearLayout, label: String, value: String, address: Boolean = false): EditText {
        val input = EditText(this).apply {
            id = View.generateViewId()
            setText(value)
            textSize = 19f
            setTextColor(ui.ink)
            inputType = if (address) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI else InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(if (address) 15 else 4))
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
            background = ui.focusSurface()
        }
        ui.text(parent, label).labelFor = input.id
        parent.addView(input, LinearLayout.LayoutParams(-1, -2))
        settingsControls += input
        return input
    }

    private inner class Choice(parent: LinearLayout, private val label: Int, values: Int, initial: Int) {
        private val options = resources.getStringArray(values)
        var selected = initial
            private set
        private val button = ui.button(parent, copy()) {
            AlertDialog.Builder(this@AmbilightSettingsActivity)
                .setTitle(label)
                .setSingleChoiceItems(options, selected) { dialog, index ->
                    selected = index
                    update()
                    dialog.dismiss()
                }
                .show()
        }

        init { settingsControls += button }
        private fun copy() = "${getString(label)} · ${options[selected]}"
        private fun update() { button.text = copy() }
    }

    private fun showFeedback(resource: Int) {
        feedback.text = getString(resource)
        feedback.visibility = View.VISIBLE
    }

    private fun save(showConfirmation: Boolean = false): Boolean {
        val ip = address.text.toString().trim()
        val canonical = ipv4Bytes(ip)?.joinToString(".") { (it.toInt() and 255).toString() }
        if (canonical == null) {
            showFeedback(R.string.ambilight_invalid_address)
            address.requestFocus()
            return false
        }
        val layout = runCatching {
            LedLayout(edges.map { it.text.toString().toInt() }, corner.selected, direction.selected == 0, first.text.toString().toInt())
        }.getOrNull()
        val cap = brightness.text.toString().toIntOrNull()
        if (layout == null || cap == null || cap !in 1..100) {
            showFeedback(R.string.ambilight_invalid_layout)
            edges.first().requestFocus()
            return false
        }
        prefs.save(AmbilightConfig(canonical, layout, LightingProfile.entries[profile.selected], cap, stopMode.selected == 1))
        total.text = getString(R.string.ambilight_total, layout.total)
        if (showConfirmation) {
            showFeedback(R.string.ambilight_saved)
            render(ambilightSession.state)
        } else feedback.visibility = View.GONE
        return true
    }

    @Suppress("DEPRECATION")
    private fun requestConsent() {
        if (pendingConsent != 0L || !save()) return
        val epoch = ambilightSession.begin() ?: return
        pendingConsent = epoch
        try {
            val projection = getSystemService(MediaProjectionManager::class.java)
            val request = if (Build.VERSION.SDK_INT >= 34) {
                runCatching {
                    projection.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                }.getOrElse { projection.createScreenCaptureIntent() }
            } else projection.createScreenCaptureIntent()
            startActivityForResult(request, SCREEN_CAPTURE)
        } catch (_: RuntimeException) {
            pendingConsent = 0
            ambilightSession.cancel(epoch, Issue.CONSENT)
        }
    }

    @Deprecated("The platform result callback supports API 26 without an additional dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SCREEN_CAPTURE) return
        val epoch = pendingConsent
        pendingConsent = 0
        val state = ambilightSession.state
        if (epoch == 0L || state.epoch != epoch || state.phase != Phase.STARTING) {
            render(state)
            return
        }
        if (resultCode != RESULT_OK || data == null) {
            ambilightSession.cancel(epoch, Issue.CONSENT)
            return
        }
        try {
            startForegroundService(Intent(this, AmbilightCaptureService::class.java)
                .setAction(ACTION_START).putExtra(EXTRA_EPOCH, epoch).putExtra(EXTRA_CONSENT, data))
        } catch (_: RuntimeException) {
            ambilightSession.cancel(epoch, Issue.CAPTURE)
        }
    }

    private fun test() {
        if (!save()) return
        val epoch = ambilightSession.begin(testing = true) ?: return
        try {
            startService(Intent(this, AmbilightCaptureService::class.java)
                .setAction(ACTION_TEST).putExtra(EXTRA_EPOCH, epoch))
        } catch (_: RuntimeException) {
            ambilightSession.cancel(epoch, Issue.CONTROLLER)
        }
    }

    companion object {
        private const val SCREEN_CAPTURE = 4048

        fun observeStatus(context: Context, update: (String) -> Unit): AutoCloseable {
            val configured = AmbilightPreferences(context).load().address.isNotEmpty()
            val observer: (AmbilightState) -> Unit = { state ->
                val label = when {
                    state.controllerUnreachable -> R.string.ambilight_controller_failed
                    state.sendError -> R.string.ambilight_send_failed
                    state.issue == Issue.TEST_COMPLETE -> R.string.ambilight_ready
                    else -> state.label(configured)
                }
                update(context.getString(label))
            }
            ambilightSession.observe(observer)
            return AutoCloseable { ambilightSession.remove(observer) }
        }
    }
}

private fun AmbilightState.label(configured: Boolean): Int = when (phase) {
    Phase.STARTING -> R.string.ambilight_starting
    Phase.ACTIVE -> if (testing) R.string.ambilight_testing else R.string.ambilight_active
    Phase.STOPPING -> R.string.ambilight_stopping
    Phase.OFF, Phase.ERROR -> when (issue) {
        Issue.CONSENT -> R.string.ambilight_consent_failed
        Issue.CAPTURE -> R.string.ambilight_capture_failed
        Issue.REVOKED -> R.string.ambilight_revoked
        Issue.CONTROLLER -> R.string.ambilight_controller_failed
        Issue.STOP_OUTPUT -> R.string.ambilight_exit_failed
        Issue.TEST_COMPLETE -> R.string.ambilight_test_done
        null -> if (configured) R.string.ambilight_ready else R.string.ambilight_off
    }
}
