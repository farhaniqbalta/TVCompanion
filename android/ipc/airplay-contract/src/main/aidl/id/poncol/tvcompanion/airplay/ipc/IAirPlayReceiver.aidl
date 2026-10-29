package id.poncol.tvcompanion.airplay.ipc;
import id.poncol.tvcompanion.airplay.ipc.IAirPlayEvents;
oneway interface IAirPlayReceiver {
    void observe(IAirPlayEvents events);
    void removeObserver(IAirPlayEvents events);
    void start(long requestId, String receiverName, IAirPlayEvents events);
    void stop(long requestId, IAirPlayEvents events);
}
