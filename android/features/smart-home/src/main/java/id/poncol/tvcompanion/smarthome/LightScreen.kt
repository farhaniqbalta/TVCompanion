package id.poncol.tvcompanion.smarthome

import android.content.Context
import android.widget.*
import id.poncol.tvcompanion.application.LightController
import id.poncol.tvcompanion.domain.*
import id.poncol.tvcompanion.designsystem.TvUi

/** Rendering/input only. Replace this screen without changing device behavior. */
class LightScreen(context: Context, private val controller: LightController, back: () -> Unit) : ScrollView(context) {
    init {
        isFillViewport = true
        val ui = TvUi(context)
        val root = ui.page(); addView(root)
        ui.text(root, "TVCOMPANION  /  SMART HOME", 13f)
        ui.text(root, "A little light. Your way.", 32f, true)
        ui.text(root, "Bardi light · direct local connection", 17f)
        val panel = ui.panel(root, "Your light")
        val status = ui.text(panel, "Status not read", 24f, true)
        val detail = ui.text(panel, "", 16f)
        val on = ui.button(panel, "Turn on") { controller.execute(LightCommand.Power(true)) }
        val off = ui.button(panel, "Turn off") { controller.execute(LightCommand.Power(false)) }
        val brightness = ui.text(panel, "White brightness —", 18f)
        val less = ui.button(panel, "Dim − 10%") {
            controller.state.light?.brightness?.let { controller.execute(LightCommand.Brightness((it - 10).coerceAtLeast(0))) }
        }
        val more = ui.button(panel, "Brighten + 10%") {
            controller.state.light?.brightness?.let { controller.execute(LightCommand.Brightness((it + 10).coerceAtMost(100))) }
        }
        val refresh = ui.button(panel, "Refresh status") { controller.refresh() }
        ui.text(root, "Brightness is available when the light is on in white mode.", 15f)
        ui.button(root, "Back to home", back)
        controller.observe { state ->
            val light = state.light
            status.text = if (light == null) "No confirmed status" else if (light.on) "Light is on" else "Light is off"
            detail.text = state.message
            brightness.text = "White brightness  ${light?.brightness?.let { "$it%" } ?: "—"}"
            on.isEnabled = !state.busy && light != null && !light.on
            off.isEnabled = !state.busy && light?.on == true
            less.isEnabled = !state.busy && light?.supportsBrightness == true && light.brightness!! > 0
            more.isEnabled = !state.busy && light?.supportsBrightness == true && light.brightness!! < 100
            refresh.isEnabled = !state.busy
            // Disabled command controls must not strand remote focus.
            if (!state.busy && listOf(on, off, less, more).any { it.hasFocus() && !it.isEnabled }) refresh.requestFocus()
        }
        refresh.requestFocus()
    }
}
