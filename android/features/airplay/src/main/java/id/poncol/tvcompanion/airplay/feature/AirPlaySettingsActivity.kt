package id.poncol.tvcompanion.airplay.feature

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Button
import id.poncol.tvcompanion.designsystem.TvUi
import id.poncol.tvcompanion.airplay.ipc.AirPlayWire
import id.poncol.tvcompanion.domain.airplay.*

/** Presentation observes a pure control port; implementation and native state stay behind adapters. */
class AirPlaySettingsActivity : Activity() {
    private var control: AirPlayReceiverControl? = null
    private var subscription: AutoCloseable? = null
    private lateinit var status: TextView
    private lateinit var stop: Button
    private lateinit var keepAvailable: Button
    private lateinit var keepSession: Button
    private lateinit var backgroundAudio: Button
    private lateinit var autoOpenVideo: Button
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val ui = TvUi(this); val page = ui.page()
        ui.button(page, getString(R.string.airplay_back)) { finish() }
        ui.spacer(page, 12); ui.eyebrow(page, getString(R.string.airplay_eyebrow))
        ui.text(page, getString(R.string.airplay_title), 34f, true)
        ui.text(page, getString(R.string.airplay_description), 18f)
        val panel = ui.panel(page, getString(R.string.airplay_status))
        status = ui.text(panel, getString(R.string.airplay_binding), 20f, true)
        val open = ui.button(panel, getString(R.string.airplay_open)) {
            startActivity(Intent().setClassName(packageName, AirPlayWire.PLAYBACK))
        }
        keepAvailable = ui.button(panel, getString(R.string.airplay_keep_off)) {
            val enabled = control?.snapshot?.keepAvailable == true
            if (!enabled && android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 301)
            } else changeAvailability(!enabled)
        }
        keepSession = ui.button(panel, "") { changeOptions(session = !(control?.snapshot?.keepSession ?: true)) }
        backgroundAudio = ui.button(panel, "") { changeOptions(audio = !(control?.snapshot?.backgroundAudio ?: false)) }
        autoOpenVideo = ui.button(panel, "") { changeOptions(autoOpen = !(control?.snapshot?.autoOpenVideo ?: true)) }
        stop = ui.button(panel, getString(R.string.airplay_stop)) {
            control?.stop { if (!it.accepted) status.setText(R.string.airplay_error) }
        }
        ui.text(page, getString(R.string.airplay_limits), 16f)
        ui.text(page, getString(R.string.airplay_lifetime), 16f)
        ui.button(page, getString(R.string.airplay_licenses)) {
            val copy = try { assets.open("third-party-notices.txt").bufferedReader().use { it.readText() } }
            catch (_: java.io.IOException) { getString(R.string.airplay_sources_missing) }
            AlertDialog.Builder(this).setTitle(R.string.airplay_licenses).setMessage(copy).setPositiveButton(android.R.string.ok, null).show()
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(page) }); open.requestFocus()
    }
    override fun onStart() {
        super.onStart()
        control = AndroidAirPlayControl(this).also { client -> subscription = client.observe { state ->
            status.setText(when {
                state.availability != AirPlayAvailability.AVAILABLE && state.issue != null -> R.string.airplay_error
                state.availability != AirPlayAvailability.AVAILABLE -> R.string.airplay_binding
                state.issue == AirPlayIssue.AUDIO_UNAVAILABLE -> R.string.airplay_audio_unavailable
                state.issue == AirPlayIssue.DISPLAY_REQUIRED -> R.string.airplay_display_required
                state.phase == AirPlayPhase.FAILED -> R.string.airplay_error
                state.phase == AirPlayPhase.STARTING -> R.string.airplay_starting
                state.phase == AirPlayPhase.STOPPING -> R.string.airplay_stopping
                state.connection == AirPlayConnection.PLAYING -> R.string.airplay_playing
                state.phase == AirPlayPhase.READY -> R.string.airplay_ready
                else -> R.string.airplay_idle
            })
            keepAvailable.setText(if (state.keepAvailable) R.string.airplay_keep_on else R.string.airplay_keep_off)
            keepAvailable.isEnabled = state.availability == AirPlayAvailability.AVAILABLE && state.phase != AirPlayPhase.STOPPING
            keepSession.text = getString(R.string.airplay_session_option, getString(if (state.keepSession) R.string.airplay_on else R.string.airplay_off))
            backgroundAudio.text = getString(R.string.airplay_audio_option, getString(if (state.backgroundAudio) R.string.airplay_on else R.string.airplay_off))
            autoOpenVideo.text = getString(R.string.airplay_auto_option, getString(if (state.autoOpenVideo) R.string.airplay_on else R.string.airplay_off))
            listOf(keepSession, backgroundAudio, autoOpenVideo).forEach { it.isEnabled = state.availability == AirPlayAvailability.AVAILABLE && state.phase != AirPlayPhase.STOPPING }
            stop.isEnabled = state.phase in listOf(AirPlayPhase.READY, AirPlayPhase.STARTING)
        } }
    }
    private fun changeOptions(session: Boolean? = null, audio: Boolean? = null, autoOpen: Boolean? = null) {
        val client = control ?: return
        val state = client.snapshot
        client.setBackgroundOptions(session ?: state.keepSession, audio ?: state.backgroundAudio, autoOpen ?: state.autoOpenVideo) {
            if (!it.accepted) status.setText(R.string.airplay_error)
        }
    }
    private fun changeAvailability(enabled: Boolean) {
        keepAvailable.isEnabled = false
        control?.setKeepAvailable(enabled) { result ->
            keepAvailable.isEnabled = true
            if (!result.accepted) status.setText(R.string.airplay_keep_failed)
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 301) {
            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) changeAvailability(true)
            else status.setText(R.string.airplay_notification_required)
        }
    }
    override fun onStop() { subscription?.close(); subscription = null; control?.close(); control = null; super.onStop() }
}
