package id.poncol.tvcompanion.airplay.runtime

import android.app.Activity
import android.content.*
import android.os.Bundle
import android.os.IBinder
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import id.poncol.tvcompanion.domain.airplay.*

/** Playback and its Surface stay beside the engine in :airplay, never in control IPC. */
class AirPlayPlaybackActivity : Activity(), SurfaceHolder.Callback {
    private var host: AirPlayService.ReceiverBinder? = null
    private var bound = false
    private var display: Surface? = null
    private var active = false
    private var waitingForStop = false
    private lateinit var label: TextView
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (!active) return
            host = binder as? AirPlayService.ReceiverBinder
            if (host == null) { label.setText(R.string.airplay_failed); return }
            attach()
        }
        override fun onServiceDisconnected(name: ComponentName?) { host = null; if (active) label.setText(R.string.airplay_failed) }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val frame = FrameLayout(this)
        val video = SurfaceView(this); video.holder.addCallback(this)
        frame.addView(video, FrameLayout.LayoutParams(-1, -1))
        label = TextView(this).apply {
            setText(R.string.airplay_waiting); textSize = 16f; setTextColor(0xffffffff.toInt())
            setBackgroundColor(0xaa000000.toInt()); setPadding(24, 10, 24, 10)
        }
        frame.addView(label, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)); setContentView(frame)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    override fun onStart() {
        super.onStart(); active = true
        // User-visible activity owns the started lifetime only until native cleanup finishes.
        startService(Intent(this, AirPlayService::class.java))
        bound = bindService(Intent(this, AirPlayService::class.java), connection, BIND_AUTO_CREATE)
        if (!bound) label.setText(R.string.airplay_failed)
    }
    private fun attach() {
        val target = display?.takeIf { it.isValid } ?: return
        val service = host ?: return
        service.attachDisplay(target) { snapshot ->
            if (!active) return@attachDisplay
            if (waitingForStop && snapshot.phase == AirPlayPhase.STOPPED) {
                waitingForStop = false
                label.post { if (active) host?.beginPlayback(getString(R.string.airplay_receiver_name)) }
            }
            label.text = when {
                snapshot.issue == AirPlayIssue.AUDIO_UNAVAILABLE -> getString(R.string.airplay_audio_unavailable)
                snapshot.phase == AirPlayPhase.STOPPED -> getString(R.string.airplay_stopped)
                snapshot.phase == AirPlayPhase.FAILED -> getString(R.string.airplay_failed)
                snapshot.phase == AirPlayPhase.STOPPING -> getString(R.string.airplay_stopping)
                snapshot.connection == AirPlayConnection.PLAYING -> getString(R.string.airplay_playing)
                snapshot.connection == AirPlayConnection.CONNECTING -> getString(R.string.airplay_connecting)
                snapshot.phase == AirPlayPhase.READY -> getString(R.string.airplay_ready, getString(R.string.airplay_receiver_name))
                else -> getString(R.string.airplay_waiting)
            }
        }
        val result = service.beginPlayback(getString(R.string.airplay_receiver_name))
        waitingForStop = result.issue == AirPlayIssue.BUSY
        if (!result.accepted) label.setText(if (waitingForStop) R.string.airplay_stopping else R.string.airplay_failed)
    }
    override fun surfaceCreated(holder: SurfaceHolder) { display = holder.surface; attach() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { }
    override fun surfaceDestroyed(holder: SurfaceHolder) { display = null; host?.detachDisplay() }
    override fun onStop() {
        active = false; host?.detachDisplay(); host = null
        if (bound) { unbindService(connection); bound = false }
        super.onStop(); finish()
    }
}
