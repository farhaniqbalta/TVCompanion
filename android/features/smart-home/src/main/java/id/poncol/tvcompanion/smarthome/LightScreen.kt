package id.poncol.tvcompanion.smarthome

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.*
import id.poncol.tvcompanion.application.LightController
import id.poncol.tvcompanion.domain.LightCommand
import id.poncol.tvcompanion.designsystem.TvUi
import id.poncol.tvcompanion.designsystem.R as DesignR

/** Presentation only: all commands and confirmed state still belong to the injected controller. */
// Constructed programmatically with feature state; intentionally not XML-inflatable.
@android.annotation.SuppressLint("ViewConstructor")
class LightScreen(context: Context, private val controller: LightController, back: () -> Unit) : ScrollView(context) {
    init {
        isFillViewport = true
        val ui = TvUi(context)
        fun copy(id: Int) = context.getString(id)
        val root = ui.page(); addView(root)
        ui.brandHeader(root, copy(R.string.light_eyebrow))
        ui.text(root, copy(R.string.light_title), 30f, true)
        val navigation = ui.row(); root.addView(navigation)
        val home = ui.button(navigation, copy(R.string.light_back), back)
        home.layoutParams = LinearLayout.LayoutParams(ui.dp(136), -2).apply { marginEnd = ui.dp(12) }
        val refresh = ui.button(navigation, copy(R.string.light_refresh)) { controller.refresh() }
        refresh.layoutParams = LinearLayout.LayoutParams(ui.dp(184), -2)
        ui.spacer(root, 20)
        val columns = ui.row().apply { gravity = Gravity.TOP }; root.addView(columns)
        val device = ui.panel(columns, "")
        device.layoutParams = LinearLayout.LayoutParams(0, -2, .95f).apply { marginEnd = ui.dp(20) }
        val bulb = ui.icon(device, DesignR.drawable.ic_bulb, ui.warm, 64)
        ui.spacer(device, 14)
        ui.text(device, copy(R.string.light_device), 17f)
        val status = ui.text(device, copy(R.string.light_unavailable), 27f, true)
        val detail = ui.text(device, "", 15f).apply { minLines = 2 }
        val controls = ui.panel(columns, copy(R.string.light_controls))
        controls.layoutParams = LinearLayout.LayoutParams(0, -2, 1.25f)
        val power = ui.button(controls, copy(R.string.light_power_on)) {
            controller.state.light?.let { controller.execute(LightCommand.Power(!it.on)) }
        }
        ui.spacer(controls, 18)
        ui.eyebrow(controls, copy(R.string.light_brightness_label))
        val levels = ui.row(); controls.addView(levels)
        val less = ui.button(levels, copy(R.string.light_dim)) {
            controller.state.light?.brightness?.let { controller.execute(LightCommand.Brightness((it - 10).coerceAtLeast(0))) }
        }.apply { contentDescription = copy(R.string.light_dim_accessibility) }
        less.layoutParams = LinearLayout.LayoutParams(ui.dp(96), ui.dp(56))
        val brightness = ui.text(levels, copy(R.string.light_brightness_unknown), 34f, true).apply {
            gravity = Gravity.CENTER; setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val more = ui.button(levels, copy(R.string.light_brighten)) {
            controller.state.light?.brightness?.let { controller.execute(LightCommand.Brightness((it + 10).coerceAtMost(100))) }
        }.apply { contentDescription = copy(R.string.light_brighten_accessibility) }
        more.layoutParams = LinearLayout.LayoutParams(ui.dp(96), ui.dp(56))
        val level = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = ColorStateList.valueOf(ui.warm)
            progressBackgroundTintList = ColorStateList.valueOf(0xff34464d.toInt())
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        controls.addView(level, LinearLayout.LayoutParams(-1, ui.dp(6)).apply { topMargin = ui.dp(14); bottomMargin = ui.dp(12) })
        val hint = ui.text(controls, "", 14f)
        ui.spacer(root, 12)
        ui.text(root, copy(R.string.light_footer), 13f)
        var initialRead = true
        controller.observe { state ->
            val light = state.light
            val focused = findFocus()
            status.text = copy(if (light == null) R.string.light_unavailable else if (light.on) R.string.light_on else R.string.light_off)
            detail.text = state.message
            bulb.imageTintList = ColorStateList.valueOf(if (light?.on == true) ui.warm else ui.muted)
            power.text = copy(if (state.busy && light != null) R.string.light_updating else if (light?.on == true) R.string.light_power_off else R.string.light_power_on)
            // Keep focus during an in-flight operation; the controller rejects repeated input.
            power.isEnabled = light != null
            power.isSelected = light?.on == true
            brightness.text = light?.brightness?.let { context.getString(R.string.light_brightness_value, it) } ?: copy(R.string.light_brightness_unknown)
            level.progress = light?.brightness ?: 0
            level.alpha = if (light?.supportsBrightness == true) 1f else .3f
            less.isEnabled = light?.supportsBrightness == true && light.brightness!! > 0
            more.isEnabled = light?.supportsBrightness == true && light.brightness!! < 100
            refresh.text = copy(if (state.busy) R.string.light_reading else R.string.light_refresh)
            hint.text = copy(when {
                light == null -> R.string.light_setup_hint
                !light.on -> R.string.light_off_hint
                !light.whiteMode -> R.string.light_mode_hint
                else -> R.string.light_white_hint
            })
            if (!state.busy && initialRead && light != null) {
                initialRead = false
                if (refresh.hasFocus()) power.requestFocus()
            } else if (focused != null && !focused.isEnabled) {
                (if (power.isEnabled) power else refresh).requestFocus()
            }
        }
        refresh.requestFocus()
    }
}
