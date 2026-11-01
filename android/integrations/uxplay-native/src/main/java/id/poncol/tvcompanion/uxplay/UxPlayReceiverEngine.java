package id.poncol.tvcompanion.uxplay;
import id.poncol.tvcompanion.airplay.runtime.ReceiverEngine;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import java.io.File;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** UxPlay-specific adapter. Native loading and all engine work stay in the receiver process. */
public final class UxPlayReceiverEngine implements ReceiverEngine {
    public static id.poncol.tvcompanion.domain.airplay.AirPlayCapabilities capabilities() {
        return new id.poncol.tvcompanion.domain.airplay.AirPlayCapabilities(true, true, 1280, 720, 30, true);
    }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean closed;
    private Observer observer;
    private long handle;
    private boolean released;
    private final java.util.List<Runnable> releaseObservers = new java.util.ArrayList<>();
    private WifiManager.MulticastLock multicast;
    private final android.media.AudioManager audioManager;
    private android.media.AudioFocusRequest audioFocus;
    private boolean ownsFocus;
    private boolean audioEnabled;
    private boolean audioRequested;
    private Boolean advertised;
    private final HlsPlayback hls;
    private boolean hlsActive;
    private final android.media.AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (closed || !audioEnabled) return;
        if (change == android.media.AudioManager.AUDIOFOCUS_GAIN) updateFocus(true, 1);
        else if (change == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) updateFocus(true, .2f);
        else {
            updateFocus(false, 1); deliver(State.AUDIO_STOPPED);
            if (change == android.media.AudioManager.AUDIOFOCUS_LOSS) abandonFocus();
        }
    };
    public UxPlayReceiverEngine(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (android.media.AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        this.hls = new HlsPlayback(this.context, state -> {
            if (closed) return;
            // Queue the terminal reason before native EOS can report disconnection.
            deliver(state);
            if (state == State.APP_VIDEO_ENDED || state == State.APP_VIDEO_FAILED || state == State.MEDIA_UNSUPPORTED || state == State.PROTECTED_CONTENT) {
                hlsActive = false; audioRequested = false; abandonFocus();
                worker.execute(() -> { if (!closed && handle != 0) nativeHlsEnded(handle); });
            }
        });
    }
    @Override public void start(String receiverName, Observer observer) {
        this.observer = observer;
        worker.execute(() -> {
            try {
                if (closed) return;
                System.loadLibrary("tvcompanion_uxplay");
                WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                if (wifi != null) {
                    multicast = wifi.createMulticastLock("tvcompanion-airplay-lab");
                    multicast.setReferenceCounted(false); multicast.acquire();
                }
                String identity = context.getSharedPreferences("receiver", 0).getString("identity", null);
                if (identity == null) {
                    byte[] bytes = new byte[6]; new SecureRandom().nextBytes(bytes);
                    bytes[0] = (byte) ((bytes[0] | 2) & 0xfe);
                    StringBuilder text = new StringBuilder();
                    for (byte value : bytes) { if (text.length() > 0) text.append(':'); text.append(String.format(java.util.Locale.ROOT, "%02X", value & 255)); }
                    identity = text.toString();
                    context.getSharedPreferences("receiver", 0).edit().putString("identity", identity).apply();
                }
                handle = nativeStart( new File(context.getFilesDir(), "receiver.key").getPath(), identity, receiverName);
                if (handle == 0) throw new IllegalStateException("Native initialization failed");
                deliver(State.DISCOVERABLE);
            } catch (RuntimeException | LinkageError failure) {
                deliver(State.FAILED);
                release();
            }
        });
    }
    @Override public void attachSurface(Surface surface) {
        if (closed) return;
        hls.setSurface(surface);
        if (surface != null && surface.isValid() && audioEnabled && audioRequested && !ownsFocus) requestFocus();
        worker.execute(() -> {
            if (!closed && handle != 0 && (surface == null || surface.isValid())) nativeSetSurface(handle, surface);
        });
    }
    @Override public void setDiscoverable(boolean enabled) {
        if (closed || (advertised != null && advertised == enabled)) return;
        advertised = enabled;
        worker.execute(() -> { if (!closed && handle != 0 && !nativeSetDiscoverable(handle, enabled)) deliver(State.FAILED); });
    }
    @Override public void setAudioEnabled(boolean enabled) {
        if (closed || audioEnabled == enabled) return;
        audioEnabled = enabled;
        hls.setAudioEnabled(enabled);
        android.util.Log.i("TVCAirPlay", enabled ? "stage=AUDIO_POLICY_ENABLED" : "stage=AUDIO_POLICY_DISABLED");
        if (enabled) { if (audioRequested) requestFocus(); }
        else { updateFocus(false, 1); abandonFocus(); deliver(State.AUDIO_STOPPED); }
    }
    private void updateFocus(boolean allowed, float duck) {
        if (closed) return;
        hls.setFocus(allowed, duck);
        worker.execute(() -> { if (!closed && handle != 0) nativeSetFocus(handle, allowed, duck); });
    }
    private void requestFocus() {
        if (closed || !audioEnabled) return;
        if (ownsFocus) { updateFocus(true, 1); return; }
        android.media.AudioAttributes attributes = new android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build();
        audioFocus = new android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener(focusListener, main)
            .setAcceptsDelayedFocusGain(false).build();
        ownsFocus = audioManager != null && audioManager.requestAudioFocus(audioFocus) == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        android.util.Log.i("TVCAirPlay", ownsFocus ? "stage=AUDIO_FOCUS_GRANTED" : "stage=AUDIO_FOCUS_DENIED");
        updateFocus(ownsFocus, 1);
        if (!ownsFocus) deliver(State.AUDIO_UNAVAILABLE);
    }
    private void abandonFocus() {
        if (ownsFocus && audioManager != null && audioFocus != null) audioManager.abandonAudioFocusRequest(audioFocus);
        ownsFocus = false; audioFocus = null;
    }
    private void onNativeHlsCommand(int operation, String location, float value, int port) {
        main.post(() -> {
            if (closed) return;
            switch (operation) {
                case 0:
                    hlsActive = true; audioRequested = true;
                    deliver(State.APP_VIDEO_REQUESTED); requestFocus();
                    hls.play(location, value, port); break;
                case 1: hls.seek(value); break;
                case 2: hls.rate(value); break;
                case 3:
                    hls.stop(); hlsActive = false;
                    if (value == 0) { audioRequested = false; updateFocus(false, 1); abandonFocus(); }
                    // A mirror transition retains focus until its own format/stop callback.
                    worker.execute(() -> { if (!closed && handle != 0) nativeHlsReleased(handle); });
                    break;
                default: deliver(State.MEDIA_UNSUPPORTED);
            }
        });
    }
    private double[] onNativeHlsInfo() { return hls.info(); }
    private void onNativeHlsVolume(float value) { main.post(() -> { if (!closed) hls.volume(value); }); }
    private void onNativeState(int state) {
        if (state == 11) {
            main.post(() -> {
                if (closed) return;
                hls.stop(); hlsActive = false; audioRequested = false;
                updateFocus(false, 1); abandonFocus(); deliver(State.SESSION_REPLACED);
            }); return;
        }
        if (state == 15) { deliver(State.MEDIA_UNSUPPORTED); return; }
        if (state == 9) { main.post(() -> { if (!closed) { audioRequested = true; deliver(State.SESSION_CONNECTED); requestFocus(); } }); return; }
        if (state == 7) { main.post(() -> { if (closed || hlsActive) return; audioRequested = false; abandonFocus(); if (!closed && observer != null) observer.onState(State.AUDIO_STOPPED); }); return; }
        State text;
        switch (state) {
            case 1: text = State.CONNECTED; break;
            case 2: text = State.VIDEO_OUTPUT; break;
            case 3: text = State.DISCONNECTED; break;
            case 5: text = State.AUDIO_PLAYING; break;
            case 6: text = State.AUDIO_UNAVAILABLE; break;
            case 8: text = State.DISPLAY_REQUIRED; break;
            default: text = State.FAILED;
        }
        deliver(text);
    }
    private void deliver(State state) {
        main.post(() -> { if (!closed && observer != null) observer.onState(state); });
    }
    private native long nativeStart(String keyPath, String identity, String receiverName);
    private native void nativeStop(long handle);
    private native void nativeSetSurface(long handle, Surface surface);
    private native boolean nativeSetDiscoverable(long handle, boolean enabled);
    private native void nativeHlsReleased(long handle);
    private native void nativeHlsEnded(long handle);
    private native void nativeSetFocus(long handle, boolean allowed, float duck);
    private void release() {
        if (handle != 0) { nativeStop(handle); handle = 0; }
        if (multicast != null && multicast.isHeld()) multicast.release();
        multicast = null;
        main.post(this::abandonFocus);
    }
    @Override public synchronized void stop(Runnable completion) {
        if (released) { main.post(completion); return; }
        releaseObservers.add(completion);
        if (closed) return;
        closed = true;
        hls.close();
        observer = null;
        main.removeCallbacksAndMessages(null);
        worker.execute(() -> {
            try { release(); }
            finally {
                main.post(() -> {
                    java.util.List<Runnable> callbacks;
                    synchronized (this) {
                        released = true;
                        callbacks = new java.util.ArrayList<>(releaseObservers);
                        releaseObservers.clear();
                    }
                    for (Runnable callback : callbacks) callback.run();
                });
            }
        });
        worker.shutdown();
    }
}
