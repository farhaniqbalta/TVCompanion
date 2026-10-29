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
                state.phase == AirPlayPhase.FAILED -> R.string.airplay_error
                state.phase == AirPlayPhase.STARTING -> R.string.airplay_starting
                state.phase == AirPlayPhase.STOPPING -> R.string.airplay_stopping
                state.connection == AirPlayConnection.PLAYING -> R.string.airplay_playing
                state.phase == AirPlayPhase.READY -> R.string.airplay_ready
                else -> R.string.airplay_idle
            })
            stop.isEnabled = state.phase in listOf(AirPlayPhase.READY, AirPlayPhase.STARTING)
        } }
    }
    override fun onStop() { subscription?.close(); subscription = null; control?.close(); control = null; super.onStop() }
}
