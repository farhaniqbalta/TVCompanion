#include "ambient/beat_tracker.h"
#include <algorithm>
#include <cmath>

namespace tvc {
namespace {
constexpr float ALPHA = 0.9f;        // weight of the past in the cumulative score
constexpr float TIGHTNESS = 5.0f;    // how strictly a beat must follow the previous one by one period
constexpr float CONF_MIN = 0.2f;     // autocorrelation at the tempo lag over lag 0; white noise sits near 0.1
constexpr float ACTIVITY_MIN = 0.001f;  // mean onset strength; steady tone ~0.0003, lone kick ~0.0024
const float LAG_120 = 60.0f * BeatTracker::FPS / 120.0f;
}

void BeatTracker::reset() { *this = BeatTracker(); }

float BeatTracker::bestPast(const float *score, int ahead, const float *future, int futureLen) const {
    const int lo = std::max(1, (int)std::lround(period_ / 2)), hi = (int)std::lround(2 * period_);
    float best = 0;
    for (int v = lo; v <= hi; ++v) {
        float c;
        if (v >= ahead) {
            const int ago = v - ahead;
            if (ago >= count_) break;
            c = at(score, ago);
        } else {
            const int j = ahead - v - 1;
            if (j >= futureLen) continue;
            c = future[j];
        }
        const float lw = TIGHTNESS * std::log(v / period_);
        best = std::max(best, std::exp(-0.5f * lw * lw) * c);
    }
    return best;
}

bool BeatTracker::push(float onset) {
    const float c = period_ > 0 ? (1 - ALPHA) * onset + ALPHA * bestPast(score_, 1, nullptr, 0) : onset;
    odf_[head_] = onset;
    score_[head_] = c;
    head_ = (head_ + 1) % H;
    count_ = std::min(count_ + 1, H);
    const long n = frame_++;
    bool estimated = false;
    if (++sinceEstimate_ >= ESTIMATE_EVERY && count_ >= MIN_HISTORY) { sinceEstimate_ = 0; estimateTempo(); estimated = true; }
    if (period_ <= 0) return false;
    if (estimated) {
        // (Re)anchor the phase from the whole history, not one loud hit: a single strong off-beat bass note must not
        // set the grid, and a grid that did lock half a beat off recovers within about a second.
        // Only on a clear margin: with 8th-note hi-hats both phases score alike, and flipping on noise drops beats.
        const int phase = bestPhase();
        const long anchor = n - phase;
        float d = std::fmod((float)(anchor - lastBeat_), period_);
        if (d > period_ / 2) d -= period_; else if (d < -period_ / 2) d += period_;
        const int current = lastBeat_ < 0 ? 0 : (int)std::lround(std::fmod((float)(n - lastBeat_), period_));
        const bool disagrees = std::fabs(d) > period_ / 4 && phaseSum(phase) >= 1.3f * phaseSum(current);
        phaseVotes_ = disagrees ? phaseVotes_ + 1 : 0;
        if (lastBeat_ < 0 || phaseVotes_ >= 2) {  // like tempo: a new phase must win twice in a row
            phaseVotes_ = 0;
            lastBeat_ = anchor;
            nextBeat_ = -1;
            predictAt_ = lastBeat_ + std::lround(period_ / 2);
        }
    }
    if (nextBeat_ < 0 && n >= predictAt_) predict();
    if (n != nextBeat_) return false;
    lastBeat_ = n;
    nextBeat_ = -1;
    predictAt_ = n + std::lround(period_ / 2);
    return confidence_ >= CONF_MIN;
}

float BeatTracker::phaseSum(int phase) const {
    float sum = 0;
    for (float ago = (float)phase; ago < count_; ago += period_) sum += at(odf_, (int)std::lround(ago));
    return sum;
}

int BeatTracker::bestPhase() const {
    const int p = std::max(1, (int)std::lround(period_));
    int best = 0;
    for (int phase = 1; phase < p && phase < count_; ++phase) if (phaseSum(phase) > phaseSum(best)) best = phase;
    return best;
}

void BeatTracker::predict() {
    const long n = frame_ - 1;
    const int len = std::min(2 * LAG_MAX, (int)std::lround(1.5f * period_));
    const float expected = std::max(1.0f, lastBeat_ + period_ - n), sigma = period_ / 4;
    float bestW = -1;
    int bestJ = 1;
    for (int j = 1; j <= len; ++j) {
        future_[j - 1] = ALPHA * bestPast(score_, j, future_, j - 1);
        const float d = (j - expected) / sigma;
        const float w = future_[j - 1] * std::exp(-0.5f * d * d);
        if (w > bestW) { bestW = w; bestJ = j; }
    }
    nextBeat_ = n + bestJ;
}

void BeatTracker::estimateTempo() {
    const int len = count_;
    float x[H], mean = 0;
    for (int i = 0; i < len; ++i) mean += x[i] = at(odf_, len - 1 - i);
    mean /= len;
    for (int i = 0; i < len; ++i) x[i] -= mean;
    const int maxLag = std::min(4 * LAG_MAX, len - 1);
    float r[4 * LAG_MAX + 1] = {};
    for (int l = 0; l <= maxLag; ++l) {
        double s = 0;
        for (int i = 0; i + l < len; ++i) s += (double)x[i] * x[i + l];
        r[l] = (float)(s / (len - l));
    }
    float score[LAG_MAX + 2] = {};
    int best = LAG_MIN;
    for (int l = LAG_MIN; l <= LAG_MAX; ++l) {
        float s = 0;
        for (int k = 1; k <= 4 && k * l <= maxLag; ++k) s += std::max(0.0f, r[k * l]) / k;
        const float oct = std::log2(l / LAG_120);
        score[l] = s * std::exp(-0.5f * oct * oct);
        if (score[l] > score[best]) best = l;
    }
    confidence_ = (mean >= ACTIVITY_MIN && r[0] > 0) ? r[best] / r[0] : 0;
    if (score[best] <= 0) return;
    float lag = (float)best;
    if (best > LAG_MIN && best < LAG_MAX) {  // parabolic peak for sub-frame period
        const float a = score[best - 1], b = score[best], c = score[best + 1], den = a - 2 * b + c;
        if (den < 0) lag += 0.5f * (a - c) / den;
    }
    if (period_ <= 0) { period_ = lag; return; }
    if (std::fabs(lag - period_) < 0.08f * period_) { period_ = 0.8f * period_ + 0.2f * lag; pending_ = 0; return; }
    // A different tempo must win twice in a row before replacing the current one.
    if (pending_ > 0 && std::fabs(lag - pending_) < 0.08f * pending_) { period_ = lag; pending_ = 0; }
    else pending_ = lag;
}
}
