#include "ambient/ambient_source.h"
#include <chrono>
#include <cmath>

namespace tvc {
bool AmbientSource::subscribe() {
    std::lock_guard<std::mutex> lock(lifeMutex_);
    if (closed_.load()) return false;
    if (++subscribers_ == 1) {
        tap_.reset();  // drop PCM left from a previous subscription
        publish(AmbientFeatures{}, tap_.generation());
        tap_.setEnabled(true);
        stop_ = false;  // thread not running: no other accessor
        thread_ = std::thread(&AmbientSource::run, this);
    }
    return true;
}

void AmbientSource::unsubscribe() {
    std::lock_guard<std::mutex> lock(lifeMutex_);
    if (subscribers_ == 0 || --subscribers_ > 0) return;
    stopThreadLocked();
}

void AmbientSource::close() {
    std::lock_guard<std::mutex> lock(lifeMutex_);
    closed_.store(true);
    subscribers_ = 0;
    stopThreadLocked();
}

void AmbientSource::stopThreadLocked() {
    tap_.setEnabled(false);
    if (!thread_.joinable()) return;
    {
        std::lock_guard<std::mutex> lock(wakeMutex_);
        stop_ = true;
    }
    wake_.notify_all();
    thread_.join();
}

void AmbientSource::publish(const AmbientFeatures &features, uint32_t generation) {
    std::lock_guard<std::mutex> lock(snapshotMutex_);
    snapshot_ = features;
    snapshotGeneration_ = generation;
}

void AmbientSource::run() {
    AmbientFeatures features;
    uint32_t generation = tap_.generation();
    uint64_t lastTotal = 0;
    extractor_.reset();
    std::unique_lock<std::mutex> lock(wakeMutex_);
    while (!wake_.wait_for(lock, std::chrono::milliseconds(5), [this] { return stop_; })) {
        lock.unlock();
        const uint32_t before = tap_.generation();
        if (before != generation) {
            generation = before;
            extractor_.reset();
            features = AmbientFeatures{};
            lastTotal = 0;
            publish(features, generation);
        }
        int64_t newest = 0;
        uint64_t total = 0;
        const bool ok = tap_.latest(window_, 1024, newest, total);
        // latest() does not say which generation it read: a reset during the copy invalidates the window.
        if (ok && tap_.generation() == before && total >= lastTotal + 512) {
            lastTotal = total;
            extractor_.process(window_, newest, features);
            publish(features, generation);
        }
        lock.lock();
    }
}

int AmbientSource::read(float *out, int64_t now) {
    if (closed_.load()) return 2;
    AmbientFeatures f;
    uint32_t generation;
    {
        std::lock_guard<std::mutex> lock(snapshotMutex_);
        f = snapshot_;
        generation = snapshotGeneration_;
    }
    const int64_t last = tap_.lastSubmitSteadyNs();
    const bool recent = last != 0 && now - last < 400000000LL;
    out[0] = (float)(generation % 1048576u);
    out[1] = recent ? 1.0f : 0.0f;
    out[2] = f.rms;
    out[3] = f.peak;
    for (int b = 0; b < 16; ++b) out[4 + b] = f.bands[b];
    out[20] = (float)(f.beatCount % 1048576u);
    for (int i = 0; i < 4; ++i)
        out[21 + i] = f.beatPlayoutNs[i] == 0 ? 99000.0f : (float)((double)(now - f.beatPlayoutNs[i]) / 1e6);
    return recent ? 0 : 1;
}
}
