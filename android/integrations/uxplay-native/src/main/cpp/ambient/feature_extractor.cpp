#include "ambient/feature_extractor.h"
#include "ambient/fft.h"
#include <algorithm>
#include <cmath>

namespace tvc {
FeatureExtractor::FeatureExtractor() {
    for (int i = 0; i < N; ++i) hannSum_ += hann_[i] = (float)(0.5 - 0.5 * std::cos(2 * M_PI * i / N));
    const double df = 44100.0 / N;
    for (int b = 0; b < 16; ++b) {
        const double f0 = 40 * std::pow(400.0, b / 16.0), f1 = 40 * std::pow(400.0, (b + 1) / 16.0);
        bandLo_[b] = std::max(1, (int)std::floor(f0 / df));
        bandHi_[b] = std::min(N / 2 + 1, std::max(bandLo_[b] + 1, (int)std::ceil(f1 / df)));
    }
}

void FeatureExtractor::reset() {
    std::fill(flux_, flux_ + HISTORY, 0.0f);
    fluxCount_ = fluxNext_ = 0;
    prevLow_ = 0;
    hasBeat_ = false;
    lastBeatNs_ = 0;
}

void FeatureExtractor::process(const float *w, int64_t newestPlayoutNs, AmbientFeatures &f) {
    double sq = 0; float peak = 0;
    for (int i = 0; i < N; ++i) {
        const float v = std::isfinite(w[i]) ? w[i] : 0.0f;
        sq += (double)v * v;
        peak = std::max(peak, std::fabs(v));
        buf_[i] = v * hann_[i];
    }
    f.rms = (float)std::sqrt(sq / N);
    f.peak = peak;
    realFftMagnitudes(buf_, N, mags_);
    const float norm = 2.0f / hannSum_;
    for (int b = 0; b < 16; ++b) {
        double sum = 0;
        for (int k = bandLo_[b]; k < bandHi_[b]; ++k) sum += mags_[k] * norm;
        const double mag = std::max(sum / (bandHi_[b] - bandLo_[b]), 1e-12);
        f.bands[b] = (float)std::min(1.0, std::max(0.0, (20 * std::log10(mag) + 80) / 55));
    }

    const float low = (f.bands[0] + f.bands[1] + f.bands[2] + f.bands[3]) / 4;
    const float flux = std::max(0.0f, low - prevLow_);
    prevLow_ = low;
    float meanFlux = 0;
    for (int i = 0; i < fluxCount_; ++i) meanFlux += flux_[i];
    if (fluxCount_) meanFlux /= fluxCount_;
    flux_[fluxNext_] = flux;
    fluxNext_ = (fluxNext_ + 1) % HISTORY;
    fluxCount_ = std::min(fluxCount_ + 1, HISTORY);

    const int64_t sinceBeat = newestPlayoutNs - lastBeatNs_;
    // Playout going backwards (discontinuity without a reset) must not block beats until it catches up.
    const bool refractoryOk = !hasBeat_ || sinceBeat < 0 || sinceBeat >= 280000000LL;
    if (flux > 1.6f * meanFlux + 0.02f && low > 0.25f && refractoryOk) {
        for (int i = 3; i > 0; --i) f.beatPlayoutNs[i] = f.beatPlayoutNs[i - 1];
        f.beatPlayoutNs[0] = newestPlayoutNs;
        ++f.beatCount;
        hasBeat_ = true;
        lastBeatNs_ = newestPlayoutNs;
    }
}
}
