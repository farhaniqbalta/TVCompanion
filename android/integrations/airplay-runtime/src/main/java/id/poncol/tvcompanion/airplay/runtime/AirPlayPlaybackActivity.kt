package id.poncol.tvcompanion.airplay.runtime

import android.app.Activity
import android.content.*
import android.os.*
import android.view.*
import android.view.accessibility.AccessibilityManager
import android.widget.*
import id.poncol.tvcompanion.designsystem.TvUi
import id.poncol.tvcompanion.domain.airplay.*

/** Surface and receiver remain local to :airplay. UI observes facts; it never owns the engine. */
class AirPlayPlaybackActivity : Activity(), SurfaceHolder.Callback {
    private var host: AirPlayService.ReceiverBinder? = null
    private var bound = false
    private var display: Surface? = null
    private var active = false
    private var waitingForStop = false
    private var snapshot = AirPlaySnapshot()
    private lateinit var title: TextView
    private lateinit var detail: TextView
    private lateinit var leaveHint: TextView
    private lateinit var waitingHint: TextView
    private lateinit var waiting: View
    private lateinit var controls: View
    private lateinit var returnButton: Button
    private lateinit var retry: Button
    private val main = Handler(Looper.getMainLooper())
    private val hideControls = Runnable { if (snapshot.videoActive) { controls.clearFocus(); controls.visibility = View.GONE } }
    private var consumedKey: Int? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (!active) return
            host = binder as? AirPlayService.ReceiverBinder
            if (host == null) { unavailable(); return }
            attach()
        }
        override fun onServiceDisconnected(name: ComponentName?) { host = null; if (active) unavailable() }
    }
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val ui = TvUi(this)
        val frame = FrameLayout(this)
        val video = SurfaceView(this).also { it.holder.addCallback(this) }
        frame.addView(video, FrameLayout.LayoutParams(-1, -1))
        val page = ui.page()
        ui.eyebrow(page, getString(R.string.airplay_runtime_eyebrow))
        ui.text(page, getString(R.string.airplay_runtime_receiver_name), 34f, true)
        title = ui.text(page, getString(R.string.airplay_runtime_waiting), 25f, true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        detail = ui.text(page, getString(R.string.airplay_runtime_instructions), 18f)
        retry = ui.button(page, getString(R.string.airplay_runtime_retry)) { retryPlayback() }.apply { visibility = View.GONE }
        waitingHint = ui.text(page, "", 16f)
        ui.button(page, getString(R.string.airplay_runtime_return)) { finish() }
        ui.button(page, getString(R.string.airplay_runtime_stop_action)) { stopAndLeave() }
        waiting = ScrollView(this).apply { isFillViewport = true; addView(page) }
        frame.addView(waiting, FrameLayout.LayoutParams(-1, -1))
        val toolbar = ui.column().apply {
            background = ui.surface(); setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12))
        }
        leaveHint = ui.text(toolbar, getString(R.string.airplay_runtime_leave_connected), 16f)
        val actions = ui.row().also { toolbar.addView(it) }
        val backColumn = ui.column().also { actions.addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(16) }) }
        val stopColumn = ui.column().also { actions.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        returnButton = ui.button(backColumn, getString(R.string.airplay_runtime_return)) { finish() }
        ui.button(stopColumn, getString(R.string.airplay_runtime_stop_action)) { stopAndLeave() }
        controls = toolbar
        frame.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(ui.dp(48), 0, ui.dp(48), ui.dp(27))
        })
        controls.visibility = View.GONE
        setContentView(frame)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    override fun onStart() {
        super.onStart(); active = true
        startService(Intent(this, AirPlayService::class.java))
        bindReceiver()
    }
    private fun bindReceiver() {
        bound = bindService(Intent(this, AirPlayService::class.java), connection, BIND_AUTO_CREATE)
        if (!bound) unavailable()
    }
    private fun attach() {
        val target = display?.takeIf { it.isValid } ?: return
        val service = host ?: return
        service.attachDisplay(target) { state ->
            if (!active) return@attachDisplay
            render(state)
            if (waitingForStop && state.phase == AirPlayPhase.STOPPED) {
                waitingForStop = false
                main.post { if (active) host?.beginPlayback(getString(R.string.airplay_runtime_receiver_name)) }
            }
        }
        val result = service.beginPlayback(getString(R.string.airplay_runtime_receiver_name))
        waitingForStop = result.issue == AirPlayIssue.BUSY && snapshot.phase == AirPlayPhase.STOPPING
        if (!result.accepted && result.issue != AirPlayIssue.BUSY) unavailable()
    }
    private fun render(state: AirPlaySnapshot) {
        val hadVideo = snapshot.videoActive
        snapshot = state
        title.setText(when {
            state.issue == AirPlayIssue.PROTECTED_CONTENT -> R.string.airplay_runtime_protected_video
            state.issue == AirPlayIssue.MEDIA_UNSUPPORTED -> R.string.airplay_runtime_unsupported_video
            state.issue == AirPlayIssue.MEDIA_FAILED && state.phase == AirPlayPhase.READY -> R.string.airplay_runtime_app_video_failed
            state.phase == AirPlayPhase.FAILED -> R.string.airplay_runtime_failed
            state.phase == AirPlayPhase.STOPPED -> R.string.airplay_runtime_stopped
            state.phase == AirPlayPhase.STOPPING -> R.string.airplay_runtime_stopping
            state.phase == AirPlayPhase.STARTING -> R.string.airplay_runtime_waiting
            state.videoActive -> R.string.airplay_runtime_video_playing
            state.audioActive -> R.string.airplay_runtime_audio_playing
            state.connection != AirPlayConnection.NONE -> R.string.airplay_runtime_connecting
            else -> R.string.airplay_runtime_ready
        })
        detail.setText(when {
            state.issue == AirPlayIssue.PROTECTED_CONTENT -> R.string.airplay_runtime_protected_help
            state.issue == AirPlayIssue.MEDIA_UNSUPPORTED || (state.issue == AirPlayIssue.MEDIA_FAILED && state.phase == AirPlayPhase.READY) -> R.string.airplay_runtime_app_video_help
            state.phase == AirPlayPhase.FAILED -> R.string.airplay_runtime_recovery
            state.issue == AirPlayIssue.AUDIO_UNAVAILABLE -> R.string.airplay_runtime_audio_unavailable
            state.audioActive && !state.videoActive -> R.string.airplay_runtime_audio_only_help
            state.connection != AirPlayConnection.NONE && !state.videoActive -> R.string.airplay_runtime_video_wait_help
            else -> R.string.airplay_runtime_instructions
        })
        leaveHint.setText(when {
            !state.keepSession -> R.string.airplay_runtime_leave_stops
            state.backgroundAudio -> R.string.airplay_runtime_leave_audio
            else -> R.string.airplay_runtime_leave_connected
        })
        waitingHint.text = leaveHint.text
        if (state.issue == AirPlayIssue.AUDIO_UNAVAILABLE) leaveHint.setText(R.string.airplay_runtime_audio_unavailable)
        retry.visibility = if (state.phase in listOf(AirPlayPhase.FAILED, AirPlayPhase.STOPPED)) View.VISIBLE else View.GONE
        waiting.visibility = if (state.videoActive) View.GONE else View.VISIBLE
        if (state.videoActive && !hadVideo) {
            waiting.clearFocus()
            if ((getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager).isTouchExplorationEnabled) {
                controls.visibility = View.VISIBLE; returnButton.requestFocus()
            } else controls.visibility = View.GONE
        } else if (!state.videoActive && hadVideo) {
            main.removeCallbacks(hideControls); controls.visibility = View.GONE
        }
    }
    private fun unavailable() {
        main.removeCallbacks(hideControls)
        waiting.visibility = View.VISIBLE; controls.visibility = View.GONE
        title.setText(R.string.airplay_runtime_failed); detail.setText(R.string.airplay_runtime_recovery)
        retry.visibility = View.VISIBLE; retry.requestFocus()
    }
    private fun stopAndLeave() {
        val service = host
        if (service == null) { unavailable(); return }
        if (service.stopPlayback().accepted) finish() else unavailable()
    }
    private fun retryPlayback() {
        if (host == null) {
            if (bound) { unbindService(connection); bound = false }
            bindReceiver()
        } else {
            val result = host!!.beginPlayback(getString(R.string.airplay_runtime_receiver_name))
            if (!result.accepted && result.issue != AirPlayIssue.BUSY) unavailable()
        }
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (consumedKey == event.keyCode) {
            if (event.action == KeyEvent.ACTION_UP) consumedKey = null
            return true
        }
        val navigation = event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
        if (snapshot.videoActive && navigation && event.action == KeyEvent.ACTION_DOWN) {
            main.removeCallbacks(hideControls)
            if (!(getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager).isTouchExplorationEnabled) main.postDelayed(hideControls, 6000)
            if (controls.visibility != View.VISIBLE) {
                controls.visibility = View.VISIBLE; returnButton.requestFocus(); consumedKey = event.keyCode
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
    override fun surfaceCreated(holder: SurfaceHolder) { display = holder.surface; attach() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { }
    override fun surfaceDestroyed(holder: SurfaceHolder) { display = null; host?.detachDisplay() }
    override fun onStop() {
        active = false; main.removeCallbacksAndMessages(null)
        host?.detachDisplay(); host = null
        if (bound) { unbindService(connection); bound = false }
        super.onStop(); finish()
    }
}
