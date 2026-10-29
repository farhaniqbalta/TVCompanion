package id.poncol.tvcompanion.smarthome

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.*
import id.poncol.tvcompanion.application.LightController
import id.poncol.tvcompanion.domain.LightCommand
import id.poncol.tvcompanion.domain.LightColor
import id.poncol.tvcompanion.domain.LightState
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
        val wide = resources.configuration.screenWidthDp >= 900 && resources.configuration.fontScale <= 1.25f
        val columns = if (wide) ui.row().apply { gravity = Gravity.TOP }.also { root.addView(it) } else root
        val device = ui.panel(columns, "")
        if (wide) device.layoutParams = LinearLayout.LayoutParams(0, -2, .95f).apply { marginEnd = ui.dp(20) }
        val bulb = ui.icon(device, DesignR.drawable.ic_bulb, ui.warm, 64)
        ui.spacer(device, 14)
        ui.text(device, copy(R.string.light_device), 17f)
        val status = ui.text(device, copy(R.string.light_unavailable), 27f, true)
        val detail = ui.text(device, "", 15f).apply { minLines = 2 }
        val controls = ui.panel(columns, copy(R.string.light_controls))
        if (wide) controls.layoutParams = LinearLayout.LayoutParams(0, -2, 1.25f)
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
        val colors = ui.panel(root, copy(R.string.light_color_title))
        val colorStatus = ui.text(colors, copy(R.string.light_color_unknown), 20f, true)
        val colorHint = ui.text(colors, copy(R.string.light_color_unavailable), 16f)
        val white = ui.button(colors, copy(R.string.light_white_mode)) { controller.execute(LightCommand.WhiteMode) }
        ui.text(colors, copy(R.string.light_color_presets_help), 16f)
        val presets = listOf(
            R.string.light_red to 0, R.string.light_orange to 30, R.string.light_yellow to 60,
            R.string.light_green to 120, R.string.light_blue to 240, R.string.light_purple to 280)
        val colorButtons = mutableListOf<Button>()
        val presetButtons = mutableListOf<Pair<Button, Int>>()
        presets.chunked(if (wide) 3 else 2).forEach { group ->
            val row = ui.row(); colors.addView(row)
            group.forEach { (label, hue) ->
                val button = ui.button(row, copy(label)) {
                    controller.state.light?.let { ColorControls.base(it) }?.let {
                        controller.execute(LightCommand.Color(it.copy(hue = hue, saturation = 1000)))
                    }
                }
                button.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginEnd = ui.dp(8); topMargin = ui.dp(8)
                }
                colorButtons.add(button); presetButtons.add(button to hue)
            }
        }
        fun adjustment(label: Int, decreaseLabel: Int, increaseLabel: Int, change: (LightColor, Int) -> LightColor): TextView {
            ui.spacer(colors, 12); ui.eyebrow(colors, copy(label))
            val row = ui.row(); colors.addView(row)
            val decrease = ui.button(row, copy(decreaseLabel)) {
                controller.state.light?.let { ColorControls.base(it) }?.let { controller.execute(LightCommand.Color(change(it, -1))) }
            }
            decrease.contentDescription = context.getString(R.string.light_adjust_accessibility, copy(label), copy(decreaseLabel))
            decrease.layoutParams = LinearLayout.LayoutParams(ui.dp(136), -2)
            val value = ui.text(row, "", 23f, true).apply {
                gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            val increase = ui.button(row, copy(increaseLabel)) {
                controller.state.light?.let { ColorControls.base(it) }?.let { controller.execute(LightCommand.Color(change(it, 1))) }
            }
            increase.contentDescription = context.getString(R.string.light_adjust_accessibility, copy(label), copy(increaseLabel))
            increase.layoutParams = LinearLayout.LayoutParams(ui.dp(136), -2)
            colorButtons.add(decrease); colorButtons.add(increase)
            return value
        }
        val hue = adjustment(R.string.light_hue, R.string.light_hue_less, R.string.light_hue_more, ColorControls::hue)
        val saturation = adjustment(R.string.light_saturation, R.string.light_percent_less, R.string.light_percent_more, ColorControls::saturation)
        val colorBrightness = adjustment(R.string.light_color_brightness, R.string.light_percent_less, R.string.light_percent_more, ColorControls::value)
        ui.spacer(root, 12)
        ui.text(root, copy(R.string.light_footer), 13f)
        var initialRead = true
        controller.observe { state ->
            val light = state.light
            val focused = findFocus()
            status.text = copy(if (light == null) R.string.light_unavailable else if (light.on) R.string.light_on else R.string.light_off)
            detail.text = state.message
            val confirmedColor = light?.color
            val tint = if (light?.on != true) ui.muted else if (light.colorMode && confirmedColor != null)
                android.graphics.Color.HSVToColor(floatArrayOf(confirmedColor.hue.toFloat(), confirmedColor.saturation / 1000f, (confirmedColor.value / 1000f).coerceAtLeast(.35f))) else ui.warm
            bulb.imageTintList = ColorStateList.valueOf(tint)
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
            colorStatus.text = copy(when {
                light == null -> R.string.light_color_unknown
                light.whiteMode -> R.string.light_white_active
                light.colorMode -> R.string.light_color_active
                else -> R.string.light_other_mode
            })
            colorHint.text = copy(when {
                light == null -> R.string.light_setup_hint
                !light.on -> R.string.light_color_off_hint
                !light.supportsColor -> R.string.light_color_unavailable
                else -> R.string.light_color_hint
            })
            white.isEnabled = light?.supportsWhiteMode == true && !light.whiteMode
            colorButtons.forEach { it.isEnabled = light?.supportsColor == true }
            presetButtons.forEach { (button, presetHue) ->
                button.isSelected = light?.on == true && light.colorMode && confirmedColor?.hue?.rem(360) == presetHue && confirmedColor.saturation == 1000
            }
            hue.text = confirmedColor?.let { context.getString(R.string.light_hue_value, it.hue) } ?: copy(R.string.light_brightness_unknown)
            saturation.text = confirmedColor?.let { context.getString(R.string.light_brightness_value, Math.round(it.saturation / 10f)) } ?: copy(R.string.light_brightness_unknown)
            colorBrightness.text = confirmedColor?.let { context.getString(R.string.light_brightness_value, Math.round(it.value / 10f)) } ?: copy(R.string.light_brightness_unknown)
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
