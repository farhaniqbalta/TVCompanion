package id.poncol.tvcompanion.airplay.runtime

import android.app.Activity
import android.content.*
import android.os.*
import android.view.*
import android.view.accessibility.AccessibilityManager
import android.util.Log
import android.widget.*
import id.poncol.tvcompanion.airplay.runtime.ambient.*
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
    private lateinit var frame: FrameLayout
    private lateinit var ambientButton: Button
    private var ambient: AmbientView? = null
    @Volatile private var ambientSub: AmbientSubscription? = null   // read on the GL thread
    private var ambientHint: TextView? = null
    private var lookLabel: TextView? = null
    private var look = AmbientShaders.DEFAULT_LOOK
    // A remote shortcut request, applied once on the first state after launch or a new intent.
    private var pendingAmbient: AmbientRequest? = null
    private val epoch = AmbientEpoch()
    private val reopen = AmbientReopen()
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
        if (saved == null) pendingAmbient = shortcut(intent)
        val ui = TvUi(this)
        frame = FrameLayout(this)
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
        ambientButton = ui.button(page, getString(R.string.airplay_runtime_ambient)) { openAmbient() }.apply { visibility = View.GONE }
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
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingAmbient = shortcut(intent)
        if (host != null) applyShortcut(snapshot)
    }
    private fun shortcut(intent: Intent?) =
        AmbientShortcut.parse(intent?.getStringExtra(AmbientShortcut.EXTRA_AMBIENT), intent?.getStringExtra(AmbientShortcut.EXTRA_LOOK))
    /** Never queues: a request the current state can't serve is dropped, so music starting later doesn't open Ambient. */
    private fun applyShortcut(state: AirPlaySnapshot) {
        val request = pendingAmbient ?: return
        pendingAmbient = null
        when (request) {
            is AmbientRequest.Open -> if (AmbientShortcut.canOpen(state, request.look)) {
                getSharedPreferences("ambient", MODE_PRIVATE).edit().putString("look", request.look).apply()
                val view = ambient
                if (view == null) openAmbient() else if (look != request.look) { look = request.look; view.setLook(look); applySize(view) }
            }
            AmbientRequest.Close -> closeAmbient()
        }
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
        if (AmbientPolicy.mustClose(state)) closeAmbient()
        ambientButton.visibility = if (AmbientPolicy.canOpen(state)) View.VISIBLE else View.GONE
        applyShortcut(state)
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
        if (ambient != null) { waiting.visibility = View.GONE; controls.visibility = View.GONE }
    }
    private fun openAmbient() {
        if (ambient != null) return
        look = AmbientShaders.resolve(getSharedPreferences("ambient", MODE_PRIVATE).getString("look", null))
        lateinit var view: AmbientView
        view = AmbientView(this, look, { ambientSub }, { status -> if (ambient === view) onAmbientStatus(status) },
            { name, meanNs -> if (ambient === view && name == look && slowLooks.add(name)) {
                if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                    Log.d("TVCAmbient", "look=$name fallback=960x540 interval=${meanNs / 1000}us")
                applySize(view)
            } }) { e ->
            if (ambient === view) { Log.w("TVCAmbient", "closing ambient", e); closeAmbient() }
        }
        ambient = view
        host?.setAmbientShowing(true)
        applySize(view)
        requestAmbient()
        frame.addView(view, FrameLayout.LayoutParams(-1, -1))
        ambientHint = ambientText(Gravity.END).apply {
            setText(R.string.airplay_runtime_ambient_hint)
            animate().alpha(0f).setStartDelay(4000).setDuration(600)
        }
        lookLabel = ambientText(Gravity.START).apply { alpha = 0f; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        main.removeCallbacks(hideControls)
        waiting.visibility = View.GONE; controls.visibility = View.GONE
        view.requestFocus()
        view.begin()
    }
    /** Overlay text inside the 5% safe margin, bottom corner. */
    private fun ambientText(side: Int): TextView {
        val dm = resources.displayMetrics
        return TextView(this).apply {
            textSize = 16f; setTextColor(0xccf2f5f4.toInt())
            frame.addView(this, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or side).apply {
                setMargins(dm.widthPixels / 20, dm.heightPixels / 20, dm.widthPixels / 20, dm.heightPixels / 20)
            })
        }
    }
    private fun switchLook(step: Int) {
        val view = ambient ?: return
        look = AmbientShaders.cycle(look, step)
        getSharedPreferences("ambient", MODE_PRIVATE).edit().putString("look", look).apply()
        view.setLook(look)
        applySize(view)
        lookLabel?.run {
            LOOK_NAMES.getOrNull(AmbientShaders.LOOKS.indexOf(look))?.let { setText(it) } ?: setText(look)
            animate().cancel(); alpha = 1f
            animate().alpha(0f).setStartDelay(2000).setDuration(600)
        }
    }
    /** Looks that missed frames at full size render at 960x540; SurfaceFlinger scales them up. */
    private fun applySize(view: AmbientView) =
        if (look in slowLooks) view.holder.setFixedSize(960, 540) else view.holder.setSizeFromLayout()
    /** Until the result arrives the renderer sees no subscription and shows idle. */
    private fun requestAmbient() {
        val e = epoch.next()
        reopen.opened()
        host?.openAmbient { sub -> if (!epoch.isCurrent(e) || ambient == null) sub?.close() else ambientSub = sub }
    }
    private fun onAmbientStatus(status: AmbientStatus) {
        if (!reopen.shouldReopen(status, SystemClock.uptimeMillis())) return
        val old = ambientSub; ambientSub = null; old?.close()   // close is safe while the GL thread reads
        requestAmbient()
    }
    private fun closeAmbient() {
        val view = ambient ?: return
        epoch.invalidate()
        ambient = null
        view.end()   // blocks until the GL thread pauses, so the subscription is no longer read
        frame.removeView(view)
        listOfNotNull(ambientHint, lookLabel).forEach { it.animate().cancel(); frame.removeView(it) }
        ambientHint = null; lookLabel = null
        ambientSub?.close(); ambientSub = null
        host?.setAmbientShowing(false)
        render(snapshot)
        ambientButton.requestFocus()
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
        if (ambient != null) when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> { if (event.action == KeyEvent.ACTION_UP) closeAmbient(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // repeatCount 0 only: holding the key must not spin through looks
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)
                    switchLook(if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
        }
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
        closeAmbient()
        active = false; main.removeCallbacksAndMessages(null)
        host?.detachDisplay(); host = null
        if (bound) { unbindService(connection); bound = false }
        super.onStop(); finish()
    }
    private companion object {
        /** Process lifetime, main thread only: looks that fell back to 960x540. */
        val slowLooks = mutableSetOf<String>()
        /** Display names, in AmbientShaders.LOOKS order. */
        val LOOK_NAMES = intArrayOf(R.string.airplay_runtime_ambient_look_aurora, R.string.airplay_runtime_ambient_look_strings,
            R.string.airplay_runtime_ambient_look_dusk,
            R.string.airplay_runtime_ambient_look_chromatic_flow, R.string.airplay_runtime_ambient_look_edge_orbit,
            R.string.airplay_runtime_ambient_look_bounce)
    }
}
