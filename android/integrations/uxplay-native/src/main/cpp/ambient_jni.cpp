#include <jni.h>
#include <ctime>
#include <memory>
#ifndef NDEBUG
#include <atomic>
#include <android/log.h>
#endif
#include "receiver_ambient.h"

using Sub = std::shared_ptr<tvc::AmbientSource>;

extern "C" JNIEXPORT jlong JNICALL
Java_id_poncol_tvcompanion_uxplay_UxPlayAmbientSubscription_nativeOpen(JNIEnv *, jclass, jlong receiver) {
    Sub source = tvc::receiverAmbient(receiver);
    if (!source) return 0;
    try {
        if (!source->subscribe()) return 0;
        return reinterpret_cast<jlong>(new Sub(std::move(source)));
    } catch (...) {
        return 0;
    }
}
extern "C" JNIEXPORT jint JNICALL
Java_id_poncol_tvcompanion_uxplay_UxPlayAmbientSubscription_nativeRead(JNIEnv *env, jclass, jlong sub, jfloatArray out) {
    auto *source = reinterpret_cast<Sub *>(sub);
    if (!source || !out || env->GetArrayLength(out) < 25) return 2;
    float frame[25]{};
    timespec now{}; clock_gettime(CLOCK_MONOTONIC, &now);   // same clock as steady_clock and AAudio timestamps
    const int64_t nowNs = int64_t(now.tv_sec) * 1000000000 + now.tv_nsec;
    const int status = (*source)->read(frame, nowNs);
#ifndef NDEBUG
    static std::atomic<int64_t> lastLogNs{0};
    static std::atomic<uint32_t> lastAnalyses{0};
    int64_t last = lastLogNs.load();
    if (nowNs - last >= 10000000000LL && lastLogNs.compare_exchange_strong(last, nowNs)) {
        const uint32_t analyses = (*source)->analyses();
        const uint32_t delta = analyses - lastAnalyses.exchange(analyses);
        if (last != 0)
            __android_log_print(ANDROID_LOG_DEBUG, "TVCAmbient", "tap dropped=%llu analyses/s=%.1f",
                                (unsigned long long)(*source)->tap().dropped(), delta * 1e9 / double(nowNs - last));
    }
#endif
    env->SetFloatArrayRegion(out, 0, 25, frame);
    return status;
}
extern "C" JNIEXPORT void JNICALL
Java_id_poncol_tvcompanion_uxplay_UxPlayAmbientSubscription_nativeClose(JNIEnv *, jclass, jlong sub) {
    auto *source = reinterpret_cast<Sub *>(sub);
    if (!source) return;
    (*source)->unsubscribe();
    delete source;
}
