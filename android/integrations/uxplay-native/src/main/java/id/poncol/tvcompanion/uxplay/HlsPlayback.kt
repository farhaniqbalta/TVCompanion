package id.poncol.tvcompanion.uxplay

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import id.poncol.tvcompanion.airplay.runtime.ReceiverEngine.State

/** Implementation-local HLS player. All player operations stay on :airplay's main thread. */
class HlsPlayback(context: Context, private val listener: Listener) : AutoCloseable {
    fun interface Listener { fun onState(state: State) }
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var prepared = false
    private var wantsPlay = true
    private var audioEnabled = false
    private var focus = false
    private var gain = 1f
    private var duck = 1f
    private var audioReported = false
    private var startAt = 0.0
    private var pendingSeek = false
    private var closed = false
    private var renderStarted = false
    private var hasAudio = false
    private var source: String? = null
    private var proxyPort = 0
    @Volatile private var progress = HlsProgress()
    private val seekDeadline = Runnable { if (pendingSeek) fail(State.APP_VIDEO_FAILED) }
    private val deadline = Runnable { if (player != null && !prepared) fail(State.APP_VIDEO_FAILED) }
    private fun checkThread() = check(Looper.myLooper() == Looper.getMainLooper())
    private fun sample(buffering: Boolean = progress.buffering) {
        val current = player ?: return
        if (!prepared) return
        try {
            val playing = current.isPlaying
            progress = HlsProgress(current.duration.coerceAtLeast(0) / 1000.0, current.currentPosition.coerceAtLeast(0) / 1000.0,
                if (playing && wantsPlay) 1.0 else 0.0, true, buffering, false, System.nanoTime())
        } catch (_: IllegalStateException) { }
    }
    fun play(location: String, start: Double, proxyPort: Int) {
        checkThread()
        if (closed) return
        release()
        if (!HlsRequestPolicy.accepts(location, proxyPort, start)) { fail(State.MEDIA_UNSUPPORTED); return }
        source = location; this.proxyPort = proxyPort
        startAt = start; wantsPlay = true; progress = HlsProgress(sampledNs = System.nanoTime())
        val created = MediaPlayer(); player = created
        created.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
        created.setOnPreparedListener { current ->
            if (player !== current || closed) return@setOnPreparedListener
            if (current.videoWidth.toLong() * current.videoHeight > 1280L * 720) { fail(State.MEDIA_UNSUPPORTED); return@setOnPreparedListener }
            prepared = true; hasAudio = current.trackInfo.any { it.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO }
            main.removeCallbacks(deadline)
            if (startAt > 0) seek(startAt) else applyPlayback()
            sample(false)
            Log.i("TVCAirPlay", "stage=HLS_PREPARED")
        }
        created.setOnVideoSizeChangedListener { current, width, height ->
            if (player === current && !closed && width.toLong() * height > 1280L * 720) fail(State.MEDIA_UNSUPPORTED)
        }
        created.setOnInfoListener { current, what, _ ->
            if (player !== current || closed) return@setOnInfoListener true
            when (what) {
                MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> {
                    renderStarted = true
                    if (surface?.isValid == true) listener.onState(State.VIDEO_OUTPUT)
                    Log.i("TVCAirPlay", "stage=HLS_VIDEO_OUTPUT")
                }
                MediaPlayer.MEDIA_INFO_BUFFERING_START -> sample(true)
                MediaPlayer.MEDIA_INFO_BUFFERING_END -> { sample(false); applyPlayback() }
            }
            true
        }
        created.setOnSeekCompleteListener { current ->
            if (player !== current || closed) return@setOnSeekCompleteListener
            pendingSeek = false; main.removeCallbacks(seekDeadline); sample(false); applyPlayback()
        }
        created.setOnCompletionListener { current ->
            if (player !== current || closed) return@setOnCompletionListener
            sample(false); progress = progress.copy(rate = 0.0, ended = true)
            listener.onState(State.APP_VIDEO_ENDED)
            release(keepProgress = true)
        }
        created.setOnErrorListener { current, what, extra ->
            if (player === current && !closed) fail(if (what == MediaPlayer.MEDIA_ERROR_UNSUPPORTED || extra == MediaPlayer.MEDIA_ERROR_UNSUPPORTED) State.MEDIA_UNSUPPORTED else State.APP_VIDEO_FAILED)
            true
        }
        created.setOnDrmInfoListener { current, info ->
            if (player === current && !closed && info.supportedSchemes.isNotEmpty()) fail(State.PROTECTED_CONTENT)
        }
        try {
            created.setSurface(surface?.takeIf { it.isValid })
            created.setVolume(0f, 0f)
            // The Android network policy allows HTTP only for the receiver's loopback proxy.
            created.setDataSource(context, android.net.Uri.parse(location))
            created.prepareAsync(); main.postDelayed(deadline, 20_000)
            Log.i("TVCAirPlay", "stage=HLS_PREPARING")
        } catch (_: Exception) { fail(State.APP_VIDEO_FAILED) }
    }
    private fun applyPlayback() {
        val current = player ?: return
        if (!prepared || pendingSeek) return
        try {
            val volume = if (audioEnabled && focus) (gain * duck).coerceIn(0f, 1f) else 0f
            current.setVolume(volume, volume)
            val run = wantsPlay && (surface?.isValid == true || (audioEnabled && focus))
            if (run && !current.isPlaying) current.start()
            else if (!run && current.isPlaying) current.pause()
            sample(progress.buffering)
            val audible = run && audioEnabled && focus && hasAudio
            if (audible != audioReported) {
                audioReported = audible; listener.onState(if (audible) State.AUDIO_PLAYING else State.AUDIO_STOPPED)
            }
        } catch (_: IllegalStateException) { fail(State.APP_VIDEO_FAILED) }
    }
    fun setSurface(value: Surface?) {
        checkThread()
        val previous = surface; surface = value
        val location = source
        if (value?.isValid == true && previous !== value && renderStarted && location != null) {
            // Reprepare on a new Surface so Android emits fresh first-frame evidence.
            // Do not infer displayed video from the previous Surface's render event.
            val position = info()[1]; val resume = wantsPlay; val port = proxyPort
            play(location, position, port); wantsPlay = resume; return
        }
        try { player?.setSurface(value?.takeIf { it.isValid }) } catch (_: IllegalStateException) { fail(State.APP_VIDEO_FAILED) }
        applyPlayback()
    }
    fun setAudioEnabled(value: Boolean) { checkThread(); audioEnabled = value; applyPlayback() }
    fun setFocus(value: Boolean, attenuation: Float) { checkThread(); focus = value; duck = attenuation; applyPlayback() }
    fun volume(value: Float) { checkThread(); gain = value.coerceIn(0f, 1f); applyPlayback() }
    fun rate(value: Float) {
        checkThread(); if (value != 0f && value != 1f) return
        wantsPlay = value == 1f; applyPlayback()
    }
    fun seek(seconds: Double) {
        checkThread(); if (!seconds.isFinite() || seconds < 0 || seconds > 604800) return
        val current = player ?: return
        if (!prepared) { startAt = seconds; return }
        try {
            val position = if (current.duration > 0) (seconds * 1000).toLong().coerceAtMost(current.duration.toLong()) else (seconds * 1000).toLong()
            pendingSeek = true; main.removeCallbacks(seekDeadline); main.postDelayed(seekDeadline, 5000)
            current.seekTo(position, MediaPlayer.SEEK_CLOSEST)
        } catch (_: IllegalStateException) { fail(State.APP_VIDEO_FAILED) }
    }
    fun info(): DoubleArray = progress.values(System.nanoTime())
    fun stop() { checkThread(); release(); progress = HlsProgress(ended = true) }
    private fun fail(reason: State) {
        Log.w("TVCAirPlay", "stage=" + if (reason == State.PROTECTED_CONTENT) "HLS_PROTECTED_CONTENT" else "HLS_PLAYBACK_FAILED")
        release(); progress = HlsProgress(ended = true); listener.onState(reason)
    }
    private fun release(keepProgress: Boolean = false) {
        main.removeCallbacks(deadline); main.removeCallbacks(seekDeadline)
        source = null; renderStarted = false; hasAudio = false
        val old = player; player = null; prepared = false; pendingSeek = false
        old?.release()
        if (audioReported) { audioReported = false; listener.onState(State.AUDIO_STOPPED) }
        if (!keepProgress) progress = HlsProgress()
    }
    override fun close() { checkThread(); if (!closed) { closed = true; release(); main.removeCallbacksAndMessages(null) } }
}
