#undef NDEBUG  // asserts are the checks
#include "ambient/ambient_source.h"
#include "ambient/ambient_tap.h"
#include "ambient/feature_extractor.h"
#include "ambient/fft.h"
#include <algorithm>
#include <chrono>
#include <cassert>
#include <cmath>
#include <iostream>
#include <thread>
#include <vector>

using namespace tvc;

static int64_t steadyNs() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

// Kick drum: exponential pitch sweep 140->45 Hz over 120 ms, amplitude decaying exponentially to -60 dB over 350 ms.
static void addKick(std::vector<float> &s, size_t start) {
    double phase = 0;
    for (size_t i = start; i < s.size() && i < start + 44100 / 2; ++i) {
        const double t = (i - start) / 44100.0;
        const double f = 140.0 * std::pow(45.0 / 140.0, std::min(t, 0.12) / 0.12);
        phase += 2 * M_PI * f / 44100.0;
        s[i] += (float)(0.5 * std::exp(-t * std::log(1000.0) / 0.35) * std::sin(phase));
    }
}

static std::vector<float> kickTrack(double seconds) {
    std::vector<float> s((size_t)(seconds * 44100), 0.0f);
    for (double t = 0; t < seconds; t += 60.0 / 118.0) addKick(s, (size_t)(t * 44100));
    return s;
}

// Feeds hop-512 windows; playout of the newest sample = sampleIndex * 1e9 / 44100.
static AmbientFeatures runExtractor(const std::vector<float> &s) {
    FeatureExtractor fx; AmbientFeatures f;
    for (size_t end = 1024; end <= s.size(); end += 512)
        fx.process(s.data() + end - 1024, (int64_t)((end - 1) * 1e9 / 44100), f);
    return f;
}

static int argmaxBand(double hz) {
    std::vector<float> w(1024);
    for (int i = 0; i < 1024; ++i) w[i] = 0.01f * (float)std::sin(2 * M_PI * hz * i / 44100.0);
    FeatureExtractor fx; AmbientFeatures f; fx.process(w.data(), 1, f);
    for (float b : f.bands) assert(std::isfinite(b) && b >= 0 && b <= 1);
    return (int)(std::max_element(f.bands, f.bands + 16) - f.bands);
}

