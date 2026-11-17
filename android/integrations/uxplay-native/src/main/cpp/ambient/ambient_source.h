#pragma once
#include "ambient/ambient_tap.h"
#include "ambient/feature_extractor.h"
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <thread>

namespace tvc {
// Owns the tap and an analyzer thread that runs while at least one subscriber exists.
// subscribe/unsubscribe/close are safe from any thread except the analyzer thread.
class AmbientSource {
public:
    AmbientSource() = default;
    ~AmbientSource() { close(); }
    AmbientSource(const AmbientSource &) = delete;
    AmbientSource &operator=(const AmbientSource &) = delete;

    AmbientTap &tap() { return tap_; }
    bool subscribe();      // false once closed; first subscriber enables tap + starts analyzer thread
    void unsubscribe();    // last subscriber stops/joins thread, disables tap
    void close();          // idempotent: closed=true, disable tap, join thread
    // Fills AmbientFrame layout (25 floats). Returns 0 OK, 1 STALE (no PCM for 400 ms), 2 CLOSED.
    int read(float *out, int64_t nowMonotonicNs);

private:
    void run();
    void stopThreadLocked();
    void publish(const AmbientFeatures &features, uint32_t generation);

    AmbientTap tap_;
    std::mutex lifeMutex_;            // guards subscribers_, thread_
    int subscribers_ = 0;
    std::thread thread_;
    std::atomic<bool> closed_{false};
    std::mutex wakeMutex_;            // guards stop_
    std::condition_variable wake_;
    bool stop_ = false;
    std::mutex snapshotMutex_;        // analyzer thread and read() only
    AmbientFeatures snapshot_;
    uint32_t snapshotGeneration_ = 0;
    FeatureExtractor extractor_;      // analyzer thread only
    float window_[1024];              // analyzer thread only
};
}
