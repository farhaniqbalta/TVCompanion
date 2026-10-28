// Development-only adapter to the pinned UxPlay API; no upstream implementation is
// copied into core/application modules. See docs/LICENSE_COMPLIANCE.md.
#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <atomic>
#include <chrono>
#include <cstring>
#include <cstdio>
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
    ANativeWindow *window = nullptr;
    raop_t *server = nullptr;
    dnssd_t *discovery = nullptr;
    AMediaCodec *decoder = nullptr;
    std::mutex video;
    std::atomic<bool> closing{false};
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
    void dropDecoder() {
        if (decoder) { AMediaCodec_stop(decoder); AMediaCodec_delete(decoder); decoder = nullptr; }
        rendered = false;
    }
};
void noop(void *) {}
void connected(void *p) { static_cast<Receiver *>(p)->event(1); }
void disconnected(void *p) { static_cast<Receiver *>(p)->event(3); }
void audio(void *, raop_ntp_t *, audio_decode_struct *) {} // Explicit video-only experiment.
void reset(void *, reset_type_t) {}
void connectionReset(void *, int) {}
void running(void *p, bool active) { static_cast<Receiver *>(p)->event(active ? 1 : 3); }
void audioFormat(void *, unsigned char *, unsigned short *, bool *, bool *, uint64_t *) {}
void volume(void *, float) {}
double clientVolume(void *) { return 0.0; }
void metadata(void *, const void *, int) {}
void remote(void *, const char *, const char *) {}
void progress(void *, uint32_t *, uint32_t *, uint32_t *) {}
void report(void *, char *, char *model, char *, bool *admit) {
    const char *kind = "OTHER";
    if (model && std::strncmp(model, "Mac", 3) == 0) kind = "MAC";
    else if (model && std::strncmp(model, "iPhone", 6) == 0) kind = "IPHONE";
    else if (model && std::strncmp(model, "iPad", 4) == 0) kind = "IPAD";
    __android_log_print(ANDROID_LOG_INFO, "TVCAirPlayLab", "sender_kind=%s", kind);
    *admit = true;
}
void registerClient(void *, const char *, const char *, const char *) {}
bool checkClient(void *, const char *) { return false; }
void customProfile(void *, const char **profile) { *profile = nullptr; }
// Only fixed, allowlisted stage labels leave the upstream log callback.
// Never forward the original message, which may contain keys or media metadata.
void protocolStage(void *, int, const char *message) {
    struct Stage { const char *prefix; const char *label; };
    static const Stage stages[] = {
        {"Handling request GET with URL /info", "INFO"},
        {"Handling request POST with URL /pair-setup", "PAIR_SETUP"},
        {"Handling request POST with URL /pair-verify", "PAIR_VERIFY"},
        {"Handling request POST with URL /fp-setup", "FP_SETUP"},
        {"Handling request SETUP", "SESSION_SETUP"},
        {"Handling request RECORD", "RECORD"},
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
            __android_log_print(ANDROID_LOG_INFO, "TVCAirPlayLab", "stage=%s", stage.label); return;
        }
    }
}

