package id.poncol.tvcompanion.ambilight

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import id.poncol.tvcompanion.ambilight.engine.LightingProfile
import id.poncol.tvcompanion.designsystem.TvUi

/** Everyday Ambilight screen: start/stop, style, brightness and stop behaviour. Strip wiring lives in [StripSetupActivity]. */
class AmbilightSettingsActivity : Activity() {
    private lateinit var ui: TvUi
    private lateinit var prefs: AmbilightPreferences
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var sessionAction: Button
    private lateinit var stripSetup: Button
    private lateinit var styles: List<Button>
    private lateinit var stopMode: TvUi.ValueRow
    private var needsAddress = false
    private val observer: (AmbilightState) -> Unit = ::render

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        val (left, right) = columns(ui, root)

        val session = ui.panel(left, "")
        val heading = ui.row()
        session.addView(heading)
        ui.icon(heading, R.drawable.ic_ambilight, ui.warm, 44)
        val headline = ui.column().apply { setPadding(ui.dp(14), 0, 0, 0) }
        heading.addView(headline)
        status = ui.text(headline, "", 23f, true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        details = ui.text(headline, "", 16f).apply { visibility = View.GONE }
        sessionAction = ui.button(session, getString(R.string.ambilight_start)) {
            when (ambilightSession.state.phase) {
                Phase.STARTING, Phase.ACTIVE -> ambilightSession.stop()
                Phase.OFF, Phase.ERROR -> requestConsent()
                Phase.STOPPING -> Unit
            }
        }
        ui.text(session, getString(R.string.ambilight_permission_help), 16f).setPadding(0, ui.dp(8), 0, 0)
        val links = ui.row()
        left.addView(links)
        stripSetup = ui.button(links, getString(R.string.ambilight_strip_setup)) {
            startActivity(Intent(this, StripSetupActivity::class.java))
        }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(12) } }
        ui.button(links, getString(R.string.ambilight_help)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.ambilight_help)
                .setMessage(R.string.ambilight_compatibility_help)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)

        val look = ui.panel(right, getString(R.string.ambilight_style))
        val names = resources.getStringArray(R.array.ambilight_profile_names)
        val descriptions = resources.getStringArray(R.array.ambilight_profile_descriptions)
        styles = LightingProfile.entries.map { profile ->
            ui.button(look, "") {
                save(prefs.load().copy(profile = profile))
                showOptions(styles, profile.ordinal, names, descriptions)
            }
        }
        showOptions(styles, config.profile.ordinal, names, descriptions)
        ui.stepper(look, getString(R.string.ambilight_brightness), { prefs.load().brightness }, { 5..100 }, 5, { "$it%" }) { value ->
            save(prefs.load().copy(brightness = value))
            true
        }
        ui.stepper(look, getString(R.string.ambilight_contrast), { prefs.load().contrast }, { 0..100 }, 10,
            { if (it == 0) getString(R.string.ambilight_contrast_normal) else "$it%" }) { value ->
            save(prefs.load().copy(contrast = value))
            true
        }
        val stopModes = resources.getStringArray(R.array.ambilight_stop_modes)
        stopMode = ui.valueRow(look, getString(R.string.ambilight_stop_mode)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.ambilight_stop_mode)
                .setSingleChoiceItems(stopModes, if (prefs.load().turnOff) 1 else 0) { dialog, index ->
                    prefs.save(prefs.load().copy(turnOff = index == 1))
                    stopMode.value.text = stopModes[index]
                    dialog.dismiss()
                }
                .show()
        }
        stopMode.value.text = stopModes[if (config.turnOff) 1 else 0]
        stopMode.value.setTextColor(ui.muted)

        setContentView(scroll)
        render(ambilightSession.state)
        if (sessionAction.isEnabled) sessionAction.requestFocus()
    }

    /** Radio-style option buttons: marker and name, with a muted one-line description beneath. */
    private fun showOptions(buttons: List<Button>, selected: Int, names: Array<String>, descriptions: Array<String>) {
        buttons.forEachIndexed { index, button ->
            val chosen = index == selected
            val title = "${if (chosen) "●" else "○"}  ${names[index]}"
            val copy = SpannableString("$title\n${descriptions[index]}")
            copy.setSpan(AbsoluteSizeSpan(15, true), title.length + 1, copy.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            copy.setSpan(ForegroundColorSpan(ui.muted), title.length + 1, copy.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            button.text = copy
            button.isSelected = chosen
            button.typeface = Typeface.DEFAULT
            button.contentDescription = "${names[index]}. ${descriptions[index]}" +
                if (chosen) ". ${getString(R.string.ambilight_selected)}" else ""
        }
    }

    /** Style, brightness and contrast also reach a running session immediately; the stop choice is read when sync stops. */
    private fun save(config: AmbilightConfig) {
        prefs.save(config)
        ambilightSession.look = Look(config.profile, config.brightness, config.contrast)
    }

    override fun onStart() {
        super.onStart()
        ambilightSession.observe(observer)
    }

    override fun onStop() {
        ambilightSession.remove(observer)
        super.onStop()
    }

    private fun render(state: AmbilightState) {
        val busy = state.phase in setOf(Phase.STARTING, Phase.ACTIVE, Phase.STOPPING)
        if (busy) needsAddress = false
        val mismatch = state.ledCountMismatch
        val unreachable = state.phase == Phase.ACTIVE && state.controllerUnreachable
        // Short state as the headline; the explanation and remedy go in the details below it.
        status.text = getString(when {
            needsAddress -> R.string.ambilight_address_status
            mismatch != null -> R.string.ambilight_led_count_status
            unreachable -> R.string.ambilight_unreachable_status
            else -> state.label(prefs.load().address.isNotEmpty())
        })
        val ended = state.phase == Phase.OFF || state.phase == Phase.ERROR
        val messages = if (needsAddress) listOf(getString(R.string.ambilight_address_needed)) else listOfNotNull(
            mismatch?.let { getString(R.string.ambilight_led_count_mismatch, it.configured, it.available) },
            state.issue?.takeIf { ended && mismatch == null }?.let { getString(it.detail()) },
            getString(R.string.ambilight_unreachable_detail).takeIf { unreachable },
            state.controllerName?.let { getString(R.string.ambilight_wled_name, it) },
            getString(R.string.ambilight_blank).takeIf { state.blank },
            getString(R.string.ambilight_send_failed).takeIf { state.sendError },
        )
        details.text = messages.joinToString("\n")
        details.visibility = if (messages.isEmpty()) View.GONE else View.VISIBLE
        sessionAction.setText(when {
            state.testing && busy -> R.string.ambilight_cancel_test
            busy -> R.string.ambilight_stop
            else -> R.string.ambilight_start
        })
        sessionAction.isEnabled = Ambilight.actionEnabled(state)
    }

    private fun requestConsent() {
        if (Ambilight.requestStart(this) == AmbilightStart.NOT_SET_UP) {
            needsAddress = true
            render(ambilightSession.state)
            stripSetup.requestFocus()
        }
    }

    companion object {
        fun observeStatus(context: Context, update: (String) -> Unit): AutoCloseable {
            val configured = AmbilightPreferences(context).load().address.isNotEmpty()
            val observer: (AmbilightState) -> Unit = { state ->
                val label = when {
                    state.ledCountMismatch != null -> R.string.ambilight_led_count_status
                    state.phase == Phase.ACTIVE && state.controllerUnreachable -> R.string.ambilight_unreachable_status
                    state.sendError -> R.string.ambilight_send_failed_status
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

/** Two columns on a TV-width screen; one stacked column at narrow widths or large text. */
internal fun Activity.columns(ui: TvUi, root: LinearLayout, leftWeight: Float = 1.25f): Pair<LinearLayout, LinearLayout> {
    val wide = resources.configuration.screenWidthDp >= 820 && resources.configuration.fontScale <= 1.2f
    if (!wide) return root to root
    val row = ui.row().apply { gravity = android.view.Gravity.TOP }
    root.addView(row)
    val left = ui.column()
    val right = ui.column()
    row.addView(left, LinearLayout.LayoutParams(0, -2, leftWeight).apply { marginEnd = ui.dp(16) })
    row.addView(right, LinearLayout.LayoutParams(0, -2, 1f))
    return left to right
}

internal fun AmbilightState.label(configured: Boolean): Int = when (phase) {
    Phase.STARTING -> R.string.ambilight_starting
    Phase.ACTIVE -> if (testing) R.string.ambilight_testing else R.string.ambilight_active
    Phase.STOPPING -> R.string.ambilight_stopping
    Phase.OFF, Phase.ERROR -> when (issue) {
        Issue.CONSENT -> R.string.ambilight_consent_status
        Issue.CAPTURE -> R.string.ambilight_capture_status
        Issue.REVOKED -> R.string.ambilight_revoked_status
        Issue.CONTROLLER -> R.string.ambilight_controller_status
        Issue.STOP_OUTPUT -> R.string.ambilight_exit_status
        Issue.TEST_COMPLETE -> R.string.ambilight_test_status
        null -> if (configured) R.string.ambilight_ready else R.string.ambilight_off
    }
}

internal fun Issue.detail(): Int = when (this) {
    Issue.CONSENT -> R.string.ambilight_consent_failed
    Issue.CAPTURE -> R.string.ambilight_capture_failed
    Issue.REVOKED -> R.string.ambilight_revoked
    Issue.CONTROLLER -> R.string.ambilight_controller_failed
    Issue.STOP_OUTPUT -> R.string.ambilight_exit_failed
    Issue.TEST_COMPLETE -> R.string.ambilight_test_done
}
