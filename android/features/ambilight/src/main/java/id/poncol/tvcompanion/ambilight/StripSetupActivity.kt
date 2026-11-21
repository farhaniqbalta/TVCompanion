package id.poncol.tvcompanion.ambilight

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import id.poncol.tvcompanion.ambilight.AmbilightCaptureService.Companion.ACTION_TEST
import id.poncol.tvcompanion.ambilight.AmbilightCaptureService.Companion.EXTRA_EPOCH
import id.poncol.tvcompanion.ambilight.engine.LedLayout
import id.poncol.tvcompanion.designsystem.TvUi

/** Rarely used wiring screen: controller address, edge counts and where the strip starts. Changes save as they are made. */
class StripSetupActivity : Activity() {
    private lateinit var ui: TvUi
    private lateinit var prefs: AmbilightPreferences
    private lateinit var diagram: StripDiagramView
    private lateinit var address: EditText
    private lateinit var addressError: TextView
    private lateinit var testButton: Button
    private lateinit var result: TextView
    private lateinit var total: TextView
    private lateinit var skip: TvUi.ValueRow
    private val settingsControls = mutableListOf<View>()
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
        ui.brandHeader(root, getString(R.string.ambilight_title))
        ui.text(root, getString(R.string.ambilight_strip_setup), 34f, true)
        val (left, right) = columns(ui, root)

        val picture = ui.panel(left, "")
        diagram = StripDiagramView(this).apply { layout = config.layout }
        picture.addView(diagram, LinearLayout.LayoutParams(-1, ui.dp(250)))
        ui.text(picture, getString(R.string.ambilight_diagram_help), 15f)
        val corners = resources.getStringArray(R.array.ambilight_corners)
        val directions = resources.getStringArray(R.array.ambilight_directions)
        // A centred row adds child margins on top of centring and clips them, so the row carries the margin.
        val wiring = ui.row().apply { isBaselineAligned = false }
        picture.addView(wiring, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        val corner = choice(wiring, R.string.ambilight_corner, corners, { it.startCorner }) { layout, index ->
            LedLayout(layout.counts, index, layout.clockwise, layout.firstLed)
        }
        val direction = choice(wiring, R.string.ambilight_direction, directions, { if (it.clockwise) 0 else 1 }) { layout, index ->
            LedLayout(layout.counts, layout.startCorner, index == 0, layout.firstLed)
        }
        corner.view.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(12) }
        direction.view.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        skip = ui.stepper(picture, getString(R.string.ambilight_offset), { layout().firstLed }, { 0 until layout().total }) { value ->
            update { LedLayout(it.counts, it.startCorner, it.clockwise, value) }
        }
        settingsControls += skip.view

