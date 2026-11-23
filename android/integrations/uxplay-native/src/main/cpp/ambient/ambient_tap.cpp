#include "ambient/ambient_tap.h"
#include <chrono>

namespace tvc {
AmbientTap::AmbientTap(size_t capacity) : ring_(capacity ? capacity : 1, 0.0f) {}

void AmbientTap::submit(const int16_t *stereo, int frames, float multiplier, int64_t firstFramePlayoutNs, int sampleRate) noexcept {
    if (!enabled_.load(std::memory_order_relaxed) || frames <= 0 || !stereo) return;
    std::unique_lock<std::mutex> lock(mutex_, std::try_to_lock);
    if (!lock.owns_lock()) { dropped_.fetch_add(1, std::memory_order_relaxed); return; }
    applyPendingResetLocked();
    const size_t cap = ring_.size();
    const float inv = multiplier > 0.0f ? 1.0f / (32768.0f * multiplier) : 0.0f;
    size_t w = (size_t)(total_ % cap);
    for (int i = 0; i < frames; ++i) {
        const int32_t mono = ((int32_t)stereo[2 * i] + (int32_t)stereo[2 * i + 1]) / 2;
        float v = (float)mono * inv;
        v = v > 1.0f ? 1.0f : (v < -1.0f ? -1.0f : v);
        if (v != v) v = 0.0f;  // multiplier tiny enough to overflow to inf*0
        ring_[w] = v;
        if (++w == cap) w = 0;
    }
    total_ += (uint64_t)frames;
    if (sampleRate > 0) rate_ = sampleRate;
    newestNs_ = firstFramePlayoutNs + (sampleRate > 0 ? (int64_t)(frames - 1) * 1000000000LL / sampleRate : 0);
    lastSubmitNs_.store(std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count(), std::memory_order_relaxed);
}

int AmbientTap::window(float *out, size_t count, uint64_t end, int64_t &endPlayoutNs, uint64_t &totalWritten) {
    std::lock_guard<std::mutex> lock(mutex_);
    applyPendingResetLocked();
    totalWritten = total_;
    const size_t cap = ring_.size();
    if (count > cap || end < count || end > total_) return 1;
    if (total_ - (end - count) > cap) return 2;
    endPlayoutNs = newestNs_ - (int64_t)(total_ - end) * 1000000000LL / rate_;
    size_t r = (size_t)((end - count) % cap);
    for (size_t i = 0; i < count; ++i) {
        out[i] = ring_[r];
        if (++r == cap) r = 0;
    }
    return 0;
}

void AmbientTap::reset() noexcept {
    generation_.fetch_add(1, std::memory_order_relaxed);
    resetPending_.store(true, std::memory_order_release);
    std::unique_lock<std::mutex> lock(mutex_, std::try_to_lock);
    if (lock.owns_lock()) applyPendingResetLocked();
}
}
