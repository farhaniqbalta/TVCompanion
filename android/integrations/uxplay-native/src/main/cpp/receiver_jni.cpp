// Receiver implementation adapter to the pinned UxPlay API; no upstream implementation is
// copied into core/application modules. See docs/LICENSE_COMPLIANCE.md.
#include <jni.h>
#include "audio_output.h"
#include "playout_clock.h"
#include "h264_config.h"
#include "video_preroll.h"
#include <android/native_window_jni.h>
#include <android/log.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <atomic>
#include <chrono>
#include <cstring>
#include <cstdio>
#include <cmath>
#include <algorithm>
#include <mutex>
#include <string>
extern "C" {
#include "raop.h"
#include "dnssd.h"
}

namespace {
struct Receiver {
    JavaVM *vm = nullptr;
    jobject observer = nullptr;
    jmethodID notify = nullptr;
    jmethodID hlsCommand = nullptr, hlsInfo = nullptr, hlsVolume = nullptr;
    std::atomic<bool> replacing{false}, hlsActive{false}, hlsReleasePending{false};
    ANativeWindow *window = nullptr;
    raop_t *server = nullptr;
    dnssd_t *discovery = nullptr;
    AMediaCodec *decoder = nullptr;
    std::mutex video;
    std::unique_ptr<tvc::AudioOutput> audio;
    tvc::H264Config config;
    tvc::VideoPreroll preroll;
    unsigned short port = 0;
    bool advertised = false;
    unsigned width = 0, height = 0;
    bool receivedVideo = false;
    std::atomic<bool> closing{false};
    std::atomic<unsigned> connections{0};
    bool rendered = false;
    void event(int code) {
        if (closing) return;
        JNIEnv *env = nullptr;
        bool attach = vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED;
        if (attach && vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        env->CallVoidMethod(observer, notify, code);
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (attach) vm->DetachCurrentThread();
    }
    void hls(int operation, const char *location = nullptr, float value = 0) {
        if (closing) return;
        JNIEnv *env = nullptr;
        const bool attach = vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED;
        if (attach && vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        jstring url = location ? env->NewStringUTF(location) : nullptr;
        env->CallVoidMethod(observer, hlsCommand, operation, url, value, static_cast<jint>(port));
        if (url) env->DeleteLocalRef(url);
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (attach) vm->DetachCurrentThread();
    }
    void dropDecoder() {
        if (decoder) { AMediaCodec_stop(decoder); AMediaCodec_delete(decoder); decoder = nullptr; }
        rendered = false; receivedVideo = false;
    }
};
void noop(void *) {}
void connectionOpened(void *p) { ++static_cast<Receiver *>(p)->connections; }
void connectionClosed(void *p) {
    auto *r = static_cast<Receiver *>(p);
    if (r->connections.fetch_sub(1) == 1) { r->replacing = false; r->event(3); }
}
void connected(void *p) { static_cast<Receiver *>(p)->event(1); }
void disconnected(void *p) { static_cast<Receiver *>(p)->event(3); }
int64_t monotonicNs() { return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count(); }
void audio(void *p, raop_ntp_t *, audio_decode_struct *data) {
    auto *r = static_cast<Receiver *>(p);
    if (r->closing || r->replacing || r->hlsActive || data->data_len <= 0) return;
    r->audio->submit(data->ct, data->data, data->data_len, tvc::presentationTime(data->ntp_time_local, raop_ntp_get_local_time(), monotonicNs()));
}
void reset(void *p, reset_type_t reason) {
    auto *r = static_cast<Receiver *>(p);
    if (reason == RESET_TYPE_NOHOLD) {
        r->replacing = true;
        if (r->hlsActive.exchange(false)) r->hlsReleasePending = true;
        r->event(11); r->hls(3);
    } else if (reason == RESET_TYPE_HLS_SHUTDOWN || reason == RESET_TYPE_HLS_EOS) {
        r->hlsActive = false; r->hls(3); if (!r->replacing) r->event(3);
    }
    { std::lock_guard<std::mutex> lock(r->video); r->dropDecoder(); r->config.clear(); r->preroll.clear(); r->width = r->height = 0; }
    r->audio->end();
}
void connectionReset(void *, int) {}
void running(void *p, bool active) {
    auto *r = static_cast<Receiver *>(p);
    if (!active && (r->replacing || r->hlsActive)) return;
    if (!active) r->audio->end();
    else r->replacing = false;
    r->event(active ? 1 : 3);
}
void audioFormat(void *p, unsigned char *type, unsigned short *samples, bool *, bool *, uint64_t *) {
    auto *r = static_cast<Receiver *>(p);
    if (r->hlsActive) return;
    r->replacing = false;
    r->audio->configure(*type, *samples);
}
void audioFlush(void *p) { static_cast<Receiver *>(p)->audio->flush(); }
void audioEnd(void *p) { static_cast<Receiver *>(p)->audio->end(); }
void volume(void *p, float db) {
    auto *r = static_cast<Receiver *>(p); r->audio->volume(db);
    if (!r->hlsActive || r->closing) return;
    JNIEnv *env = nullptr; const bool attach = r->vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED;
    if (attach && r->vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
    const float gain = !std::isfinite(db) || db <= -144 ? 0 : std::clamp(std::pow(10.f, db / 20.f), 0.f, 1.f);
    env->CallVoidMethod(r->observer, r->hlsVolume, gain);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attach) r->vm->DetachCurrentThread();
}
double clientVolume(void *) { return 0.0; }
void metadata(void *, const void *, int) {}
void remote(void *, const char *, const char *) {}
void progress(void *, uint32_t *, uint32_t *, uint32_t *) {}
void report(void *, char *, char *model, char *, bool *admit) {
    const char *kind = "OTHER";
    if (model && std::strncmp(model, "Mac", 3) == 0) kind = "MAC";
    else if (model && std::strncmp(model, "iPhone", 6) == 0) kind = "IPHONE";
    else if (model && std::strncmp(model, "iPad", 4) == 0) kind = "IPAD";
    __android_log_print(ANDROID_LOG_INFO, "TVCAirPlay", "sender_kind=%s", kind);
    *admit = true;
}
void registerClient(void *, const char *, const char *, const char *) {}
bool checkClient(void *, const char *) { return false; }
void customProfile(void *, const char **profile) { *profile = "AVC:720,0;HEVC:0;VP9:0;AV1:0"; }
// Only fixed, allowlisted stage labels leave the upstream log callback.
// Never forward the original message, which may contain keys or media metadata.
void protocolStage(void *context, int, const char *message) {
    struct Stage { const char *prefix; const char *label; };
    static const Stage stages[] = {
        {"Unsupported m3u8 HLS streaming format", "UNSUPPORTED_APP_VIDEO"},
        {"only FairPlay version", "UNSUPPORTED_APP_VIDEO"},
        {"Handling request GET with URL /info", "INFO"},
        {"Handling request POST with URL /pair-setup", "PAIR_SETUP"},
        {"Handling request POST with URL /pair-verify", "PAIR_VERIFY"},
        {"Handling request POST with URL /fp-setup", "FP_SETUP"},
        {"Handling request SETUP", "SESSION_SETUP"},
        {"Handling request RECORD", "RECORD"},
        {"Handling request ANNOUNCE", "AUDIO_ANNOUNCE"},
        {"SETUP tries to setup stream of unknown type", "UNSUPPORTED_STREAM_TYPE"},
        {"RAOP not initialized at SETUP", "AUDIO_TRANSPORT_UNAVAILABLE"},
        {"Handling request TEARDOWN", "TEARDOWN"},
        {"Client specified timingProtocol=", "UNSUPPORTED_TIMING_PROTOCOL"},
        {"Client specified AirPlay2", "UNSUPPORTED_REMOTE_CONTROL"},
        {"rejecting new connection request", "BUSY_EXISTING_SENDER"},
        {"ignoring AirPlay video streaming request", "UNSUPPORTED_HLS_REQUEST"},
        {"raop_rtp_mirror accepting client", "VIDEO_SOCKET_CONNECTED"},
        {"failed to set video codec", "VIDEO_CONFIG_FAILED"},
        {"raop_rtp_mirror tcp socket was closed", "VIDEO_SOCKET_CLOSED"},
    };
    for (const auto &stage : stages) {
        if (std::strncmp(message, stage.prefix, std::strlen(stage.prefix)) == 0) {
            __android_log_print(ANDROID_LOG_INFO, "TVCAirPlay", "stage=%s", stage.label);
            if (context && std::strcmp(stage.label, "UNSUPPORTED_APP_VIDEO") == 0) static_cast<Receiver *>(context)->event(15);
            return;
        }
    }
}

bool configureVideo(Receiver *r) {
    r->dropDecoder();
    if (r->hlsActive || r->hlsReleasePending || !r->window || !r->width || !r->height || !r->config.complete()) return true;
    r->decoder = AMediaCodec_createDecoderByType("video/avc");
    if (!r->decoder) return false;
    AMediaFormat *format = AMediaFormat_new();
    AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, "video/avc");
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, r->width);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, r->height);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024);
    if (!r->config.sps.empty()) AMediaFormat_setBuffer(format, "csd-0", r->config.sps.data(), r->config.sps.size());
    if (!r->config.pps.empty()) AMediaFormat_setBuffer(format, "csd-1", r->config.pps.data(), r->config.pps.size());
    media_status_t result = AMediaCodec_configure(r->decoder, format, r->window, nullptr, 0);
    AMediaFormat_delete(format);
    if (result != AMEDIA_OK || AMediaCodec_start(r->decoder) != AMEDIA_OK) { r->dropDecoder(); return false; }
    __android_log_print(ANDROID_LOG_INFO, "TVCAirPlay", "stage=VIDEO_CONFIGURED");
    return true;
}
int codec(void *p, video_codec_t kind, unsigned short, unsigned short,
          unsigned short, unsigned short, unsigned short width, unsigned short height) {
    auto *r = static_cast<Receiver *>(p);
    std::lock_guard<std::mutex> lock(r->video);
    if (r->closing) return -1;
    if (kind != VIDEO_CODEC_H264 || width == 0 || height == 0 || width > 1920 || height > 1080) { r->event(4); return -1; }
    if (r->hlsActive.exchange(false)) { r->hlsReleasePending = true; r->hls(3, nullptr, 1); }
    r->replacing = false;
    r->width = width; r->height = height; r->config.clear(); r->preroll.clear();
    if (!configureVideo(r)) { r->event(4); return -1; }
    if (!r->window) { r->event(8); }
    return 0;
}
void feedVideo(Receiver *r, const uint8_t *data, size_t size, int64_t pts) {
    if (!r->decoder) return;
    // Match the upstream continuous-stream path: intra-refresh/non-IDR frames are valid input.
    if (!r->receivedVideo) { r->receivedVideo = true; __android_log_print(ANDROID_LOG_INFO, "TVCAirPlay", "stage=VIDEO_INPUT_RECEIVED"); }
    auto drain = [&]() {
        AMediaCodecBufferInfo info{};
        for (int n = 0; n < 16; n++) {
            ssize_t index = AMediaCodec_dequeueOutputBuffer(r->decoder, &info, 0);
            if (index < 0) break;
            const int64_t target = info.presentationTimeUs * 1000;
            if (tvc::expired(target, monotonicNs())) { AMediaCodec_releaseOutputBuffer(r->decoder, index, false); continue; }
            AMediaCodec_releaseOutputBufferAtTime(r->decoder, index, target);
            if (!r->rendered) { r->rendered = true; __android_log_print(ANDROID_LOG_INFO, "TVCAirPlay", "stage=FIRST_VIDEO_OUTPUT"); r->event(2); }
        }
    };
    drain();
    ssize_t index = AMediaCodec_dequeueInputBuffer(r->decoder, 10000);
    if (index >= 0) {
        size_t capacity = 0;
        uint8_t *input = AMediaCodec_getInputBuffer(r->decoder, index, &capacity);
        if (input && size <= capacity) {
            std::memcpy(input, data, size);
            AMediaCodec_queueInputBuffer(r->decoder, index, 0, size, pts, 0);
        } else {
            AMediaCodec_queueInputBuffer(r->decoder, index, 0, 0, 0, 0); r->event(4);
        }
    }
    drain();
}
void video(void *p, raop_ntp_t *, video_decode_struct *frame) {
    auto *r = static_cast<Receiver *>(p);
    std::lock_guard<std::mutex> lock(r->video);
    if (r->closing || r->replacing || r->hlsActive || frame->is_h265 || frame->data_len <= 0 || frame->data_len > 2 * 1024 * 1024) return;
    if (!r->config.complete() || !r->window) r->config.inspect(frame->data, frame->data_len);
    const auto pts = tvc::presentationTime(frame->ntp_time_local, raop_ntp_get_local_time(), monotonicNs()) / 1000;
    if (!r->window || r->hlsReleasePending) {
        if (r->config.complete()) r->preroll.append(frame->data, frame->data_len, pts);
        return;
    }
    if (!r->decoder && !configureVideo(r)) { r->event(4); return; }
    feedVideo(r, frame->data, frame->data_len, pts);
}
void hlsPlay(void *p, const char *location, float position) {
    auto *r = static_cast<Receiver *>(p);
    if (!location || strnlen(location, 8193) > 8192 || !std::isfinite(position) || position < 0 || position > 604800) { r->event(15); return; }
    r->hlsActive = true; r->replacing = false;
    { std::lock_guard<std::mutex> lock(r->video); r->dropDecoder(); r->preroll.clear(); }
    r->audio->end(); r->hls(0, location, position);
}
void hlsSeek(void *p, float position) { static_cast<Receiver *>(p)->hls(1, nullptr, position); }
void hlsRate(void *p, float rate) { static_cast<Receiver *>(p)->hls(2, nullptr, rate); }
void hlsStop(void *p) { auto *r = static_cast<Receiver *>(p); r->hlsActive = false; r->hls(3); }
void hlsPlaybackInfo(void *p, playback_info_t *info) {
    auto *r = static_cast<Receiver *>(p); *info = {};
    if (r->closing) { info->duration = info->position = -1; return; }
    JNIEnv *env = nullptr; const bool attach = r->vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED;
    if (attach && r->vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
    auto values = static_cast<jdoubleArray>(env->CallObjectMethod(r->observer, r->hlsInfo));
    if (!env->ExceptionCheck() && values && env->GetArrayLength(values) == 6) {
        jdouble data[6]{}; env->GetDoubleArrayRegion(values, 0, 6, data);
        if (data[5] == 1) info->duration = info->position = -1;
        else {
            info->duration = std::isfinite(data[0]) ? std::max(0., data[0]) : 0;
            info->position = std::isfinite(data[1]) ? std::max(0., data[1]) : 0;
            info->duration = std::max(info->duration, info->position);
            info->rate = data[2] == 1 ? 1 : 0;
            info->ready_to_play = data[3] == 1;
            info->playback_buffer_empty = data[4] == 1;
            info->playback_buffer_full = info->playback_likely_to_keep_up = info->ready_to_play && !info->playback_buffer_empty;
            info->seek_start = 0; info->seek_duration = info->duration;
        }
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (values) env->DeleteLocalRef(values);
    if (attach) r->vm->DetachCurrentThread();
}
float hlsPlaylistRemove(void *p) {
    playback_info_t info{}; hlsPlaybackInfo(p, &info); static_cast<Receiver *>(p)->hls(2, nullptr, 0);
    return static_cast<float>(std::max(0., info.position));
}
void destroy(JNIEnv *env, Receiver *r) {
    r->closing = true;
    if (r->discovery) { dnssd_unregister_airplay(r->discovery); dnssd_unregister_raop(r->discovery); }
    if (r->server) raop_destroy(r->server); // Joins receiver workers before releasing callback targets.
    if (r->discovery) dnssd_destroy(r->discovery);
    r->audio.reset();
    { std::lock_guard<std::mutex> lock(r->video); r->dropDecoder(); }
    if (r->window) ANativeWindow_release(r->window);
    if (r->observer) env->DeleteGlobalRef(r->observer);
    delete r;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_id_poncol_tvcompanion_uxplay_UxPlayReceiverEngine_nativeStart(JNIEnv *env, jobject self, jstring path, jstring identity, jstring receiverName) {
    auto *r = new Receiver();
    env->GetJavaVM(&r->vm);
    r->observer = env->NewGlobalRef(self);
    jclass type = env->GetObjectClass(self);
    r->notify = env->GetMethodID(type, "onNativeState", "(I)V");
    r->hlsCommand = env->GetMethodID(type, "onNativeHlsCommand", "(ILjava/lang/String;FI)V");
    r->hlsInfo = env->GetMethodID(type, "onNativeHlsInfo", "()[D");
    r->hlsVolume = env->GetMethodID(type, "onNativeHlsVolume", "(F)V"); env->DeleteLocalRef(type);
    if (!r->notify || !r->hlsCommand || !r->hlsInfo || !r->hlsVolume) { destroy(env, r); return 0; }
    r->audio.reset(new tvc::AudioOutput([r](int code) { r->event(code); }));
    const char *key = env->GetStringUTFChars(path, nullptr);
    const char *id = env->GetStringUTFChars(identity, nullptr);
    raop_callbacks_t callbacks{};
    callbacks.cls = r; callbacks.audio_process = audio; callbacks.video_process = video;
    callbacks.on_video_play = hlsPlay; callbacks.on_video_scrub = hlsSeek; callbacks.on_video_rate = hlsRate;
    callbacks.on_video_stop = hlsStop; callbacks.on_video_acquire_playback_info = hlsPlaybackInfo;
    callbacks.on_video_playlist_remove = hlsPlaylistRemove;
    callbacks.video_set_codec_and_size = codec;
    callbacks.conn_init = connectionOpened; callbacks.conn_destroy = connectionClosed;
    callbacks.conn_feedback = noop; callbacks.conn_reset = connectionReset;
    callbacks.video_reset = reset; callbacks.video_pause = noop; callbacks.video_resume = noop;
    callbacks.audio_flush = audioFlush; callbacks.video_flush = noop;
    callbacks.audio_set_client_volume = clientVolume; callbacks.audio_set_volume = volume;
    callbacks.audio_set_metadata = metadata; callbacks.audio_set_coverart = metadata;
    callbacks.audio_stop_coverart_rendering = audioEnd; callbacks.audio_remote_control_id = remote;
    callbacks.audio_set_progress = progress; callbacks.audio_get_format = audioFormat;
    callbacks.mirror_video_running = running; callbacks.report_client_request = report;
    callbacks.register_client = registerClient; callbacks.check_register = checkClient;
    callbacks.get_custom_profile = customProfile; callbacks.export_dacp = remote;
    ntp_global_init();
    r->server = raop_init(&callbacks);
    bool ok = r->server != nullptr;
    if (ok) {
        raop_set_log_callback(r->server, protocolStage, r);
        raop_set_log_level(r->server, LOGGER_INFO);
        ok = raop_init2(r->server, 1, id, key) == 0;
    }
    unsigned char hw[6]{};
    if (ok) {
        unsigned int parts[6]{};
        ok = std::sscanf(id, "%x:%x:%x:%x:%x:%x", &parts[0], &parts[1], &parts[2], &parts[3], &parts[4], &parts[5]) == 6;
        for (int i=0; i<6; ++i) hw[i] = static_cast<unsigned char>(parts[i]);
    }
    env->ReleaseStringUTFChars(path, key); env->ReleaseStringUTFChars(identity, id);
    int error = 0;
    if (ok) { const char *name = env->GetStringUTFChars(receiverName, nullptr); r->discovery = dnssd_init(name, std::strlen(name), reinterpret_cast<const char *>(hw), 6, 0, &error); ok = r->discovery && !error; env->ReleaseStringUTFChars(receiverName, name); }
    if (ok) {
        // Preserve upstream's discovery/pairing profile, including audio negotiation.
        // Audio decoding/output remains local to this receiver implementation.
        for (int bit : {0, 4}) dnssd_set_airplay_features(r->discovery, bit, 1);
        dnssd_set_airplay_features(r->discovery, 42, 0);
        raop_set_plist(r->server, "hls", 1);
        raop_set_lang(r->server, "", "", "en");
        raop_set_dnssd(r->server, r->discovery);
        raop_set_plist(r->server, "width", 1280); raop_set_plist(r->server, "height", 720);
        raop_set_plist(r->server, "refreshRate", 30); raop_set_plist(r->server, "maxFPS", 30);
        unsigned short port = 0;
        ok = raop_start_httpd(r->server, &port) > 0 && port != 0;
        if (ok) { raop_set_port(r->server, port); r->port = port; ok = dnssd_register_raop(r->discovery, port) == 0 && dnssd_register_airplay(r->discovery, port) == 0; r->advertised = ok; }
    }
    if (!ok) { destroy(env, r); return 0; }
    return reinterpret_cast<jlong>(r);
}
extern "C" JNIEXPORT void JNICALL
Java_id_poncol_tvcompanion_uxplay_UxPlayReceiverEngine_nativeStop(JNIEnv *env, jobject, jlong handle) {
    if (handle) destroy(env, reinterpret_cast<Receiver *>(handle));
}

extern "C" JNIEXPORT void JNICALL
Java_id_poncol_tvcompanion_uxplay_UxPlayReceiverEngine_nativeSetSurface(JNIEnv *env, jobject, jlong handle, jobject surface) {
    auto *r = reinterpret_cast<Receiver *>(handle); if (!r) return;
    ANativeWindow *window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    std::lock_guard<std::mutex> lock(r->video);
    if (window == r->window) { if (window) ANativeWindow_release(window); return; }
    r->dropDecoder(); if (r->window) ANativeWindow_release(r->window); r->window = window;
    if (r->hlsActive) { r->preroll.clear(); return; }
    if (!configureVideo(r)) { r->event(4); return; }
    if (window && r->decoder) {
        // Decode startup references in order. Stale output is discarded by the normal clock policy.
        for (const auto &packet : r->preroll.packets) feedVideo(r, packet.bytes.data(), packet.bytes.size(), packet.pts);
        __android_log_print(ANDROID_LOG_INFO, "TVCAirPlay", "stage=VIDEO_SURFACE_ATTACHED");
    }
    r->preroll.clear();
}
extern "C" JNIEXPORT void JNICALL
Java_id_poncol_tvcompanion_uxplay_UxPlayReceiverEngine_nativeSetFocus(JNIEnv *, jobject, jlong handle, jboolean allowed, jfloat duck) {
    auto *r = reinterpret_cast<Receiver *>(handle); if (r && r->audio) r->audio->focus(allowed, duck);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_id_poncol_tvcompanion_uxplay_UxPlayReceiverEngine_nativeSetDiscoverable(JNIEnv *, jobject, jlong handle, jboolean enabled) {
    auto *r = reinterpret_cast<Receiver *>(handle);
    if (!r || r->closing || !r->discovery) return JNI_FALSE;
    if (r->advertised == static_cast<bool>(enabled)) return JNI_TRUE;
    if (!enabled) {
        dnssd_unregister_airplay(r->discovery); dnssd_unregister_raop(r->discovery);
        r->advertised = false; return JNI_TRUE;
    }
    const bool ok = dnssd_register_raop(r->discovery, r->port) == 0 && dnssd_register_airplay(r->discovery, r->port) == 0;
    if (!ok) { dnssd_unregister_airplay(r->discovery); dnssd_unregister_raop(r->discovery); }
    r->advertised = ok; return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_id_poncol_tvcompanion_uxplay_UxPlayReceiverEngine_nativeHlsEnded(JNIEnv *, jobject, jlong handle) {
    auto *r = reinterpret_cast<Receiver *>(handle);
    if (r && !r->closing && r->hlsActive.exchange(false)) raop_handle_eos(r->server);
}

extern "C" JNIEXPORT void JNICALL
Java_id_poncol_tvcompanion_uxplay_UxPlayReceiverEngine_nativeHlsReleased(JNIEnv *, jobject, jlong handle) {
    auto *r = reinterpret_cast<Receiver *>(handle);
    if (!r || r->closing) return;
    std::lock_guard<std::mutex> lock(r->video);
    if (!r->hlsReleasePending.exchange(false) || r->hlsActive) return;
    if (!configureVideo(r)) { r->event(4); return; }
    if (r->decoder) for (const auto &packet : r->preroll.packets) feedVideo(r, packet.bytes.data(), packet.bytes.size(), packet.pts);
    r->preroll.clear();
}