static int bandOf(double hz) {
    for (int b = 0; b < 16; ++b)
        if (hz >= 40 * std::pow(400.0, b / 16.0) && hz < 40 * std::pow(400.0, (b + 1) / 16.0)) return b;
    return -1;
}

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

    // silenceIsZero
    {
        FeatureExtractor fx; AmbientFeatures f; std::vector<float> w(1024, 0.0f);
        fx.process(w.data(), 0, f); assert(f.rms == 0 && f.peak == 0 && f.beatCount == 0);
        for (float b : f.bands) assert(b == 0 && std::isfinite(b));
    }

    // sineHitsExpectedBand
    assert(argmaxBand(100) == bandOf(100));
    assert(argmaxBand(1000) == bandOf(1000));

    // onsetsOnKicks: 8 s at 118 BPM has 16 kicks
    {
        AmbientFeatures f = runExtractor(kickTrack(8.0));
        std::cout << "kick onsets: " << f.beatCount << "\n";
        assert(f.beatCount >= 15 && f.beatCount <= 17);
        assert(f.beatPlayoutNs[0] > f.beatPlayoutNs[1] && f.beatPlayoutNs[1] > f.beatPlayoutNs[2] && f.beatPlayoutNs[3] > 0);
        const double gapMs = (f.beatPlayoutNs[0] - f.beatPlayoutNs[1]) / 1e6;
        assert(gapMs > 480 && gapMs < 540);
        assert(std::isfinite(f.rms) && f.peak <= 1.0f);
    }

    // noOnsetOnSustainedTone
    {
        std::vector<float> s(8 * 44100);
        for (size_t i = 0; i < s.size(); ++i) s[i] = 0.5f * (float)std::sin(2 * M_PI * 100.0 * i / 44100.0);
        AmbientFeatures f = runExtractor(s);
        std::cout << "sustained tone onsets: " << f.beatCount << "\n";
        assert(f.beatCount <= 1);
    }

    // refractory: two kicks 150 ms apart count once; a third 600 ms later counts
    {
        std::vector<float> s(2 * 44100, 0.0f);
        addKick(s, 4410); addKick(s, 4410 + 6615);
        assert(runExtractor(s).beatCount == 1);
        addKick(s, 4410 + 26460);
        assert(runExtractor(s).beatCount == 2);
    }

    // closeBeforeUnsubscribe
    {
        AmbientSource src; assert(src.subscribe()); src.close(); float o[25];
        assert(src.read(o, 0) == 2); src.unsubscribe(); assert(!src.subscribe());
        src.close(); assert(src.read(o, steadyNs()) == 2);
    }

    // openCloseOpen, double close, and fast thread start/stop
    {
        AmbientSource src;
        const auto t0 = std::chrono::steady_clock::now();
        for (int i = 0; i < 50; ++i) {
            assert(src.subscribe()); assert(src.tap().enabled());
            src.unsubscribe(); assert(!src.tap().enabled());
        }
        assert(std::chrono::steady_clock::now() - t0 < std::chrono::seconds(2));
        assert(src.subscribe() && src.subscribe()); src.unsubscribe(); assert(src.tap().enabled());
        src.unsubscribe(); src.unsubscribe(); assert(!src.tap().enabled());
        float o[25]; assert(src.read(o, steadyNs()) == 1);  // no PCM yet: stale
        for (int i = 21; i < 25; ++i) assert(o[i] == 99000.0f);
        src.close(); src.close(); assert(!src.tap().enabled() && !src.subscribe());
    }

    // concurrent subscribe/unsubscribe/close from several threads
    {
        AmbientSource src;
        std::thread c1([&] { for (int i = 0; i < 200; ++i) if (src.subscribe()) src.unsubscribe(); });
        std::thread c2([&] { float o[25]; for (int i = 0; i < 2000; ++i) src.read(o, steadyNs()); });
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
        std::thread c3([&] { src.close(); });
        c1.join(); c2.join(); c3.join();
        float o[25]; assert(src.read(o, 0) == 2 && !src.subscribe());
    }

    // endToEnd: kicks through the tap in 480-frame chunks with real playout times
    {
        AmbientSource src; assert(src.subscribe());
        const std::vector<float> kicks = kickTrack(1.6);
        std::vector<int16_t> pcm(2 * kicks.size());
        for (size_t i = 0; i < kicks.size(); ++i) pcm[2 * i] = pcm[2 * i + 1] = (int16_t)(kicks[i] * 32767);
        const int64_t base = steadyNs();
        for (size_t off = 0; off + 480 <= kicks.size(); off += 480) {
            src.tap().submit(pcm.data() + 2 * off, 480, 1.0f, base + (int64_t)(off * 1e9 / 44100), 44100);
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
        }
        float o[25]; int status = -1;
        for (int i = 0; i < 200; ++i) {
            status = src.read(o, steadyNs());
            if (o[20] > 0) break;
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
        std::cout << "end-to-end beats: " << o[20] << "\n";
        assert(status == 0 && o[1] == 1.0f && o[20] > 0 && o[0] == (float)(src.tap().generation() % 1048576));
        for (int i = 2; i < 25; ++i) assert(std::isfinite(o[i]));
        assert(o[21] != 99000.0f);
        assert(src.analyses() > 0);  // debug stat counts extractor runs
        src.tap().reset();  // generation change clears the snapshot
        for (int i = 0; i < 200 && o[20] != 0; ++i) { std::this_thread::sleep_for(std::chrono::milliseconds(5)); src.read(o, steadyNs()); }
        assert(o[20] == 0 && o[0] == (float)(src.tap().generation() % 1048576) && o[21] == 99000.0f);
        src.unsubscribe(); src.close();
    }

    std::cout << "ambient checks passed\n";
}
