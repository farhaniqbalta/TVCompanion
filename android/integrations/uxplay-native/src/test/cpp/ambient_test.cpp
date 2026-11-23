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

static std::vector<float> kickTrack(double seconds, double bpm = 118.0) {
    std::vector<float> s((size_t)(seconds * 44100), 0.0f);
    for (double t = 0; t < seconds; t += 60.0 / bpm) addKick(s, (size_t)(t * 44100));
    return s;
}

// Louder bass note (decaying sine at `hz`, gone to -50 dB by 600 ms, no click at the end): an off-beat bass hit.
static void addBass(std::vector<float> &s, size_t start, double hz) {
    for (size_t i = start; i < s.size() && i < start + 44100 * 3 / 5; ++i) {
        const double t = (i - start) / 44100.0;
        s[i] += (float)(0.7 * std::exp(-t * 9) * std::sin(2 * M_PI * hz * t));
    }
}

// Every beat time in seconds (window-centre stamps), hop-512 windows as the analyzer feeds them.
static std::vector<double> beatTimes(const std::vector<float> &s) {
    FeatureExtractor fx; AmbientFeatures f; std::vector<double> out; uint32_t seen = 0;
    for (size_t end = 1024; end <= s.size(); end += 512) {
        fx.process(s.data() + end - 1024, (int64_t)((end - 1) * 1e9 / 44100), f);
        if (f.beatCount != seen) { seen = f.beatCount; out.push_back(f.beatPlayoutNs[0] / 1e9); }
    }
    return out;
}

// Fraction of `expected` times (after `from` s) that have a beat within `tolMs`, and of beats (after `from`) near one.
static void scoreBeats(const std::vector<double> &beats, const std::vector<double> &expected, double from, double tolMs,
                       double &recall, double &precision) {
    auto near = [&](double x, const std::vector<double> &set) {
        for (double y : set) if (std::fabs(x - y) * 1000 <= tolMs) return true;
        return false;
    };
    int e = 0, eh = 0, b = 0, bh = 0;
    for (double x : expected) if (x >= from) { ++e; eh += near(x, beats); }
    for (double x : beats) if (x >= from) { ++b; bh += near(x, expected); }
    recall = e ? (double)eh / e : 0; precision = b ? (double)bh / b : 0;
}

