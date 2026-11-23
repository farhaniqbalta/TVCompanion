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
    std::fill(prevLog_, prevLog_ + FLUX_BINS, 0.0f);
    hasPrev_ = false;
    tracker_.reset();
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

    // Log-compressed spectral flux, half-wave rectified per bin (Dixon 2006): bass note changes and kicks count,
    // a rising and a falling bin no longer cancel out.
    float flux = 0;
    for (int k = 1; k < FLUX_BINS; ++k) {
        const float l = std::log1p(100.0f * mags_[k] * norm);
        flux += std::max(0.0f, l - prevLog_[k]);
        prevLog_[k] = l;
    }
    flux = hasPrev_ ? flux / (FLUX_BINS - 1) : 0.0f;
    hasPrev_ = true;
    if (tracker_.push(flux)) {
        for (int i = 3; i > 0; --i) f.beatPlayoutNs[i] = f.beatPlayoutNs[i - 1];
        // Hann weights peak mid-window, so an onset registers when it is near the centre, not the newest sample.
        f.beatPlayoutNs[0] = newestPlayoutNs - (int64_t)(N / 2) * 1000000000LL / 44100;
        ++f.beatCount;
    }
}
}