        val controller = ui.panel(right, "")
        ui.text(controller, getString(R.string.ambilight_address), 16f)
        val addressRow = ui.row().apply { isBaselineAligned = false }
        controller.addView(addressRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        address = EditText(this).apply {
            id = View.generateViewId()
            setText(config.address)
            textSize = 19f
            setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(0xff6f818a.toInt(), ui.ink)))
            setHint(R.string.ambilight_address_example)
            setHintTextColor(ui.muted)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(15))
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
            background = ui.focusSurface()
            contentDescription = getString(R.string.ambilight_address)
        }
        addressRow.addView(address, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(12) })
        settingsControls += address
        testButton = ui.button(addressRow, getString(R.string.ambilight_test), ::test).apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2)
        }
        settingsControls += testButton
        addressError = ui.text(controller, "", 16f).apply {
            setTextColor(ui.warm)
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        address.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { addressError.visibility = View.GONE }
        })
        result = ui.text(controller, "", 16f).apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        ui.text(controller, getString(R.string.ambilight_address_help), 15f)

        val edges = ui.panel(right, getString(R.string.ambilight_layout))
        resources.getStringArray(R.array.ambilight_edges).forEachIndexed { side, label ->
            ui.stepper(edges, label, { layout().counts[side] }, { 0..MAX_EDGE }) { value ->
                update { layout ->
                    val counts = layout.counts.toMutableList().also { it[side] = value }
                    if (counts.sum() == 0) null
                    else LedLayout(counts, layout.startCorner, layout.clockwise, minOf(layout.firstLed, counts.sum() - 1))
                }
            }.also { settingsControls += it.view }
        }
        total = ui.text(edges, "", 17f, true).apply { setPadding(0, ui.dp(10), 0, 0) }
        settingsControls += corner.view
        settingsControls += direction.view

        setContentView(scroll)
        showLayout(config.layout)
        render(ambilightSession.state)
        testButton.requestFocus()
    }

    private fun layout() = prefs.load().layout

    /** Saves a changed layout; the change returns null to reject it (for example, every edge at zero). */
    private fun update(change: (LedLayout) -> LedLayout?): Boolean {
        val next = change(layout()) ?: return false
        prefs.save(prefs.load().copy(layout = next))
        showLayout(next)
        return true
    }

    private fun showLayout(layout: LedLayout) {
        diagram.layout = layout
        total.text = resources.getQuantityString(R.plurals.ambilight_total, layout.total, layout.total)
        skip.value.text = "‹  ${layout.firstLed}  ›"
        skip.view.contentDescription = "${getString(R.string.ambilight_offset)} ${layout.firstLed}"
    }

    private fun choice(parent: LinearLayout, label: Int, options: Array<String>, selected: (LedLayout) -> Int,
                       apply: (LedLayout, Int) -> LedLayout): TvUi.ValueRow {
        lateinit var row: TvUi.ValueRow
        row = ui.valueRow(parent, getString(label)) {
            AlertDialog.Builder(this)
                .setTitle(label)
                .setSingleChoiceItems(options, selected(layout())) { dialog, index ->
                    update { apply(it, index) }
                    row.value.text = options[index]
                    dialog.dismiss()
                }
                .show()
        }
        row.value.text = options[selected(layout())]
        row.value.setTextColor(ui.muted)
        return row
    }

    /** Saves a valid address, or shows why it is invalid. Blank stays allowed until something needs the controller. */
    private fun saveAddress(required: Boolean): Boolean {
        val text = address.text.toString().trim()
        val canonical = ipv4Bytes(text)?.joinToString(".") { (it.toInt() and 255).toString() }
        if (canonical == null) {
            if (required || text.isNotEmpty()) {
                addressError.text = getString(R.string.ambilight_invalid_address)
                addressError.visibility = View.VISIBLE
                if (required) address.requestFocus()
            }
            return false
        }
        if (canonical != prefs.load().address) prefs.save(prefs.load().copy(address = canonical))
        return true
    }

    private fun test() {
        if (!saveAddress(required = true)) return
        val epoch = ambilightSession.begin(testing = true) ?: return
        try {
            startService(Intent(this, AmbilightCaptureService::class.java)
                .setAction(ACTION_TEST).putExtra(EXTRA_EPOCH, epoch))
        } catch (_: RuntimeException) {
            ambilightSession.cancel(epoch, Issue.CONTROLLER)
        }
    }

    private fun render(state: AmbilightState) {
        val busy = state.phase in setOf(Phase.STARTING, Phase.ACTIVE, Phase.STOPPING)
        val mismatch = state.ledCountMismatch
        val lines = when {
            busy && state.testing -> listOf(getString(R.string.ambilight_testing))
            busy -> listOf(getString(R.string.ambilight_locked))
            mismatch != null -> listOf(getString(R.string.ambilight_led_count_mismatch, mismatch.configured, mismatch.available))
            state.issue == Issue.TEST_COMPLETE || state.issue == Issue.CONTROLLER ->
                listOf(getString(state.label(true)), getString(state.issue.detail()))
            else -> emptyList()
        }
        result.text = lines.joinToString("\n")
        result.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        result.setTextColor(if (state.issue == Issue.TEST_COMPLETE || busy) ui.ink else ui.warm)
        settingsControls.forEach { it.isEnabled = !busy }
        // Everything here locks during a test; put focus back on Test LEDs once it finishes.
        if (!busy && currentFocus?.isEnabled != true) testButton.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        ambilightSession.observe(observer)
    }

    override fun onStop() {
        ambilightSession.remove(observer)
        super.onStop()
    }

    override fun onPause() {
        if (address.isEnabled) saveAddress(required = false)
        super.onPause()
    }

    private companion object {
        const val MAX_EDGE = 500
    }
}