static std::vector<double> grid(double seconds, double bpm) {
    std::vector<double> g;
    for (double t = 0; t < seconds; t += 60.0 / bpm) g.push_back(t);
    return g;
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
    assert(tap.window(out, 3, 3, newest, total) == 0 && total == 3);
    assert(out[0] > 0.99f && out[1] < -0.99f && std::fabs(out[2]) < 0.001f);
    assert(newest == 1000 + 2 * 1000000000LL / 44100);

    // tapUndoesGainAndMutesToZero
    int16_t half[] = {8192, 8192}; tap.reset(); tap.submit(half, 1, 0.5f, 0, 44100);
    assert(tap.window(out, 1, 1, newest, total) == 0); assert(std::fabs(out[0] - 0.5f) < 0.001f);
    tap.reset(); tap.submit(half, 1, 0.0f, 0, 44100); tap.window(out, 1, 1, newest, total); assert(out[0] == 0.0f);
    tap.reset(); tap.submit(half, 1, 1e-6f, 0, 44100); tap.window(out, 1, 1, newest, total);
    assert(std::isfinite(out[0]) && out[0] <= 1.0f);

    // disabledIsNoOp and resetBumpsGeneration
    tap.setEnabled(false); tap.reset(); uint32_t g = tap.generation(); tap.submit(half, 1, 1.0f, 0, 44100);
    assert(tap.window(out, 1, 1, newest, total) == 1); tap.reset(); assert(tap.generation() == g + 1);

    // overflowKeepsNewest: 10000 ramp frames into an 8192 ring
    tap.setEnabled(true); tap.reset();
    std::vector<int16_t> ramp(20000);
    for (int i = 0; i < 10000; ++i) ramp[2 * i] = ramp[2 * i + 1] = (int16_t)i;
    tap.submit(ramp.data(), 10000, 1.0f, 0, 48000);
    std::vector<float> win(8192);
    assert(tap.window(win.data(), 8192, 10000, newest, total) == 0 && total == 10000);
    assert(std::fabs(win[8191] - 9999 / 32768.0f) < 1e-6f);
    assert(std::fabs(win[0] - (10000 - 8192) / 32768.0f) < 1e-6f);
    assert(tap.window(win.data(), 8193, 10000, newest, total) == 1);
    // windowByIndex: an older window still in the ring, its playout, and one already overwritten
    assert(tap.window(win.data(), 1024, 9000, newest, total) == 0 && std::fabs(win[1023] - 8999 / 32768.0f) < 1e-6f);
    assert(newest == 9999 * 1000000000LL / 48000 - 1000 * 1000000000LL / 48000);
    assert(tap.window(win.data(), 1024, 2000, newest, total) == 2 && tap.window(win.data(), 1024, 10001, newest, total) == 1);

    // contentionDrops: every call is either accepted (totalWritten) or counted dropped
    tap.reset();
    uint64_t dropped0 = tap.dropped();
    const int calls = 100000;
    auto run = [&] { for (int i = 0; i < calls; ++i) tap.submit(half, 1, 1.0f, 0, 44100); };
    std::thread a(run), b(run);
    a.join(); b.join();
    tap.window(out, 1, 1, newest, total);
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

    // beatsOnSteadyKick: locks within ~3 s, every later kick gets a beat within 35 ms, nothing in between
    {
        const std::vector<double> beats = beatTimes(kickTrack(12.0));
        double recall, precision; scoreBeats(beats, grid(12.0, 118), 3.0, 35, recall, precision);
        std::cout << "steady kick: beats=" << beats.size() << " recall=" << recall << " precision=" << precision << "\n";
        assert(recall >= 0.95 && precision >= 0.95);
    }

    // beatsIgnoreOffbeatBass: louder bass hits on the "and" of 2 and 4 must not pull the grid off the kicks
    {
        std::vector<float> s = kickTrack(12.0, 120);
        for (double bar = 0; bar < 12.0; bar += 2.0) { addBass(s, (size_t)((bar + 0.75) * 44100), 55); addBass(s, (size_t)((bar + 1.75) * 44100), 82); }
        const std::vector<double> beats = beatTimes(s);
        double recall, precision; scoreBeats(beats, grid(12.0, 120), 3.0, 35, recall, precision);
        std::cout << "offbeat bass: beats=" << beats.size() << " recall=" << recall << " precision=" << precision << "\n";
        assert(recall >= 0.9 && precision >= 0.9);
    }

    // beatsFillMissingKicks: every 4th kick dropped; the beat still lands where it would have been
    {
        std::vector<float> s((size_t)(12.0 * 44100), 0.0f); std::vector<double> missing;
        int i = 0;
        for (double t = 0; t < 12.0; t += 0.5, ++i) { if (i % 4 == 3) missing.push_back(t); else addKick(s, (size_t)(t * 44100)); }
        const std::vector<double> beats = beatTimes(s);
        double recall, precision; scoreBeats(beats, missing, 3.0, 40, recall, precision);
        std::cout << "missing kicks filled: " << recall << "\n";
        assert(recall >= 0.8);
        scoreBeats(beats, grid(12.0, 120), 3.0, 40, recall, precision);
        assert(precision >= 0.95);
    }

    // skippedHopsKeepTheGrid: 20 hops (~230 ms) never analysed mid-stream; beats are back on the kicks within 1 s
    {
        const std::vector<float> s = kickTrack(12.0); FeatureExtractor fx; AmbientFeatures f;
        std::vector<double> beats; uint32_t seen = 0;
        for (size_t end = 1024; end <= s.size(); end += 512) {
            if (end == 1024 + 512 * 600) { fx.skip(20); end += 512 * 20; }
            fx.process(s.data() + end - 1024, (int64_t)((end - 1) * 1e9 / 44100), f);
            if (f.beatCount != seen) { seen = f.beatCount; beats.push_back(f.beatPlayoutNs[0] / 1e9); }
        }
        const double resume = (1024 + 512 * 620) / 44100.0;
        double recall, precision; scoreBeats(beats, grid(12.0, 118), resume + 1.0, 35, recall, precision);
        std::cout << "after skip: recall=" << recall << " precision=" << precision << "\n";
        assert(recall >= 0.95 && precision >= 0.95);
    }

    // noBeatsOnSustainedToneSilenceOrNoise
    {
        std::vector<float> s(8 * 44100);
        for (size_t i = 0; i < s.size(); ++i) s[i] = 0.5f * (float)std::sin(2 * M_PI * 100.0 * i / 44100.0);
        std::vector<float> noise(8 * 44100); uint32_t seed = 1;
        for (float &v : noise) { seed = seed * 1664525u + 1013904223u; v = 0.3f * ((seed >> 8) / 16777216.0f - 0.5f); }
        const size_t tone = beatTimes(s).size(), silence = beatTimes(std::vector<float>(8 * 44100, 0.0f)).size(),
                     hiss = beatTimes(noise).size();
        std::cout << "beats on tone: " << tone << " silence: " << silence << " noise: " << hiss << "\n";
        assert(tone == 0 && silence == 0 && hiss == 0);
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
        const std::vector<float> kicks = kickTrack(5.0);
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
        // Every 512-sample hop of what the tap accepted (contended chunks are dropped by design) is analysed once.
        const uint64_t written = (kicks.size() / 480 - src.tap().dropped()) * 480, hops = (written - 1024) / 512 + 1;
        for (int i = 0; i < 200 && src.analyses() < hops; ++i) std::this_thread::sleep_for(std::chrono::milliseconds(5));
        std::cout << "analyses: " << src.analyses() << " of " << hops << " (dropped chunks " << src.tap().dropped() << ")\n";
        assert(src.analyses() == hops);
        src.tap().reset();  // generation change clears the snapshot
        for (int i = 0; i < 200 && o[20] != 0; ++i) { std::this_thread::sleep_for(std::chrono::milliseconds(5)); src.read(o, steadyNs()); }
        assert(o[20] == 0 && o[0] == (float)(src.tap().generation() % 1048576) && o[21] == 99000.0f);
        src.unsubscribe(); src.close();
    }

    std::cout << "ambient checks passed\n";
}
