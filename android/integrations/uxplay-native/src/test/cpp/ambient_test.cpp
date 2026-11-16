#include "ambient/ambient_tap.h"
#include "ambient/fft.h"
#include <algorithm>
#include <cassert>
#include <cmath>
#include <iostream>
#include <thread>
#include <vector>

using namespace tvc;

int main() {
    // tapExtremesNoOverflow
    AmbientTap tap(8192); tap.setEnabled(true);
    int16_t ext[] = {32767, 32767, -32768, -32768, 32767, -32768};
    tap.submit(ext, 3, 1.0f, 1000, 44100);
    float out[3]; int64_t newest; uint64_t total;
    assert(tap.latest(out, 3, newest, total) && total == 3);
    assert(out[0] > 0.99f && out[1] < -0.99f && std::fabs(out[2]) < 0.001f);
    assert(newest == 1000 + 2 * 1000000000LL / 44100);

    // tapUndoesGainAndMutesToZero
    int16_t half[] = {8192, 8192}; tap.reset(); tap.submit(half, 1, 0.5f, 0, 44100);
    assert(tap.latest(out, 1, newest, total)); assert(std::fabs(out[0] - 0.5f) < 0.001f);
    tap.reset(); tap.submit(half, 1, 0.0f, 0, 44100); tap.latest(out, 1, newest, total); assert(out[0] == 0.0f);
    tap.reset(); tap.submit(half, 1, 1e-6f, 0, 44100); tap.latest(out, 1, newest, total);
    assert(std::isfinite(out[0]) && out[0] <= 1.0f);

    // disabledIsNoOp and resetBumpsGeneration
    tap.setEnabled(false); tap.reset(); uint32_t g = tap.generation(); tap.submit(half, 1, 1.0f, 0, 44100);
    assert(!tap.latest(out, 1, newest, total)); tap.reset(); assert(tap.generation() == g + 1);

    // overflowKeepsNewest: 10000 ramp frames into an 8192 ring
    tap.setEnabled(true); tap.reset();
    std::vector<int16_t> ramp(20000);
    for (int i = 0; i < 10000; ++i) ramp[2 * i] = ramp[2 * i + 1] = (int16_t)i;
    tap.submit(ramp.data(), 10000, 1.0f, 0, 48000);
    std::vector<float> win(8192);
    assert(tap.latest(win.data(), 8192, newest, total) && total == 10000);
    assert(std::fabs(win[8191] - 9999 / 32768.0f) < 1e-6f);
    assert(std::fabs(win[0] - (10000 - 8192) / 32768.0f) < 1e-6f);
    assert(!tap.latest(win.data(), 8193, newest, total));

    // contentionDrops: every call is either accepted (totalWritten) or counted dropped
    tap.reset();
    uint64_t dropped0 = tap.dropped();
    const int calls = 100000;
    auto run = [&] { for (int i = 0; i < calls; ++i) tap.submit(half, 1, 1.0f, 0, 44100); };
    std::thread a(run), b(run);
    a.join(); b.join();
    tap.latest(out, 1, newest, total);
    assert(total + (tap.dropped() - dropped0) == 2u * calls);

    // fftSine
    std::vector<float> s(1024), mags(513);
    for (int i = 0; i < 1024; ++i) s[i] = 0.5f * std::sin(2 * M_PI * 1000.0 * i / 44100.0);
    realFftMagnitudes(s.data(), 1024, mags.data());
    assert(std::max_element(mags.begin() + 1, mags.end()) - mags.begin() == 23);

    std::cout << "ambient checks passed\n";
}
