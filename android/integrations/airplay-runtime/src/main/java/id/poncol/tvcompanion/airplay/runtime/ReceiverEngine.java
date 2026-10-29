package id.poncol.tvcompanion.airplay.runtime;
import android.view.Surface;
/** Receiver-process local API. Surface never crosses the control IPC. */
public interface ReceiverEngine extends AutoCloseable {
    enum State { DISCOVERABLE, CONNECTED, VIDEO_OUTPUT, DISCONNECTED, FAILED }
    interface Observer { void onState(State state); }
    void start(Surface surface, String receiverName, Observer observer);
    void stop(Runnable released);
    @Override default void close() { stop(() -> {}); }
}
