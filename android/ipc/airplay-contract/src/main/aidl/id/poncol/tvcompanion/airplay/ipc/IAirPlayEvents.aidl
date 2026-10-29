package id.poncol.tvcompanion.airplay.ipc;
oneway interface IAirPlayEvents {
    void onState(int version, long revision, int phase, int connection, int issue, int capabilities, int width, int height, int maxFps);
    void onResult(long requestId, int issue);
}