int codec(void *p, video_codec_t kind, unsigned short, unsigned short,
          unsigned short, unsigned short, unsigned short width, unsigned short height) {
    auto *r = static_cast<Receiver *>(p);
    std::lock_guard<std::mutex> lock(r->video);
    if (r->closing) return -1;
    r->dropDecoder();
    __android_log_print(ANDROID_LOG_INFO, "TVCAirPlayLab", "codec=%d width=%u height=%u", kind, width, height);
    if (kind != VIDEO_CODEC_H264 || width == 0 || height == 0 || width > 1920 || height > 1080) {
        r->event(4); return -1;
    }
    r->decoder = AMediaCodec_createDecoderByType("video/avc");
    if (!r->decoder) { r->event(4); return -1; }
    AMediaFormat *format = AMediaFormat_new();
    AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, "video/avc");
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, width);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, height);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024);
    media_status_t result = AMediaCodec_configure(r->decoder, format, r->window, nullptr, 0);
    AMediaFormat_delete(format);
    __android_log_print(ANDROID_LOG_INFO, "TVCAirPlayLab", "configure_result=%d", result);
    if (result != AMEDIA_OK || AMediaCodec_start(r->decoder) != AMEDIA_OK) {
        r->dropDecoder(); r->event(4); return -1;
    }
    return 0;
}
void video(void *p, raop_ntp_t *, video_decode_struct *frame) {
    auto *r = static_cast<Receiver *>(p);
    std::lock_guard<std::mutex> lock(r->video);
    if (r->closing || !r->decoder || frame->is_h265 || frame->data_len <= 0 || frame->data_len > 2 * 1024 * 1024) return;
    auto drain = [&]() {
        AMediaCodecBufferInfo info{};
        for (int n = 0; n < 16; n++) {
            ssize_t index = AMediaCodec_dequeueOutputBuffer(r->decoder, &info, 0);
            if (index < 0) break;
            AMediaCodec_releaseOutputBuffer(r->decoder, index, true);
            if (!r->rendered) { r->rendered = true; __android_log_print(ANDROID_LOG_INFO, "TVCAirPlayLab", "stage=FIRST_VIDEO_OUTPUT"); r->event(2); }
        }
    };
    drain();
    ssize_t index = AMediaCodec_dequeueInputBuffer(r->decoder, 10000);
    if (index >= 0) {
        size_t capacity = 0;
        uint8_t *input = AMediaCodec_getInputBuffer(r->decoder, index, &capacity);
        if (input && static_cast<size_t>(frame->data_len) <= capacity) {
            std::memcpy(input, frame->data, frame->data_len);
            auto pts = std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
            AMediaCodec_queueInputBuffer(r->decoder, index, 0, frame->data_len, pts, 0);
        } else {
            AMediaCodec_queueInputBuffer(r->decoder, index, 0, 0, 0, 0); r->event(4);
        }
    }
    drain();
}
void destroy(JNIEnv *env, Receiver *r) {
    r->closing = true;
    if (r->discovery) { dnssd_unregister_airplay(r->discovery); dnssd_unregister_raop(r->discovery); }
    if (r->server) raop_destroy(r->server); // Joins receiver workers before releasing callback targets.
    if (r->discovery) dnssd_destroy(r->discovery);
    { std::lock_guard<std::mutex> lock(r->video); r->dropDecoder(); }
    if (r->window) ANativeWindow_release(r->window);
    if (r->observer) env->DeleteGlobalRef(r->observer);
    delete r;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_id_poncol_tvcompanion_airplaylab_UxPlayEngine_nativeStart(JNIEnv *env, jobject self, jobject surface, jstring path, jstring identity) {
    auto *r = new Receiver();
    env->GetJavaVM(&r->vm);
    r->observer = env->NewGlobalRef(self);
    jclass type = env->GetObjectClass(self);
    r->notify = env->GetMethodID(type, "onNativeState", "(I)V"); env->DeleteLocalRef(type);
    r->window = ANativeWindow_fromSurface(env, surface);
    if (!r->notify || !r->window) { destroy(env, r); return 0; }
    const char *key = env->GetStringUTFChars(path, nullptr);
    const char *id = env->GetStringUTFChars(identity, nullptr);
    raop_callbacks_t callbacks{};
    callbacks.cls = r; callbacks.audio_process = audio; callbacks.video_process = video;
    callbacks.video_set_codec_and_size = codec;
    callbacks.conn_init = noop; callbacks.conn_destroy = noop;
    callbacks.conn_feedback = noop; callbacks.conn_reset = connectionReset;
    callbacks.video_reset = reset; callbacks.video_pause = noop; callbacks.video_resume = noop;
    callbacks.audio_flush = noop; callbacks.video_flush = noop;
    callbacks.audio_set_client_volume = clientVolume; callbacks.audio_set_volume = volume;
    callbacks.audio_set_metadata = metadata; callbacks.audio_set_coverart = metadata;
    callbacks.audio_stop_coverart_rendering = noop; callbacks.audio_remote_control_id = remote;
    callbacks.audio_set_progress = progress; callbacks.audio_get_format = audioFormat;
    callbacks.mirror_video_running = running; callbacks.report_client_request = report;
    callbacks.register_client = registerClient; callbacks.check_register = checkClient;
    callbacks.get_custom_profile = customProfile; callbacks.export_dacp = remote;
    ntp_global_init();
    r->server = raop_init(&callbacks);
    bool ok = r->server != nullptr;
    if (ok) {
        raop_set_log_callback(r->server, protocolStage, nullptr);
        raop_set_log_level(r->server, LOGGER_INFO);
        ok = raop_init2(r->server, 0, id, key) == 0;
    }
    unsigned char hw[6]{};
    if (ok) {
        unsigned int parts[6]{};
        ok = std::sscanf(id, "%x:%x:%x:%x:%x:%x", &parts[0], &parts[1], &parts[2], &parts[3], &parts[4], &parts[5]) == 6;
        for (int i=0; i<6; ++i) hw[i] = static_cast<unsigned char>(parts[i]);
    }
    env->ReleaseStringUTFChars(path, key); env->ReleaseStringUTFChars(identity, id);
    int error = 0;
    if (ok) { r->discovery = dnssd_init("TVCompanion Lab", 15, reinterpret_cast<const char *>(hw), 6, 0, &error); ok = r->discovery && !error; }
    if (ok) {
        // Preserve upstream's discovery/pairing profile, including audio negotiation.
        // The experiment accepts but discards audio: sound output remains unimplemented.
        for (int bit : {0, 4, 42}) dnssd_set_airplay_features(r->discovery, bit, 0);
        raop_set_dnssd(r->server, r->discovery);
        raop_set_plist(r->server, "width", 1280); raop_set_plist(r->server, "height", 720);
        raop_set_plist(r->server, "refreshRate", 30); raop_set_plist(r->server, "maxFPS", 30);
        unsigned short port = 0;
        ok = raop_start_httpd(r->server, &port) > 0 && port != 0;
        if (ok) { raop_set_port(r->server, port); ok = dnssd_register_raop(r->discovery, port) == 0 && dnssd_register_airplay(r->discovery, port) == 0; }
    }
    if (!ok) { destroy(env, r); return 0; }
    return reinterpret_cast<jlong>(r);
}
extern "C" JNIEXPORT void JNICALL
Java_id_poncol_tvcompanion_airplaylab_UxPlayEngine_nativeStop(JNIEnv *env, jobject, jlong handle) {
    if (handle) destroy(env, reinterpret_cast<Receiver *>(handle));
}
