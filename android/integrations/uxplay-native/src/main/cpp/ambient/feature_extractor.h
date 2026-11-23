#pragma once
#include "ambient/beat_tracker.h"
#include <cstdint>

namespace tvc {
struct AmbientFeatures {
    float rms = 0, peak = 0, bands[16] = {};
    uint32_t beatCount = 0;
    int64_t beatPlayoutNs[4] = {0, 0, 0, 0};  // newest first, 0 = empty
};

// Pure 44.1 kHz analysis of one 1024-sample window (hop 512): Hann FFT, 16 log bands, log spectral flux into a
// tempo/phase beat tracker. Call once per 512-sample hop, in order.
// Allocation-free after construction except inside realFftMagnitudes; use from one thread.
class FeatureExtractor {
public:
    FeatureExtractor();
    void reset();
    void process(const float *window1024, int64_t newestPlayoutNs, AmbientFeatures &features);  // updates in place
    const BeatTracker &tracker() const { return tracker_; }  // diagnostics and tests
    float onset() const { return onset_; }                    // last onset strength fed to the tracker
    void skip(int hops);  // hops that were never analysed: advance the beat clock, forget the previous spectrum

private:
    static constexpr int N = 1024, FLUX_BINS = 256;  // flux over bins 1..255 (~11 kHz)
    float hann_[N], buf_[N], mags_[N / 2 + 1];
    int bandLo_[16], bandHi_[16];
    float hannSum_ = 0;
    float prevLog_[FLUX_BINS] = {};
    bool hasPrev_ = false;
    float onset_ = 0;
    BeatTracker tracker_;
};
}
