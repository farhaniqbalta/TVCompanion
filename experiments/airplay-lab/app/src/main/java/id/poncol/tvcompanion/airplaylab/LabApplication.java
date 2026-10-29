package id.poncol.tvcompanion.airplaylab;
import android.app.Application;
import id.poncol.tvcompanion.airplay.runtime.ReceiverEngine;
import id.poncol.tvcompanion.airplay.runtime.ReceiverEngineProvider;
import id.poncol.tvcompanion.uxplay.UxPlayReceiverEngine;
public final class LabApplication extends Application implements ReceiverEngineProvider {
    @Override public id.poncol.tvcompanion.domain.airplay.AirPlayCapabilities receiverCapabilities() { return UxPlayReceiverEngine.capabilities(); }
    @Override public ReceiverEngine createReceiverEngine() { return new UxPlayReceiverEngine(this); }
}
