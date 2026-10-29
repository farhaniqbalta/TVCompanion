package id.poncol.tvcompanion.airplay.feature

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import id.poncol.tvcompanion.designsystem.TvUi
import id.poncol.tvcompanion.airplay.ipc.AirPlayWire
import id.poncol.tvcompanion.domain.airplay.*

/** TV presentation only; status decisions and receiver commands have separate boundaries. */
class AirPlaySettingsActivity : Activity() {
    private var control: AirPlayReceiverControl? = null
    private var subscription: AutoCloseable? = null
    private lateinit var ui: TvUi
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var feedback: TextView
    private lateinit var audioHelp: TextView
    private lateinit var open: Button
    private lateinit var stop: Button
    private lateinit var retry: Button
    private lateinit var notificationSettings: Button
    private lateinit var listening: Button
    private lateinit var retention: Button
    private lateinit var audio: Button
    private lateinit var autoOpen: Button
    private val targets = linkedMapOf<String, Button>()
    private var pending = false
    private var lastFocus = "open"
    private var focusRestored = false
    private var permissionHelp = false

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        lastFocus = saved?.getString("airplay_focus") ?: "open"
        ui = TvUi(this)
        val page = ui.page()
        action(page, "back", getString(R.string.airplay_back)) { finish() }
        ui.spacer(page, 12)
        ui.eyebrow(page, getString(R.string.airplay_eyebrow))
        ui.text(page, getString(R.string.airplay_title), 34f, true)
        ui.text(page, getString(R.string.airplay_description), 18f)
        val wide = resources.configuration.screenWidthDp >= 900 && resources.configuration.fontScale <= 1.25f
        val body = if (wide) ui.row().also { it.gravity = android.view.Gravity.TOP; page.addView(it) } else page
        val primary = if (wide) ui.column().also {
            body.addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(24) })
        } else page
        val preferences = if (wide) ui.column().also { body.addView(it, LinearLayout.LayoutParams(0, -2, 1.15f)) } else page
        val receiver = ui.panel(primary, getString(R.string.airplay_receiver_name))
        status = ui.text(receiver, getString(R.string.airplay_binding), 23f, true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        detail = ui.text(receiver, "", 16f)
        feedback = ui.text(receiver, "", 16f).apply {
            visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        open = action(receiver, "open", getString(R.string.airplay_open)) {
            if (!pending && open.isEnabled) startActivity(Intent().setClassName(packageName, AirPlayWire.PLAYBACK))
        }
        stop = action(receiver, "stop", getString(R.string.airplay_stop)) { command { client, reply -> client.stop(reply) } }
        retry = action(receiver, "retry", getString(R.string.airplay_retry)) { bindControls() }
        notificationSettings = action(receiver, "notifications", getString(R.string.airplay_notification_settings)) {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        val guide = ui.panel(primary, getString(R.string.airplay_connect_title))
        ui.text(guide, getString(R.string.airplay_connect_mac), 16f)
        ui.text(guide, getString(R.string.airplay_connect_iphone), 16f)
        ui.text(guide, getString(R.string.airplay_connect_music), 16f)
        val background = ui.panel(preferences, getString(R.string.airplay_background_title))
        listening = action(background, "listening", "") { toggleListening() }
        ui.text(background, getString(R.string.airplay_listening_help), 16f)
        retention = action(background, "retention", "") { changeOptions(session = !(control?.snapshot?.keepSession ?: true)) }
        ui.text(background, getString(R.string.airplay_session_help), 16f)
        audio = action(background, "audio", "") { changeOptions(sound = !(control?.snapshot?.backgroundAudio ?: false)) }
        audioHelp = ui.text(background, getString(R.string.airplay_audio_help), 16f)
        autoOpen = action(background, "auto", "") { changeOptions(automatic = !(control?.snapshot?.autoOpenVideo ?: true)) }
        ui.text(background, getString(R.string.airplay_auto_help), 16f)
        ui.text(preferences, getString(R.string.airplay_lifetime), 16f)
        val about = ui.panel(page, getString(R.string.airplay_about))
        ui.text(about, getString(R.string.airplay_limits), 16f)
        action(about, "licenses", getString(R.string.airplay_licenses)) {
            val copy = try { assets.open("third-party-notices.txt").bufferedReader().use { it.readText() } }
            catch (_: java.io.IOException) { getString(R.string.airplay_sources_missing) }
            AlertDialog.Builder(this).setTitle(R.string.airplay_licenses).setMessage(copy).setPositiveButton(android.R.string.ok, null).show()
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(page) })
        render(AirPlaySnapshot())
    }

    private fun action(parent: LinearLayout, key: String, label: String, block: () -> Unit): Button =
        ui.button(parent, label, block).also { button ->
            targets[key] = button
            button.setOnFocusChangeListener { _, focused -> if (focused && focusRestored && !pending) lastFocus = key }
        }

    override fun onStart() { super.onStart(); bindControls() }
    private fun bindControls() {
        subscription?.close(); control?.close()
        pending = false; focusRestored = false; permissionHelp = false; feedback.visibility = View.GONE
        val client = AndroidAirPlayControl(this)
        control = client
        subscription = client.observe { render(it) }
    }
    private fun render(state: AirPlaySnapshot) {
        val model = AirPlayPresentation.from(state)
        status.setText(when (model.status) {
            AirPlayPresentation.Status.BINDING -> R.string.airplay_binding
            AirPlayPresentation.Status.UNAVAILABLE -> R.string.airplay_controls_unavailable
            AirPlayPresentation.Status.STOPPED -> R.string.airplay_idle
            AirPlayPresentation.Status.STARTING -> R.string.airplay_starting
            AirPlayPresentation.Status.STOPPING -> R.string.airplay_stopping
            AirPlayPresentation.Status.FAILED -> R.string.airplay_error
            AirPlayPresentation.Status.LISTENING -> R.string.airplay_ready
            AirPlayPresentation.Status.CONNECTED -> R.string.airplay_connected
            AirPlayPresentation.Status.AUDIO -> R.string.airplay_audio_playing
            AirPlayPresentation.Status.VIDEO -> R.string.airplay_video_playing
            AirPlayPresentation.Status.IDLE -> R.string.airplay_not_listening
        })
        detail.setText(when {
            state.issue == AirPlayIssue.AUDIO_UNAVAILABLE -> R.string.airplay_audio_unavailable
            state.issue == AirPlayIssue.DISPLAY_REQUIRED -> R.string.airplay_display_required
            model.status == AirPlayPresentation.Status.UNAVAILABLE -> R.string.airplay_controls_help
            model.status == AirPlayPresentation.Status.FAILED -> R.string.airplay_retry_help
            state.connection != AirPlayConnection.NONE && !state.discoverable -> R.string.airplay_private_session
            state.discoverable -> R.string.airplay_discovery_help
            else -> R.string.airplay_idle_help
        })
        open.setText(if (state.phase == AirPlayPhase.FAILED) R.string.airplay_retry_playback else R.string.airplay_open)
        open.isEnabled = model.canOpen
        stop.isEnabled = model.canStop
        retry.visibility = if (model.status == AirPlayPresentation.Status.UNAVAILABLE) View.VISIBLE else View.GONE
        notificationSettings.visibility = if (permissionHelp) View.VISIBLE else View.GONE
        listening.text = getString(R.string.airplay_listening_option, onOff(state.keepAvailable))
        retention.text = getString(R.string.airplay_session_option, onOff(state.keepSession))
        audio.text = getString(R.string.airplay_audio_option, if (!state.keepSession && state.backgroundAudio) getString(R.string.airplay_inactive) else onOff(model.backgroundAudioEffective))
        autoOpen.text = getString(R.string.airplay_auto_option, onOff(state.autoOpenVideo))
        audioHelp.setText(if (state.keepSession) R.string.airplay_audio_help else R.string.airplay_audio_requires_session)
        listOf(listening, retention, autoOpen).forEach { it.isEnabled = model.canChangePreferences }
        // Keep the explanation visible; an inactive dependent setting is not silently applied.
        audio.isEnabled = model.canChangePreferences && state.keepSession
        listOf(open, stop, listening, retention, audio, autoOpen).forEach {
            it.isClickable = !pending
            if (android.os.Build.VERSION.SDK_INT >= 30) it.stateDescription = if (pending) getString(R.string.airplay_working) else null
        }
        if (state.availability == AirPlayAvailability.AVAILABLE && !focusRestored) {
            focusRestored = true
            (targets[lastFocus]?.takeIf { it.isEnabled && it.visibility == View.VISIBLE } ?: open).requestFocus()
        } else if (focusRestored && !pending && currentFocus?.isEnabled != true) {
            (targets[lastFocus]?.takeIf { it.isEnabled && it.visibility == View.VISIBLE } ?: targets.values.firstOrNull { it.isEnabled && it.visibility == View.VISIBLE })?.requestFocus()
        }
    }
    private fun onOff(value: Boolean) = getString(if (value) R.string.airplay_on else R.string.airplay_off)
    private fun showIssue(issue: AirPlayIssue?) {
        feedback.setText(when (issue) {
            AirPlayIssue.TIMEOUT -> R.string.airplay_timeout
            AirPlayIssue.BUSY -> R.string.airplay_busy
            AirPlayIssue.UNSUPPORTED -> R.string.airplay_unsupported
            AirPlayIssue.UNAVAILABLE, AirPlayIssue.CLOSED -> R.string.airplay_controls_help
            AirPlayIssue.AUDIO_UNAVAILABLE -> R.string.airplay_audio_unavailable
            else -> R.string.airplay_retry_help
        })
        feedback.visibility = View.VISIBLE
    }
    private fun command(send: (AirPlayReceiverControl, (AirPlayResult) -> Unit) -> Unit) {
        val client = control ?: return
        if (pending) return
        pending = true; feedback.setText(R.string.airplay_working); feedback.visibility = View.VISIBLE
        render(client.snapshot)
        send(client) { result ->
            if (control !== client) return@send
            pending = false
            if (result.accepted) feedback.visibility = View.GONE else showIssue(result.issue)
            render(client.snapshot)
            targets[lastFocus]?.takeIf { it.isEnabled && it.visibility == View.VISIBLE }?.requestFocus()
        }
    }
    private fun toggleListening() {
        val state = control?.snapshot ?: return
        if (pending) return
        if (!state.keepAvailable && android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 301)
        } else changeListening(!state.keepAvailable)
    }
    private fun changeListening(enabled: Boolean) = command { client, reply ->
        client.setKeepAvailable(enabled) { result ->
            permissionHelp = enabled && !result.accepted && result.issue == AirPlayIssue.UNAVAILABLE
            reply(result)
            if (permissionHelp) { feedback.setText(R.string.airplay_notification_required); feedback.visibility = View.VISIBLE }
        }
    }
    private fun changeOptions(session: Boolean? = null, sound: Boolean? = null, automatic: Boolean? = null) = command { client, reply ->
        val state = client.snapshot
        client.setBackgroundOptions(session ?: state.keepSession, sound ?: state.backgroundAudio, automatic ?: state.autoOpenVideo, reply)
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code != 301) return
        if (results.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) changeListening(true)
        else {
            permissionHelp = true; feedback.setText(R.string.airplay_notification_required); feedback.visibility = View.VISIBLE
            control?.snapshot?.let(::render)
        }
    }
    override fun onSaveInstanceState(out: Bundle) { out.putString("airplay_focus", lastFocus); super.onSaveInstanceState(out) }
    override fun onStop() {
        subscription?.close(); subscription = null; control?.close(); control = null; pending = false
        super.onStop()
    }
}
