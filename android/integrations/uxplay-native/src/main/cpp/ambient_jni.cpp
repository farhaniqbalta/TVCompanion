#include <jni.h>
#include <ctime>
#include <memory>
#include "receiver_ambient.h"

using Sub = std::shared_ptr<tvc::AmbientSource>;

extern "C" JNIEXPORT jlong JNICALL
Java_id_poncol_tvcompanion_uxplay_UxPlayAmbientSubscription_nativeOpen(JNIEnv *, jclass, jlong receiver) {
    Sub source = tvc::receiverAmbient(receiver);
    if (!source || !source->subscribe()) return 0;
    return reinterpret_cast<jlong>(new Sub(std::move(source)));
}
extern "C" JNIEXPORT jint JNICALL
Java_id_poncol_tvcompanion_uxplay_UxPlayAmbientSubscription_nativeRead(JNIEnv *env, jclass, jlong sub, jfloatArray out) {
    auto *source = reinterpret_cast<Sub *>(sub);
    if (!source || !out || env->GetArrayLength(out) < 25) return 2;
    float frame[25]{};
    timespec now{}; clock_gettime(CLOCK_MONOTONIC, &now);   // same clock as steady_clock and AAudio timestamps
    const int status = (*source)->read(frame, int64_t(now.tv_sec) * 1000000000 + now.tv_nsec);
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
