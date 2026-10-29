package id.poncol.tvcompanion.airplay.runtime;
import android.view.Surface;
/** Receiver-process local API. Surfaces and audio never cross the control IPC. */
public interface ReceiverEngine extends AutoCloseable {
    enum State { DISCOVERABLE, CONNECTED, SESSION_CONNECTED, VIDEO_OUTPUT, AUDIO_PLAYING, AUDIO_STOPPED, AUDIO_UNAVAILABLE, DISPLAY_REQUIRED, DISCONNECTED, FAILED }
    interface Observer { void onState(State state); }
    void start(String receiverName, Observer observer);
    void attachSurface(Surface surface);
    void setDiscoverable(boolean enabled);
    void setAudioEnabled(boolean enabled);
    void stop(Runnable released);
    @Override default void close() { stop(() -> {}); }
}
