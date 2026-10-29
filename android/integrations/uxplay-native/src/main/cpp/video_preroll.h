#pragma once
#include <cstddef>
#include <cstdint>
#include <deque>
#include <vector>
namespace tvc {
// Receiver-local compressed startup data only. Never buffer an entire hidden session.
struct VideoPreroll {
    struct Packet { std::vector<uint8_t> bytes; int64_t pts; };
    std::deque<Packet> packets;
    size_t bytes = 0;
    bool exhausted = false;
    void clear() { packets.clear(); bytes = 0; exhausted = false; }
    void append(const uint8_t *data, size_t size, int64_t pts) {
        if (exhausted) return;
        if (packets.size() >= 60 || size > 2 * 1024 * 1024 - bytes ||
            (!packets.empty() && pts - packets.front().pts > 1000000)) {
            packets.clear(); bytes = 0; exhausted = true; return;
        }
        packets.push_back({std::vector<uint8_t>(data, data + size), pts}); bytes += size;
    }
};
}
