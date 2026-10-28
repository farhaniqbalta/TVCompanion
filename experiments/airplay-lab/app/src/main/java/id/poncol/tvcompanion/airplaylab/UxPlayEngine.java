package id.poncol.tvcompanion.airplaylab;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import java.io.File;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** UxPlay-specific adapter confined to the development harness and receiver process. */
final class UxPlayEngine implements ReceiverEngine {
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean closed;
    private Observer observer;
    private long handle;
    private WifiManager.MulticastLock multicast;
    UxPlayEngine(Context context) { this.context = context.getApplicationContext(); }
    @Override public void start(Surface surface, Observer observer) {
        this.observer = observer;
        worker.execute(() -> {
            try {
                if (closed) return;
                System.loadLibrary("airplay_lab");
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
                handle = nativeStart(surface, new File(context.getFilesDir(), "receiver.key").getPath(), identity);
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
    private native long nativeStart(Surface surface, String keyPath, String identity);
    private native void nativeStop(long handle);
    private void release() {
        if (handle != 0) { nativeStop(handle); handle = 0; }
        if (multicast != null && multicast.isHeld()) multicast.release();
        multicast = null;
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        worker.execute(this::release);
        worker.shutdown();
        observer = null;
        main.removeCallbacksAndMessages(null);
    }
}
