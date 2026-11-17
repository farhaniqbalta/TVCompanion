#pragma once
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <vector>

namespace tvc {
// Bounded mono float ring fed from the native audio worker. submit() and reset() never
// block, allocate, log or call JNI; a contended call is dropped (submit) or deferred (reset).
class AmbientTap {
public:
    explicit AmbientTap(size_t capacity = 8192);
    void setEnabled(bool on) noexcept { enabled_.store(on, std::memory_order_relaxed); }
    bool enabled() const noexcept { return enabled_.load(std::memory_order_relaxed); }
    // Interleaved stereo PCM16 already scaled by `multiplier`; stores (L+R)/2/32768/multiplier clamped to [-1,1], 0 when multiplier<=0.
    void submit(const int16_t *stereo, int frames, float multiplier, int64_t firstFramePlayoutNs, int sampleRate) noexcept;
    // Analyzer thread. Copies newest `count` samples oldest-first; false if fewer than `count` written since reset.
    bool latest(float *out, size_t count, int64_t &newestPlayoutNs, uint64_t &totalWritten);
    // Clears and bumps generation. If the lock is busy the clear is applied by the next lock holder.
    void reset() noexcept;
    uint32_t generation() const noexcept { return generation_.load(std::memory_order_relaxed); }
    uint64_t dropped() const noexcept { return dropped_.load(std::memory_order_relaxed); }
    int64_t lastSubmitSteadyNs() const noexcept { return lastSubmitNs_.load(std::memory_order_relaxed); }

private:
    void applyPendingResetLocked() noexcept {
        if (resetPending_.exchange(false, std::memory_order_acq_rel)) { total_ = 0; newestNs_ = 0; }
    }
    std::mutex mutex_;                 // guards ring_, total_, newestNs_
    std::vector<float> ring_;          // preallocated
    uint64_t total_ = 0;
    int64_t newestNs_ = 0;
    std::atomic<bool> enabled_{false};
    std::atomic<bool> resetPending_{false};
    std::atomic<uint32_t> generation_{0};
    std::atomic<uint64_t> dropped_{0};
    std::atomic<int64_t> lastSubmitNs_{0};
};
}
