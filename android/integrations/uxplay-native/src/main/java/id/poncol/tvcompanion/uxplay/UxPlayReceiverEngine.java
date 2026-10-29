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
        return new id.poncol.tvcompanion.domain.airplay.AirPlayCapabilities(true, false, 1280, 720, 30);
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
    public UxPlayReceiverEngine(Context context) { this.context = context.getApplicationContext(); }
    @Override public void start(Surface surface, String receiverName, Observer observer) {
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
                handle = nativeStart(surface, new File(context.getFilesDir(), "receiver.key").getPath(), identity, receiverName);
                if (handle == 0) throw new IllegalStateException("Native initialization failed");
                deliver(State.DISCOVERABLE);
            } catch (RuntimeException | LinkageError failure) {
                deliver(State.FAILED);
                release();
            }
        });
    }
    private void onNativeState(int state) {
        State text;
        switch (state) {
            case 1: text = State.CONNECTED; break;
            case 2: text = State.VIDEO_OUTPUT; break;
            case 3: text = State.DISCONNECTED; break;
            default: text = State.FAILED;
        }
        deliver(text);
    }
    private void deliver(State state) {
        main.post(() -> { if (!closed && observer != null) observer.onState(state); });
    }
    private native long nativeStart(Surface surface, String keyPath, String identity, String receiverName);
    private native void nativeStop(long handle);
    private void release() {
        if (handle != 0) { nativeStop(handle); handle = 0; }
        if (multicast != null && multicast.isHeld()) multicast.release();
        multicast = null;
    }
    @Override public synchronized void stop(Runnable completion) {
        if (released) { main.post(completion); return; }
        releaseObservers.add(completion);
        if (closed) return;
        closed = true;
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
