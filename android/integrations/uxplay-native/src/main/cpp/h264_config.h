#pragma once
#include <cstdint>
#include <cstddef>
#include <vector>
namespace tvc {
struct H264Config {
    std::vector<uint8_t> sps, pps;
    void clear() { sps.clear(); pps.clear(); }
    bool complete() const { return !sps.empty() && !pps.empty(); }
    // Only retain bounded parameter sets. Non-IDR/intra-refresh video must still reach the decoder.
    void inspect(const uint8_t *data, size_t size) {
        const size_t limit = size < 65536 ? size : 65536;
        size_t nal = 0;
        auto consume = [&](size_t end) {
            if (nal >= end) return;
            unsigned type = data[nal] & 31;
            if ((type == 7 || type == 8) && end - nal <= 16384) {
                auto &out = type == 7 ? sps : pps;
                out.assign({0, 0, 0, 1}); out.insert(out.end(), data + nal, data + end);
            }
        };
        bool haveNal = false;
        for (size_t i = 0; i + 3 <= limit;) {
            size_t length = 0;
            if (data[i] == 0 && data[i+1] == 0) {
                if (data[i+2] == 1) length = 3;
                else if (i + 4 <= limit && data[i+2] == 0 && data[i+3] == 1) length = 4;
            }
            if (!length) { ++i; continue; }
            if (haveNal) {
                const unsigned type = data[nal] & 31;
                consume(i);
                if (type == 1 || type == 5) return;
            }
            nal = i + length; haveNal = nal < limit; i += length;
        }
        if (haveNal) consume(limit);
    }
};
}
