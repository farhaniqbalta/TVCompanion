#include "audio_output.h"
#include "playout_clock.h"
#include "audio_formats.h"
#include <aaudio/AAudio.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include "ALACDecoder.h"
#include "ALACBitUtilities.h"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <mutex>
#include <thread>
#include <vector>

namespace tvc {
namespace {
constexpr int rate = 44100, channels = 2;
int64_t nowNs() { return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count(); }
}
struct AudioOutput::Impl {
    struct Packet { std::vector<uint8_t> bytes; int64_t target; };
    std::function<void(int)> event;
    std::mutex mutex;
    std::condition_variable wake;
    std::deque<Packet> queue;
    std::thread worker;
    bool closing = false;
    unsigned type = 0, spf = 480;
    uint64_t version = 0;
    std::atomic<bool> allowed{false}, broken{false}, needsFocus{true};
    std::atomic<float> gain{1}, duck{1};
    explicit Impl(std::function<void(int)> callback) : event(std::move(callback)) {}
    ~Impl() {
        { std::lock_guard<std::mutex> lock(mutex); closing = true; queue.clear(); }
        wake.notify_all(); if (worker.joinable()) worker.join();
    }
    void run() {
        AMediaCodec *decoder = nullptr;
        AAudioStream *stream = nullptr;
        std::unique_ptr<ALACDecoder> alac;
        uint64_t current = 0;
        unsigned activeType = 0, activeSpf = 0;
        bool playing = false;
        auto release = [&]() {
            if (stream) { AAudioStream_requestStop(stream); AAudioStream_close(stream); stream = nullptr; }
            if (decoder) { AMediaCodec_stop(decoder); AMediaCodec_delete(decoder); decoder = nullptr; }
            alac.reset(); playing = false;
        };
        auto fail = [&]() { release(); broken = true; event(6); event(7); };
        auto open = [&]() -> bool {
            if (activeType == 2) {
                // ALACSpecificConfig: immutable 16-bit stereo 44.1 kHz stream parameters.
                auto cookie = alacCookie(activeSpf);
                alac.reset(new ALACDecoder());
                if (alac->Init(cookie.data(), cookie.size()) != 0) return false;
            } else if (activeType == 4 || activeType == 8) {
                AMediaFormat *format = AMediaFormat_new();
                AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, "audio/mp4a-latm");
                AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, rate);
                AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, channels);
                AMediaFormat_setInt32(format, "aac-profile", activeType == 8 ? 39 : 2);
                AMediaFormat_setInt32(format, "pcm-encoding", 2); // Android ENCODING_PCM_16BIT.
                uint8_t lc[] = {0x12,0x10}, eld[] = {0xf8,0xe8,0x50,0x00};
                AMediaFormat_setBuffer(format, "csd-0", activeType == 8 ? eld : lc, activeType == 8 ? sizeof(eld) : sizeof(lc));
                // Prefer the platform software AAC decoder for ELD profile consistency; no bundled AAC library.
                const char *candidates[] = {"c2.android.aac.decoder", "OMX.google.aac.decoder", nullptr};
                for (const char *name : candidates) {
                    decoder = name ? AMediaCodec_createCodecByName(name) : AMediaCodec_createDecoderByType("audio/mp4a-latm");
                    if (!decoder) continue;
                    if (AMediaCodec_configure(decoder, format, nullptr, nullptr, 0) == AMEDIA_OK && AMediaCodec_start(decoder) == AMEDIA_OK) break;
                    AMediaCodec_delete(decoder); decoder = nullptr;
                }
                AMediaFormat_delete(format);
                if (!decoder) return false;
            } else if (activeType != 1) return false;
            AAudioStreamBuilder *builder = nullptr;
            if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) return false;
            AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
            AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
            AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
            AAudioStreamBuilder_setSampleRate(builder, rate);
            AAudioStreamBuilder_setChannelCount(builder, channels);
            AAudioStreamBuilder_setBufferCapacityInFrames(builder, rate / 10);
            aaudio_result_t result = AAudioStreamBuilder_openStream(builder, &stream);
            AAudioStreamBuilder_delete(builder);
            if (result != AAUDIO_OK || !stream) return false;
            if (AAudioStream_getSampleRate(stream) != rate || AAudioStream_getChannelCount(stream) != channels || AAudioStream_getFormat(stream) != AAUDIO_FORMAT_PCM_I16) return false;
            AAudioStream_setBufferSizeInFrames(stream, std::min(AAudioStream_getBufferCapacityInFrames(stream), rate / 50));
            return AAudioStream_requestStart(stream) == AAUDIO_OK;
        };
        auto writePcm = [&](const uint8_t *bytes, size_t size, int64_t target) -> bool {
            if (size == 0 || size % 4 != 0 || size > 65536) return false;
            if (expired(target, nowNs())) return true;
            // Account for already queued hardware frames before waiting for source-clock playout.
            int64_t framePosition = 0, timestamp = 0;
            int64_t nextStart = nowNs() + static_cast<int64_t>(AAudioStream_getBufferSizeInFrames(stream)) * 1000000000 / rate;
            if (AAudioStream_getTimestamp(stream, CLOCK_MONOTONIC, &framePosition, &timestamp) == AAUDIO_OK)
                nextStart = timestamp + (AAudioStream_getFramesWritten(stream) - framePosition) * 1000000000 / rate;
            int64_t wait = target - nextStart;
            if (wait > 0 && wait < 500000000) {
                std::unique_lock<std::mutex> lock(mutex);
                if (wake.wait_for(lock, std::chrono::nanoseconds(wait), [&] { return closing || !allowed || version != current; })) return true;
            }
            std::vector<int16_t> samples(size / 2);
            const float multiplier = std::clamp(gain.load() * duck.load(), 0.0f, 1.0f);
            for (size_t i = 0; i < samples.size(); ++i) { int16_t sample; std::memcpy(&sample, bytes + i*2, 2); samples[i] = static_cast<int16_t>(sample * multiplier); }
            int frames = static_cast<int>(samples.size()) / channels, offset = 0;
            while (offset < frames) {
                { std::lock_guard<std::mutex> lock(mutex); if (closing || !allowed || version != current) return true; }
                int count = std::min(480, frames - offset);
                int result = AAudioStream_write(stream, samples.data() + offset * channels, count, 20000000);
                if (result < 0) return false;
                if (result == 0) return true; // Bounded wait; never spin or grow a backlog.
                offset += result;
                if (!playing) { playing = true; event(5); }
            }
            return true;
        };
        for (;;) {
            Packet packet;
            {
                std::unique_lock<std::mutex> lock(mutex);
                auto ready = [&] { return closing || version != current || (!queue.empty() && allowed); };
                if (stream) {
                    if (!wake.wait_for(lock, std::chrono::seconds(1), ready)) {
                        allowed = false; queue.clear(); lock.unlock(); release(); event(7); needsFocus = true; continue;
                    }
                } else wake.wait(lock, ready);
                if (closing) break;
                if (version != current) {
                    current = version; activeType = type; activeSpf = spf; lock.unlock(); release(); continue;
                }
                if (queue.empty()) continue;
                packet = std::move(queue.front()); queue.pop_front();
            }
            if (broken || expired(packet.target, nowNs())) continue;
            if (!stream && !open()) { fail(); continue; }
            if (activeType == 1) { if (!writePcm(packet.bytes.data(), packet.bytes.size(), packet.target)) fail(); }
            else if (activeType == 2) {
                // Reference decoder may read ahead: keep zero padding outside the packet bounds.
                const auto count = packet.bytes.size(); packet.bytes.resize(count + 16, 0);
                BitBuffer bits; BitBufferInit(&bits, packet.bytes.data(), static_cast<uint32_t>(count));
                std::vector<uint8_t> pcm(4096 * channels * sizeof(int16_t)); uint32_t frames = 0;
                if (alac->Decode(&bits, pcm.data(), activeSpf, channels, &frames) == 0 && frames <= activeSpf)
                    if (!writePcm(pcm.data(), frames * channels * sizeof(int16_t), packet.target)) fail();
            } else {
                ssize_t index = AMediaCodec_dequeueInputBuffer(decoder, 1000);
                if (index < 0 && index != AMEDIACODEC_INFO_TRY_AGAIN_LATER) { fail(); continue; }
                if (index >= 0) {
                    size_t capacity = 0; uint8_t *input = AMediaCodec_getInputBuffer(decoder, index, &capacity);
                    const uint8_t *data = packet.bytes.data(); size_t length = packet.bytes.size();
                    if (activeType == 4 && length >= 7 && data[0] == 0xff && (data[1] & 0xf0) == 0xf0) {
                        size_t header = data[1] & 1 ? 7 : 9;
                        if (length >= header) { data += header; length -= header; }
                    }
                    if (input && length <= capacity) { std::memcpy(input, data, length); if (AMediaCodec_queueInputBuffer(decoder, index, 0, length, packet.target / 1000, 0) != AMEDIA_OK) { fail(); continue; } }
                    else { AMediaCodec_queueInputBuffer(decoder, index, 0, 0, 0, 0); fail(); continue; }
                }
                for (int n = 0; n < 16 && decoder; ++n) {
                    AMediaCodecBufferInfo info{}; ssize_t output = AMediaCodec_dequeueOutputBuffer(decoder, &info, 0);
                    if (output == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
                        AMediaFormat *format = AMediaCodec_getOutputFormat(decoder);
                        int32_t sampleRate = rate, channelCount = channels, encoding = 2;
                        AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, &sampleRate);
                        AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &channelCount);
                        AMediaFormat_getInt32(format, "pcm-encoding", &encoding); AMediaFormat_delete(format);
                        if (sampleRate != rate || channelCount != channels || encoding != 2) fail();
                        continue;
                    }
                    if (output == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) continue;
                    if (output == AMEDIACODEC_INFO_TRY_AGAIN_LATER) break;
                    if (output < 0) { fail(); break; }
                    size_t capacity = 0; uint8_t *pcm = AMediaCodec_getOutputBuffer(decoder, output, &capacity);
                    bool ok = true;
                    if (info.size > 0 && pcm && info.offset >= 0 && static_cast<size_t>(info.offset) + info.size <= capacity)
                        ok = writePcm(pcm + info.offset, info.size, info.presentationTimeUs * 1000);
                    AMediaCodec_releaseOutputBuffer(decoder, output, false);
                    if (!ok) { fail(); break; }
                }
            }
        }
        release();
    }
};
AudioOutput::AudioOutput(std::function<void(int)> event) : impl(new Impl(std::move(event))) {}
AudioOutput::~AudioOutput() = default;
void AudioOutput::configure(unsigned type, unsigned samplesPerFrame) {
    if (samplesPerFrame == 0) samplesPerFrame = type == 8 ? 480 : type == 4 ? 1024 : 352;
    if (samplesPerFrame > 4096 || (type != 1 && type != 2 && type != 4 && type != 8)) { impl->broken = true; impl->event(6); return; }
    { std::lock_guard<std::mutex> lock(impl->mutex);
      impl->type = type; impl->spf = samplesPerFrame; ++impl->version; impl->queue.clear(); impl->broken = false;
      if (!impl->worker.joinable()) impl->worker = std::thread([state = impl.get()] { state->run(); }); }
    impl->needsFocus = false; impl->event(9); impl->wake.notify_all();
}
void AudioOutput::submit(unsigned type, const uint8_t *data, size_t size, int64_t target) {
    if (!data || !size || size > 16384 || impl->broken) return;
    { std::lock_guard<std::mutex> lock(impl->mutex); if (impl->closing || type != impl->type) return; }
    if (!impl->allowed) { if (impl->needsFocus.exchange(false)) impl->event(9); return; }
    { std::lock_guard<std::mutex> lock(impl->mutex);
      if (impl->closing || type != impl->type) return;
      if (impl->queue.size() == 32) impl->queue.pop_front();
      impl->queue.push_back({std::vector<uint8_t>(data, data + size), target}); }
    impl->wake.notify_all();
}
void AudioOutput::focus(bool allowed, float duck) {
    impl->needsFocus = false; impl->allowed = allowed; impl->duck = std::clamp(duck, 0.0f, 1.0f);
    if (!allowed) { std::lock_guard<std::mutex> lock(impl->mutex); impl->queue.clear(); ++impl->version; }
    impl->wake.notify_all();
}
void AudioOutput::volume(float decibels) { impl->gain = !std::isfinite(decibels) || decibels <= -144 ? 0.0f : std::clamp(std::pow(10.0f, decibels / 20.0f), 0.0f, 1.0f); }
void AudioOutput::flush() { std::lock_guard<std::mutex> lock(impl->mutex); impl->queue.clear(); ++impl->version; impl->wake.notify_all(); }
void AudioOutput::end() {
    focus(false, 1);
    { std::lock_guard<std::mutex> lock(impl->mutex); impl->type = 0; ++impl->version; impl->queue.clear(); }
    impl->wake.notify_all(); impl->event(7);
}
}
