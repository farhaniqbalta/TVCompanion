package id.poncol.tvcompanion.airplay.runtime;
/** App composition selects one implementation at build time, instantiated only in :airplay. */
public interface ReceiverEngineProvider {
    ReceiverEngine createReceiverEngine();
    id.poncol.tvcompanion.domain.airplay.AirPlayCapabilities receiverCapabilities();
}
