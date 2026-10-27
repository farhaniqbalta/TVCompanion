package id.poncol.tvcompanion.notifications.presentation

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.*
import id.poncol.tvcompanion.application.NotificationController
import id.poncol.tvcompanion.designsystem.TvUi
import id.poncol.tvcompanion.notifications.infrastructure.AndroidNotificationRuntime
import id.poncol.tvcompanion.notifications.infrastructure.ReceiverSettings

class NotificationSettingsActivity : Activity() {
    private lateinit var controller: NotificationController
    private val settings get() = controller.preferences
    private val runtime get() = controller.runtime
    private data class Screen(val status: TextView, val receipt: TextView, val address: TextView,
        val token: TextView, val preview: LinearLayout, val start: Button)
    private var screen: Screen? = null
    private var portDialog: AlertDialog? = null
    private val status get() = checkNotNull(screen).status
    private val receipt get() = checkNotNull(screen).receipt
    private val address get() = checkNotNull(screen).address
    private val token get() = checkNotNull(screen).token
    private val preview get() = checkNotNull(screen).preview
    private val start get() = checkNotNull(screen).start
    private var previewSource = "grab"
    private val updates = android.os.Handler(android.os.Looper.getMainLooper())
    private val changes = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refresh() }
    }
    private val ui by lazy { TvUi(this) }
    private fun dp(value: Int) = ui.dp(value)
    private fun column() = ui.column()
    private fun label(parent: LinearLayout, value: String, size: Float = 18f, bold: Boolean = false) = ui.text(parent, value, size, bold)
    private fun panel(parent: LinearLayout, title: String) = ui.panel(parent, title)
    private fun button(parent: LinearLayout, value: String, action: () -> Unit) = ui.button(parent, value, action)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
    }
    private fun buildScreen() {
        val preferences = ReceiverSettings(applicationContext)
        controller = NotificationController(
            preferences, AndroidNotificationRuntime(applicationContext, preferences))
        val root = ui.page()
        label(root, "TVCompanion", 32f, true)
        label(root, "Quiet updates, wherever you're watching.  ·  Local network prototype", 18f)
        val columns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(columns, LinearLayout.LayoutParams(-1,0,1f))
        val left = column()
        columns.addView(ScrollView(this).apply { isFillViewport = false; addView(left) }, LinearLayout.LayoutParams(0,-1,1.1f).apply { marginEnd = dp(18) })
        val right = column()
        columns.addView(ScrollView(this).apply { addView(right) }, LinearLayout.LayoutParams(0,-1,1f))
        val connection = panel(left, "Receiver")
        val status = label(connection, "")
        val address = label(connection, "", 16f)
        val receipt = label(connection, runtime.lastReceipt, 15f)
        val start = button(connection, "Start receiver") { if (runtime.running) pause() else startReceiver() }
        button(connection, "Allow display over other apps") {
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
        button(connection, "Change listening port") { editPort() }
        val sources = panel(left, "Notification sources")
        for ((id, theme) in NotificationThemes.all) {
            lateinit var control: Button
            control = button(sources, "${theme.name}: ${if (settings.enabled(id)) "on" else "muted"}") {
                controller.toggleSource(id)
                control.text = "${theme.name}: ${if (settings.enabled(id)) "on" else "muted"}"
            }
        }
        val presentation = panel(left, "Presentation")
        lateinit var mode: Button
        mode = button(presentation, "Style: ${settings.mode}") {
            controller.cycleMode()
            mode.text = "Style: ${settings.mode}"; updatePreview()
        }
        label(presentation, "Privacy shows only the source and a generic update. Compact hides the body.", 15f)
        lateinit var duration: Button
        duration = button(presentation, "Dismiss after ${settings.duration} seconds") {
            controller.cycleDuration()
            duration.text = "Dismiss after ${settings.duration} seconds"
        }
        lateinit var motion: Button
        motion = button(presentation, "Reduced motion: ${if (settings.reducedMotion) "on" else "off"}") {
            controller.toggleMotion()
            motion.text = "Reduced motion: ${if (settings.reducedMotion) "on" else "off"}"
        }
        val previewPanel = panel(right, "Preview")
        label(previewPanel, "Demo content · the same card used by the overlay", 16f)
        val preview = column(); previewPanel.addView(preview)
        for ((id, theme) in NotificationThemes.all) button(previewPanel, "Preview ${theme.name}") {
            previewSource = id; updatePreview()
            if (!controller.preview(id)) Toast.makeText(this, "Local preview only. Start receiver and enable the source for an overlay.", Toast.LENGTH_LONG).show()
        }
        val pairing = panel(right, "Local access")
        label(pairing, "6-digit PIN · enter this in your sender", 16f)
        val token = label(pairing, runtime.pin, 16f)
        button(pairing, "Change PIN and pause receiver") {
            token.text = controller.rotatePin()
            updates.postDelayed({ refresh() }, 300)
            receipt.text = "PIN changed. Update your sender before restarting."
        }
        label(pairing, "HTTP is unencrypted. Use trusted Wi-Fi and non-sensitive demo messages. No message history is saved.", 15f)
        label(right, "No phone pairing or automatic forwarding is verified. After reboot, reopen TVCompanion and start the receiver. If overlays are blocked, use the local preview above.", 15f)
        screen = Screen(status, receipt, address, token, preview, start)
        setContentView(root)
        updatePreview(); refresh(); start.requestFocus()
    }
    private fun updatePreview() {
        preview.removeAllViews()
        preview.addView(NotificationCard(this, NotificationThemes.demo(previewSource, settings.duration), settings.mode))
    }
    private fun startReceiver() {
        if (!runtime.overlayAllowed) {
            Toast.makeText(this, "Allow display over other apps first. Local previews still work.", Toast.LENGTH_LONG).show(); return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        controller.start()
        updates.postDelayed({ refresh() }, 700)
    }
    private fun pause() {
        controller.pause()
        updates.postDelayed({ refresh() }, 300)
    }
    private fun editPort() {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(settings.port.toString()) }
        val dialog = AlertDialog.Builder(this).setTitle("Listening port (1024–65535)").setMessage("Changing the port pauses the receiver. Update your sender URL.")
            .setView(input).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = input.text.toString().toIntOrNull()
            if (value == null || value !in 1024..65535) input.error = "Use a port from 1024 to 65535"
            else { controller.changePort(value); updates.postDelayed({ refresh() }, 300); address.text = runtime.addresses; dialog.dismiss() }
        } }
        portDialog = dialog
        dialog.show()
    }
    private fun refresh() {
        status.text = "${runtime.state}  ·  Overlay ${if (runtime.overlayAllowed) "allowed" else "permission needed"}"
        start.text = if (runtime.running) "Pause receiver" else "Start receiver"
        address.text = runtime.addresses; receipt.text = runtime.lastReceipt
    }
    override fun onStart() {
        super.onStart()
        val filter = IntentFilter("$packageName.STATE_CHANGED")
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(changes, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(changes, filter)
        refresh()
    }
    override fun onStop() {
        unregisterReceiver(changes)
        updates.removeCallbacksAndMessages(null)
        // Break references to heavy screen trees while only the receiver is needed.
        portDialog?.dismiss(); portDialog = null
        screen = null
        setContentView(FrameLayout(this))
        super.onStop()
    }
    override fun onRestart() { super.onRestart(); buildScreen() }
    override fun onResume() { super.onResume(); if (screen != null) refresh() }
}
