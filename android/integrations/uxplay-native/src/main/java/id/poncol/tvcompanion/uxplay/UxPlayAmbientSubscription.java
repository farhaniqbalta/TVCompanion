package id.poncol.tvcompanion.uxplay;

import id.poncol.tvcompanion.airplay.runtime.ambient.AmbientStatus;
import id.poncol.tvcompanion.airplay.runtime.ambient.AmbientSubscription;

/** Owns one native subscription. read (GL thread) and close (main) are serialized; close is idempotent. */
final class UxPlayAmbientSubscription implements AmbientSubscription {
    private long sub;

    UxPlayAmbientSubscription(long sub) { this.sub = sub; }

    @Override public synchronized AmbientStatus read(float[] out) {
        if (sub == 0) return AmbientStatus.CLOSED;
        switch (nativeRead(sub, out)) {
            case 0: return AmbientStatus.OK;
            case 1: return AmbientStatus.STALE;
            default: return AmbientStatus.CLOSED;
        }
    }

    @Override public synchronized void close() {
        if (sub == 0) return;
        nativeClose(sub);
        sub = 0;
    }

    /** Engine worker only: the receiver handle is valid only there. Returns 0 when unavailable. */
    static native long nativeOpen(long receiverHandle);
    private static native int nativeRead(long sub, float[] out);
    private static native void nativeClose(long sub);
}
