#pragma once

namespace tvc {
// Causal tempo + phase tracker over an onset-strength signal at a fixed frame rate (hop 512 @ 44.1 kHz).
// Tempo: comb-weighted autocorrelation of ~6 s of onset strength with a prior around 120 BPM.
// Phase: cumulative beat score; halfway between beats the next beat is predicted from it, so beats land on the
// grid even when a kick is soft or missing, and off-beat bass hits do not move it. Same idea as BTrack
// (Stark, Davies & Plumbley, DAFx 2009); written from the papers, no code taken. One thread, no allocation.
class BeatTracker {
public:
    static constexpr float FPS = 44100.0f / 512.0f;
    void reset();
    // Feed one onset-strength value per frame. Returns true when this frame is a beat.
    bool push(float onset);
    float periodFrames() const { return period_; }  // 0 until a tempo is established
    float confidence() const { return confidence_; }

private:
    static constexpr int H = 512;                       // history frames (~5.9 s)
    static constexpr int LAG_MIN = 29, LAG_MAX = 86;    // 178..60 BPM
    static constexpr int ESTIMATE_EVERY = 43, MIN_HISTORY = 172, PHASE_SPAN = 344;  // phase from the last ~4 s
    float at(const float *ring, int framesAgo) const { return ring[((head_ - 1 - framesAgo) % H + H) % H]; }
    float bestPast(const float *score, int framesAgo, const float *future, int futureLen) const;
    int bestPhase() const;  // frames ago of the most recent beat on the strongest comb through recent onsets
    void estimateTempo();
    void predict();

    float odf_[H] = {}, score_[H] = {};
    int head_ = 0, count_ = 0, sinceEstimate_ = 0;
    float period_ = 0, pending_ = 0, confidence_ = 0;
    long frame_ = 0, lastBeat_ = -1, predictAt_ = -1, nextBeat_ = -1;
    float future_[2 * LAG_MAX] = {};
};
}
