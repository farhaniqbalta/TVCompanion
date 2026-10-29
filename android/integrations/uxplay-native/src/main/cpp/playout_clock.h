#pragma once
#include <cstdint>
namespace tvc {
constexpr int64_t playoutDelay = 80000000; // Shared audio/video jitter allowance, nanoseconds.
inline int64_t presentationTime(uint64_t sourceLocal, uint64_t wallNow, int64_t monotonicNow) {
    if (!sourceLocal) return monotonicNow + playoutDelay;
    const uint64_t difference = sourceLocal > wallNow ? sourceLocal - wallNow : wallNow - sourceLocal;
    if (difference > 500000000) return monotonicNow + playoutDelay; // Discontinuity, never wait unboundedly.
    const int64_t delta = sourceLocal >= wallNow ? static_cast<int64_t>(difference) : -static_cast<int64_t>(difference);
    return monotonicNow + playoutDelay + delta;
}
inline bool expired(int64_t target, int64_t now) { return target < now - 150000000; }
}
