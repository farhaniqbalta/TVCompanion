package id.poncol.tvcompanion.notifications.presentation

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.*
import id.poncol.tvcompanion.application.NotificationController
import id.poncol.tvcompanion.designsystem.TvUi
import id.poncol.tvcompanion.notifications.R
import id.poncol.tvcompanion.notifications.infrastructure.AndroidNotificationRuntime
import id.poncol.tvcompanion.notifications.infrastructure.ReceiverSettings

/** Category navigation, view bindings and permission UI only. Feature policy stays in the controller. */
class NotificationSettingsActivity : Activity() {
    private lateinit var controller: NotificationController
    private val settings get() = controller.preferences
    private val runtime get() = controller.runtime
    private val ui by lazy { TvUi(this) }
    private var selectedSection = R.string.notifications_receiver
    private var previewSource = "grab"
    private var pinVisible = false
    private var dialog: AlertDialog? = null
    private val sections = listOf(R.string.notifications_receiver, R.string.notifications_appearance,
        R.string.notifications_sources, R.string.notifications_connection)
    private class Screen(val content: LinearLayout, val preview: LinearLayout, val rail: List<Button>) {
        var status: TextView? = null
        var receipt: TextView? = null
        var receiverAction: Button? = null
        var address: TextView? = null
        var pin: TextView? = null
        var pinAction: Button? = null
        var sample: NotificationCard? = null
    }
    private var screen: Screen? = null
    private val changes = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refresh() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedSection = savedInstanceState?.getInt("section", selectedSection)?.takeIf { it in sections } ?: selectedSection
        previewSource = savedInstanceState?.getString("preview")?.takeIf { it in NotificationThemes.all } ?: previewSource
        buildScreen()
    }
    private fun buildScreen() {
        val preferences = ReceiverSettings(applicationContext)
        controller = NotificationController(preferences, AndroidNotificationRuntime(applicationContext, preferences))
        val root = ui.page()
        ui.brandHeader(root, getString(R.string.notifications_eyebrow))
        ui.text(root, getString(R.string.notifications_title), 30f, true)
        ui.text(root, getString(R.string.notifications_subtitle), 16f)
        ui.spacer(root, 12)
        val columns = ui.row().apply { gravity = Gravity.TOP }
        root.addView(columns, LinearLayout.LayoutParams(-1, 0, 1f))
        val rail = ui.column()
        columns.addView(rail, LinearLayout.LayoutParams(ui.dp(152), -1).apply { marginEnd = ui.dp(20) })
        val tabs = sections.map { section -> ui.button(rail, getString(section)) { showSection(section) } }
        ui.spacer(rail, 16)
        ui.button(rail, getString(R.string.notifications_home)) { finish() }
        val body = ui.column()
        val scroll = ScrollView(this).apply { addView(body); isFillViewport = true }
        columns.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f))
        val preview = ui.column()
        if (resources.configuration.screenWidthDp >= 900 && resources.configuration.fontScale <= 1.2f) {
            val previewScroll = ScrollView(this).apply { addView(preview) }
            columns.addView(previewScroll, LinearLayout.LayoutParams(ui.dp(320), -1).apply { marginStart = ui.dp(20) })
        } else {
            // Narrow/large-text layouts keep every setting reachable without a cramped fixed pane.
            body.addView(preview)
        }
        ui.text(root, getString(R.string.notifications_footer), 13f)
        screen = Screen(body, preview, tabs)
        setContentView(root)
        showSection(selectedSection)
        updatePreview()
        tabs[sections.indexOf(selectedSection)].requestFocus()
    }
    private fun showSection(section: Int) {
        val binding = screen ?: return
        val restoreCategoryFocus = binding.content.hasFocus()
        selectedSection = section; pinVisible = false
        binding.rail.forEachIndexed { index, button -> button.isSelected = sections[index] == section }
        // A fallback inline preview is reattached after the section body is replaced.
        (binding.preview.parent as? LinearLayout)?.removeView(binding.preview)
        binding.content.removeAllViews()
        binding.status = null; binding.receipt = null; binding.receiverAction = null
        binding.address = null; binding.pin = null; binding.pinAction = null
        val panel = ui.panel(binding.content, getString(section))
        when (section) {
            R.string.notifications_receiver -> {
                binding.status = ui.text(panel, ReceiverStatusText.status(this, runtime.snapshot), 22f, true)
                ui.text(panel, getString(R.string.notifications_receiver_hint), 16f)
                binding.receiverAction = ui.button(panel, "") { if (runtime.running) pause() else startReceiver() }
                if (!runtime.overlayAllowed) {
                    ui.button(panel, getString(R.string.notifications_permission_needed)) { openOverlaySettings() }
                    ui.text(panel, getString(R.string.notifications_permission_hint), 14f)
                } else ui.text(panel, getString(R.string.notifications_permission_allowed), 14f).setTextColor(ui.accent)
                ui.spacer(panel, 12)
                binding.receipt = ui.text(panel, ReceiverStatusText.receipt(this, runtime.snapshot), 14f)
                ui.text(panel, getString(R.string.notifications_restart_hint), 14f)
            }
            R.string.notifications_appearance -> {
                val mode = ui.button(panel, getString(R.string.notifications_style, modeLabel())) {}
                mode.setOnClickListener { chooseMode(mode) }
                ui.text(panel, getString(R.string.notifications_style_hint), 14f)
                val duration = ui.button(panel, resources.getQuantityString(R.plurals.notifications_duration, settings.duration, settings.duration)) {}
                duration.setOnClickListener { chooseDuration(duration) }
                lateinit var motion: Button
                motion = ui.button(panel, motionLabel()) {
                    controller.toggleMotion(); motion.text = motionLabel()
                }
            }
            R.string.notifications_sources -> {
                ui.text(panel, getString(R.string.notifications_sources_hint), 16f)
                for ((id, theme) in NotificationThemes.all) {
                    lateinit var toggle: Button
                    toggle = ui.button(panel, sourceLabel(id, theme.name)) {
                        controller.toggleSource(id)
                        toggle.text = sourceLabel(id, theme.name); toggle.isSelected = settings.enabled(id)
                    }
                    toggle.isSelected = settings.enabled(id)
                }
            }
            R.string.notifications_connection -> {
                ui.text(panel, getString(R.string.notifications_pairing_hint), 16f)
                binding.address = ui.text(panel, runtime.addresses, 16f)
                binding.pin = ui.text(panel, getString(R.string.notifications_pin_hidden), 28f, true)
                binding.pinAction = ui.button(panel, getString(R.string.notifications_pin_show)) {
                    pinVisible = !pinVisible; refresh()
                }
                ui.button(panel, getString(R.string.notifications_port, settings.port)) { editPort() }
                ui.button(panel, getString(R.string.notifications_pin_rotate)) { confirmPinReset() }
                ui.text(panel, getString(R.string.notifications_privacy_notice), 14f)
            }
        }
        if (resources.configuration.screenWidthDp < 900 || resources.configuration.fontScale > 1.2f) binding.content.addView(binding.preview)
        refresh()
        if (restoreCategoryFocus) binding.rail[sections.indexOf(section)].requestFocus()
    }
    private fun sourceLabel(id: String, name: String) = getString(R.string.notifications_source, name,
        getString(if (settings.enabled(id)) R.string.notifications_on else R.string.notifications_off))
    private fun modeLabel() = getString(when (settings.mode) {
        "compact" -> R.string.notifications_style_compact
        "expanded" -> R.string.notifications_style_expanded
        else -> R.string.notifications_style_privacy
    })
    private fun motionLabel() = getString(R.string.notifications_motion,
        getString(if (settings.reducedMotion) R.string.notifications_on else R.string.notifications_off))
    private fun chooseMode(control: Button) {
        val modes = listOf("privacy", "compact", "expanded")
        val names = arrayOf(getString(R.string.notifications_style_privacy), getString(R.string.notifications_style_compact), getString(R.string.notifications_style_expanded))
        dialog = AlertDialog.Builder(this).setTitle(R.string.notifications_style_title)
            .setSingleChoiceItems(names, modes.indexOf(settings.mode)) { selected, index ->
                controller.selectMode(modes[index]); control.text = getString(R.string.notifications_style, modeLabel())
                updatePreview(); selected.dismiss()
            }.setNegativeButton(R.string.notifications_cancel, null).create().also { it.show() }
    }
    private fun chooseDuration(control: Button) {
        val values = listOf(5, 8, 12, 20, 30)
        dialog = AlertDialog.Builder(this).setTitle(R.string.notifications_duration_title)
            .setSingleChoiceItems(values.map { resources.getQuantityString(R.plurals.notifications_seconds, it, it) }.toTypedArray(), values.indexOf(settings.duration)) { selected, index ->
                controller.selectDuration(values[index]); control.text = resources.getQuantityString(R.plurals.notifications_duration, settings.duration, settings.duration)
                selected.dismiss()
            }.setNegativeButton(R.string.notifications_cancel, null).create().also { it.show() }
    }
    private fun updatePreview() {
        val parent = screen?.preview ?: return
        parent.removeAllViews()
        val panel = ui.panel(parent, getString(R.string.notifications_preview))
        ui.eyebrow(panel, getString(R.string.notifications_demo))
        val sample = NotificationCard(this, NotificationThemes.demo(previewSource, settings.duration), settings.mode)
        screen?.sample = sample
        panel.addView(sample)
        ui.spacer(panel, 10)
        val sources = NotificationThemes.all.entries.toList()
        for (pair in sources.chunked(2)) {
            val row = ui.row(); panel.addView(row)
            pair.forEachIndexed { index, (id, theme) ->
                val action = ui.button(row, theme.name) {
                    previewSource = id
                    updatePreviewCard(panel)
                    if (!controller.preview(id)) Toast.makeText(this, R.string.notifications_local_preview, Toast.LENGTH_LONG).show()
                }
                action.contentDescription = getString(R.string.notifications_preview_source, theme.name)
                action.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                    topMargin = ui.dp(8); if (index == 0) marginEnd = ui.dp(8)
                }
                action.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10)); action.textSize = 14f
            }
        }
        ui.text(panel, getString(R.string.notifications_preview_hint), 13f)
    }
    private fun updatePreviewCard(panel: LinearLayout) {
        val binding = screen ?: return
        val previous = binding.sample ?: return
        val index = panel.indexOfChild(previous)
        if (index < 0) return
        panel.removeView(previous)
        val sample = NotificationCard(this, NotificationThemes.demo(previewSource, settings.duration), settings.mode)
        binding.sample = sample
        panel.addView(sample, index)
    }
    private fun openOverlaySettings() {
        try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
    private fun startReceiver() {
        if (!runtime.overlayAllowed) { Toast.makeText(this, R.string.notifications_permission_toast, Toast.LENGTH_LONG).show(); return }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        controller.start()
    }
    private fun pause() { controller.pause() }
    private fun confirmPinReset() {
        dialog = AlertDialog.Builder(this).setTitle(R.string.notifications_pin_confirm_title)
            .setMessage(R.string.notifications_pin_confirm_body).setNegativeButton(R.string.notifications_cancel, null)
            .setPositiveButton(R.string.notifications_reset) { _, _ ->
                controller.rotatePin(); pinVisible = false; refresh()
                Toast.makeText(this, R.string.notifications_pin_reset, Toast.LENGTH_LONG).show()
            }.create().also { it.show() }
    }
    private fun editPort() {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(settings.port.toString()) }
        val editor = AlertDialog.Builder(this).setTitle(R.string.notifications_port_title).setMessage(R.string.notifications_port_hint)
            .setView(input).setNegativeButton(R.string.notifications_cancel, null).setPositiveButton(R.string.notifications_save, null).create()
        editor.setOnShowListener { editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = input.text.toString().toIntOrNull()
            if (value == null || value !in 1024..65535) input.error = getString(R.string.notifications_port_error)
            else {
                controller.changePort(value); editor.dismiss(); showSection(selectedSection)
            }
        } }
        dialog = editor; editor.show()
    }
    private fun refresh() {
        val binding = screen ?: return
        binding.status?.text = ReceiverStatusText.status(this, runtime.snapshot)
        binding.receipt?.text = ReceiverStatusText.receipt(this, runtime.snapshot)
        binding.receiverAction?.setText(if (runtime.running) R.string.notifications_pause else R.string.notifications_start)
        binding.address?.text = runtime.addresses
        binding.pin?.text = if (pinVisible) runtime.pin else getString(R.string.notifications_pin_hidden)
        binding.pinAction?.setText(if (pinVisible) R.string.notifications_pin_hide else R.string.notifications_pin_show)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("section", selectedSection); outState.putString("preview", previewSource)
        super.onSaveInstanceState(outState)
    }
    override fun onStart() {
        super.onStart()
        val filter = IntentFilter("$packageName.STATE_CHANGED")
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(changes, filter, RECEIVER_NOT_EXPORTED) else registerReceiver(changes, filter)
        refresh()
    }
    override fun onStop() {
        unregisterReceiver(changes)
        dialog?.dismiss(); dialog = null; pinVisible = false
        screen = null; setContentView(FrameLayout(this)); super.onStop()
    }
    override fun onRestart() { super.onRestart(); buildScreen() }
    override fun onResume() { super.onResume(); refresh() }
}
