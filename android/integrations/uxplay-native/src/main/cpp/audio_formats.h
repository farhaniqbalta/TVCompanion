#pragma once
#include <array>
#include <cstdint>
namespace tvc {
inline std::array<uint8_t, 24> alacCookie(uint32_t frames) {
    std::array<uint8_t, 24> cookie{0,0,0,0, 0,16,40,10,14,2,0,255, 0,0,0,0, 0,0,0,0, 0,0,172,68};
    cookie[0] = (frames >> 24) & 255; cookie[1] = (frames >> 16) & 255;
    cookie[2] = (frames >> 8) & 255; cookie[3] = frames & 255;
    return cookie;
}
}
