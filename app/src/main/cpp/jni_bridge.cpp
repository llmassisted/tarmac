// Tarmac native entry points. Bodies are Phase-1 stubs; Phase 2 wires
// them to UxPlay's RAOP/RTSP server under native/libairplay/lib.

#include <jni.h>
#include <android/log.h>
#include <string>

#define LOG_TAG "tarmac-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jint JNICALL
Java_com_tarmac_service_AirPlayJni_startServer(
        JNIEnv* env, jobject /* thiz */,
        jstring deviceName, jlong features, jint pin) {
    const char* name = env->GetStringUTFChars(deviceName, nullptr);
    LOGI("startServer(name=%s, features=0x%llx, pin=%d) — stub", name,
         (long long)features, pin);
    env->ReleaseStringUTFChars(deviceName, name);
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_tarmac_service_AirPlayJni_stopServer(JNIEnv* /* env */, jobject /* thiz */) {
    LOGI("stopServer — stub");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tarmac_service_AirPlayJni_nativeVersion(JNIEnv* env, jobject /* thiz */) {
    return env->NewStringUTF("tarmac-native 0.1.0 (phase 1 stub)");
}
