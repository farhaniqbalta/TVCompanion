package id.poncol.tvcompanion.airplaylab;

import android.view.Surface;

/** Local receiver-process test seam, not the pure application or Binder contract. */
interface ReceiverEngine extends AutoCloseable {
    enum State { STARTING, DISCOVERABLE, CONNECTED, VIDEO_OUTPUT, DISCONNECTED, FAILED }
    interface Observer { void onState(State state); }
    void start(Surface surface, Observer observer);
    @Override void close();
}
