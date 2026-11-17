#pragma once
#include <cstdint>

namespace tvc {
struct AmbientFeatures {
    float rms = 0, peak = 0, bands[16] = {};
    uint32_t beatCount = 0;
    int64_t beatPlayoutNs[4] = {0, 0, 0, 0};  // newest first, 0 = empty
};

// Pure 44.1 kHz analysis of one 1024-sample window (hop 512): Hann FFT, 16 log bands, low-band flux onsets.
// Allocation-free after construction except inside realFftMagnitudes; use from one thread.
class FeatureExtractor {
public:
    FeatureExtractor();
    void reset();
    void process(const float *window1024, int64_t newestPlayoutNs, AmbientFeatures &features);  // updates in place

private:
    static constexpr int N = 1024, HISTORY = 86;
    float hann_[N], buf_[N], mags_[N / 2 + 1];
    int bandLo_[16], bandHi_[16];
    float hannSum_ = 0;
    float flux_[HISTORY] = {};
    int fluxCount_ = 0, fluxNext_ = 0;
    float prevLow_ = 0;
    bool hasBeat_ = false;
    int64_t lastBeatNs_ = 0;
};
}
