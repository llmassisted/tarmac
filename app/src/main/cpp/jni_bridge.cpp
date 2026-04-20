// Tarmac native entry points.
//
// When TARMAC_LINK_LIBAIRPLAY is ON, this drives UxPlay's RAOP/RTSP server
// (native/libairplay/lib/raop.h). When OFF (the current default — see CMake
// note about libplist), the JNI methods are stubs that just log.
//
// The Java side (com.tarmac.service.AirPlayJni) owns:
//   - the deviceName / features / pin lifecycle
//   - the Bonjour advertisement (NsdManager)
//   - the MediaCodec + AudioTrack pipelines (called back via JNI callbacks)
//
// This file is intentionally small: most decoding and rendering logic lives in
// android_video_renderer.cpp / android_audio_renderer.cpp.

#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>

#define LOG_TAG "tarmac-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifdef HAVE_LIBAIRPLAY
extern "C" {
#include "raop.h"
#include "stream.h"
#include "dnssd.h"
}
#endif

namespace {

// JavaVM cached at JNI_OnLoad so native callbacks can reach back into Kotlin.
JavaVM* g_vm = nullptr;
// Global ref to the Java-side AirPlayJni singleton object (target of callbacks).
jobject g_callback_obj = nullptr;
// Pre-resolved Java method IDs for the most-used callbacks. Kept null when
// libairplay is off.
jmethodID g_mid_on_video_data = nullptr;     // (Ljava/nio/ByteBuffer;IZJ)V
jmethodID g_mid_on_audio_data = nullptr;     // (Ljava/nio/ByteBuffer;IIJ)V
jmethodID g_mid_on_pin_display = nullptr;    // (Ljava/lang/String;)V
jmethodID g_mid_on_session_state = nullptr;  // (I)V
jmethodID g_mid_on_video_play = nullptr;     // (Ljava/lang/String;F)V
jmethodID g_mid_on_video_stop = nullptr;     // ()V
jmethodID g_mid_on_video_rate = nullptr;     // (F)V
jmethodID g_mid_on_video_scrub = nullptr;    // (F)V

#ifdef HAVE_LIBAIRPLAY
std::mutex g_raop_mutex;
raop_t* g_raop = nullptr;
dnssd_t* g_dnssd = nullptr;
#endif

JNIEnv* attach_env(bool* should_detach) {
    JNIEnv* env = nullptr;
    *should_detach = false;
    if (!g_vm) return nullptr;
    int rc = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (rc == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            *should_detach = true;
        } else {
            return nullptr;
        }
    } else if (rc != JNI_OK) {
        return nullptr;
    }
    return env;
}

#ifdef HAVE_LIBAIRPLAY
// --- UxPlay-style callbacks. Each forwards into the Java AirPlayJni object. -

void cb_video_process(void* /*cls*/, raop_ntp_t* /*ntp*/, video_decode_struct* data) {
    if (!data || !data->data || data->data_len <= 0) return;
    bool detach = false;
    JNIEnv* env = attach_env(&detach);
    if (!env || !g_callback_obj || !g_mid_on_video_data) return;
    jobject buf = env->NewDirectByteBuffer(data->data, data->data_len);
    env->CallVoidMethod(g_callback_obj, g_mid_on_video_data,
                        buf, data->data_len, (jboolean) data->is_h265,
                        (jlong) data->ntp_time_local);
    env->DeleteLocalRef(buf);
    if (detach) g_vm->DetachCurrentThread();
}

void cb_audio_process(void* /*cls*/, raop_ntp_t* /*ntp*/, audio_decode_struct* data) {
    if (!data || !data->data || data->data_len <= 0) return;
    bool detach = false;
    JNIEnv* env = attach_env(&detach);
    if (!env || !g_callback_obj || !g_mid_on_audio_data) return;
    jobject buf = env->NewDirectByteBuffer(data->data, data->data_len);
    env->CallVoidMethod(g_callback_obj, g_mid_on_audio_data,
                        buf, data->data_len, (jint) data->ct,
                        (jlong) data->ntp_time_local);
    env->DeleteLocalRef(buf);
    if (detach) g_vm->DetachCurrentThread();
}

void cb_display_pin(void* /*cls*/, char* pin) {
    if (!pin) return;
    bool detach = false;
    JNIEnv* env = attach_env(&detach);
    if (!env || !g_callback_obj || !g_mid_on_pin_display) return;
    jstring s = env->NewStringUTF(pin);
    env->CallVoidMethod(g_callback_obj, g_mid_on_pin_display, s);
    env->DeleteLocalRef(s);
    if (detach) g_vm->DetachCurrentThread();
}

void notify_session_state(int state) {
    bool detach = false;
    JNIEnv* env = attach_env(&detach);
    if (!env || !g_callback_obj || !g_mid_on_session_state) return;
    env->CallVoidMethod(g_callback_obj, g_mid_on_session_state, (jint) state);
    if (detach) g_vm->DetachCurrentThread();
}

void cb_conn_init(void* /*cls*/)    { notify_session_state(1); }
void cb_conn_destroy(void* /*cls*/) { notify_session_state(0); }
void cb_conn_reset(void* /*cls*/, int /*reason*/) { notify_session_state(0); }

void cb_video_play(void* /*cls*/, const char* location, const float start_position) {
    if (!location) return;
    bool detach = false;
    JNIEnv* env = attach_env(&detach);
    if (!env || !g_callback_obj || !g_mid_on_video_play) return;
    jstring s = env->NewStringUTF(location);
    env->CallVoidMethod(g_callback_obj, g_mid_on_video_play, s, (jfloat) start_position);
    env->DeleteLocalRef(s);
    if (detach) g_vm->DetachCurrentThread();
}

void cb_video_stop(void* /*cls*/) {
    bool detach = false;
    JNIEnv* env = attach_env(&detach);
    if (!env || !g_callback_obj || !g_mid_on_video_stop) return;
    env->CallVoidMethod(g_callback_obj, g_mid_on_video_stop);
    if (detach) g_vm->DetachCurrentThread();
}

void cb_video_rate(void* /*cls*/, const float rate) {
    bool detach = false;
    JNIEnv* env = attach_env(&detach);
    if (!env || !g_callback_obj || !g_mid_on_video_rate) return;
    env->CallVoidMethod(g_callback_obj, g_mid_on_video_rate, (jfloat) rate);
    if (detach) g_vm->DetachCurrentThread();
}

void cb_video_scrub(void* /*cls*/, const float position) {
    bool detach = false;
    JNIEnv* env = attach_env(&detach);
    if (!env || !g_callback_obj || !g_mid_on_video_scrub) return;
    env->CallVoidMethod(g_callback_obj, g_mid_on_video_scrub, (jfloat) position);
    if (detach) g_vm->DetachCurrentThread();
}

void cb_log(void* /*cls*/, int level, const char* msg) {
    if (!msg) return;
    int prio = ANDROID_LOG_INFO;
    if (level <= 0) prio = ANDROID_LOG_ERROR;
    else if (level == 1) prio = ANDROID_LOG_WARN;
    else if (level == 2) prio = ANDROID_LOG_INFO;
    else prio = ANDROID_LOG_DEBUG;
    __android_log_print(prio, "tarmac-airplay", "%s", msg);
}
#endif  // HAVE_LIBAIRPLAY

void resolve_callback_methods(JNIEnv* env, jobject obj) {
    jclass cls = env->GetObjectClass(obj);
    g_mid_on_video_data    = env->GetMethodID(cls, "onVideoData",    "(Ljava/nio/ByteBuffer;IZJ)V");
    g_mid_on_audio_data    = env->GetMethodID(cls, "onAudioData",    "(Ljava/nio/ByteBuffer;IIJ)V");
    g_mid_on_pin_display   = env->GetMethodID(cls, "onPinDisplay",   "(Ljava/lang/String;)V");
    g_mid_on_session_state = env->GetMethodID(cls, "onSessionState", "(I)V");
    g_mid_on_video_play    = env->GetMethodID(cls, "onVideoPlay",    "(Ljava/lang/String;F)V");
    g_mid_on_video_stop    = env->GetMethodID(cls, "onVideoStop",    "()V");
    g_mid_on_video_rate    = env->GetMethodID(cls, "onVideoRate",    "(F)V");
    g_mid_on_video_scrub   = env->GetMethodID(cls, "onVideoScrub",   "(F)V");
    env->DeleteLocalRef(cls);
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_tarmac_service_AirPlayJni_startServer(
        JNIEnv* env, jobject thiz,
        jstring deviceName, jbyteArray hwAddr, jlong features, jint pin) {
    const char* name = env->GetStringUTFChars(deviceName, nullptr);
    jsize hwLen = env->GetArrayLength(hwAddr);
    jbyte* hwBytes = env->GetByteArrayElements(hwAddr, nullptr);
    LOGI("startServer(name=%s, hwLen=%d, features=0x%llx, pin=%d)",
         name ? name : "(null)", (int) hwLen, (long long) features, pin);

    if (g_callback_obj) {
        env->DeleteGlobalRef(g_callback_obj);
        g_callback_obj = nullptr;
    }
    g_callback_obj = env->NewGlobalRef(thiz);
    resolve_callback_methods(env, thiz);

#ifdef HAVE_LIBAIRPLAY
    std::lock_guard<std::mutex> lock(g_raop_mutex);
    if (g_raop) {
        env->ReleaseByteArrayElements(hwAddr, hwBytes, JNI_ABORT);
        env->ReleaseStringUTFChars(deviceName, name);
        LOGW("startServer: already running");
        return 0;
    }
    raop_callbacks_t cbs;
    memset(&cbs, 0, sizeof(cbs));
    cbs.audio_process  = cb_audio_process;
    cbs.video_process  = cb_video_process;
    cbs.conn_init      = cb_conn_init;
    cbs.conn_destroy   = cb_conn_destroy;
    cbs.conn_reset     = cb_conn_reset;
    cbs.display_pin    = cb_display_pin;
    cbs.on_video_play  = cb_video_play;
    cbs.on_video_stop  = cb_video_stop;
    cbs.on_video_rate  = cb_video_rate;
    cbs.on_video_scrub = cb_video_scrub;

    g_raop = raop_init(&cbs);
    if (!g_raop) {
        LOGE("raop_init failed");
        env->ReleaseByteArrayElements(hwAddr, hwBytes, JNI_ABORT);
        env->ReleaseStringUTFChars(deviceName, name);
        return -1;
    }
    raop_set_log_callback(g_raop, cb_log, nullptr);
    raop_set_log_level(g_raop, 3);

    // libairplay's raop_init2 takes a colon-formatted MAC string; build it
    // from the 6 hwAddr bytes the Java service handed us.
    char mac[18] = {0};
    if (hwLen == 6) {
        snprintf(mac, sizeof(mac), "%02X:%02X:%02X:%02X:%02X:%02X",
                 (unsigned char) hwBytes[0], (unsigned char) hwBytes[1],
                 (unsigned char) hwBytes[2], (unsigned char) hwBytes[3],
                 (unsigned char) hwBytes[4], (unsigned char) hwBytes[5]);
    } else {
        snprintf(mac, sizeof(mac), "02:00:00:00:00:00");
    }
    if (raop_init2(g_raop, /*nohold*/1, mac, /*keyfile*/"")) {
        LOGE("raop_init2 failed");
        raop_destroy(g_raop);
        g_raop = nullptr;
        env->ReleaseByteArrayElements(hwAddr, hwBytes, JNI_ABORT);
        env->ReleaseStringUTFChars(deviceName, name);
        return -2;
    }
    if (pin > 0) raop_set_plist(g_raop, "pin", (int) pin);
    raop_set_plist(g_raop, "width",  1920);
    raop_set_plist(g_raop, "height", 1080);
    raop_set_plist(g_raop, "refreshRate", 60);
    raop_set_plist(g_raop, "maxFPS", 60);

    // raop_handlers.h does `assert(raop->dnssd)` inside the /info handler, so
    // we have to hand raop a dnssd_t even though the real Bonjour record is
    // advertised on the Java side via NsdManager. The stub dnssd_stub.c
    // builds the TXT bytes raop will return inside plist replies.
    const int name_len = name ? (int) strlen(name) : 0;
    int dnssd_err = 0;
    g_dnssd = dnssd_init(name ? name : "Tarmac", name_len,
                         (hwLen == 6) ? (const char*) hwBytes : "\x02\x00\x00\x00\x00\x00",
                         6, &dnssd_err, /*pin_pw*/ pin > 0 ? 1 : 0);
    if (!g_dnssd) {
        LOGE("dnssd_init failed (err=%d)", dnssd_err);
        raop_destroy(g_raop);
        g_raop = nullptr;
        env->ReleaseByteArrayElements(hwAddr, hwBytes, JNI_ABORT);
        env->ReleaseStringUTFChars(deviceName, name);
        return -3;
    }
    raop_set_dnssd(g_raop, g_dnssd);

    unsigned short port = 0;
    raop_start_httpd(g_raop, &port);
    raop_set_port(g_raop, port);
    LOGI("raop server listening on port %u", (unsigned) port);

    env->ReleaseByteArrayElements(hwAddr, hwBytes, JNI_ABORT);
    env->ReleaseStringUTFChars(deviceName, name);
    return (jint) port;
#else
    (void) hwBytes;
    env->ReleaseByteArrayElements(hwAddr, hwBytes, JNI_ABORT);
    env->ReleaseStringUTFChars(deviceName, name);
    LOGW("startServer: libairplay not linked (TARMAC_LINK_LIBAIRPLAY=OFF). "
         "Returning stub success.");
    return 0;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_tarmac_service_AirPlayJni_stopServer(JNIEnv* env, jobject /*thiz*/) {
    LOGI("stopServer");
#ifdef HAVE_LIBAIRPLAY
    {
        std::lock_guard<std::mutex> lock(g_raop_mutex);
        if (g_raop) {
            raop_stop_httpd(g_raop);
            raop_destroy(g_raop);
            g_raop = nullptr;
        }
        if (g_dnssd) {
            dnssd_destroy(g_dnssd);
            g_dnssd = nullptr;
        }
    }
#endif
    if (g_callback_obj) {
        env->DeleteGlobalRef(g_callback_obj);
        g_callback_obj = nullptr;
    }
    g_mid_on_video_data    = nullptr;
    g_mid_on_audio_data    = nullptr;
    g_mid_on_pin_display   = nullptr;
    g_mid_on_session_state = nullptr;
    g_mid_on_video_play    = nullptr;
    g_mid_on_video_stop    = nullptr;
    g_mid_on_video_rate    = nullptr;
    g_mid_on_video_scrub   = nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tarmac_service_AirPlayJni_nativeVersion(JNIEnv* env, jobject /*thiz*/) {
#ifdef HAVE_LIBAIRPLAY
    return env->NewStringUTF("tarmac-native 0.2.0 (phase 2, libairplay linked)");
#else
    return env->NewStringUTF("tarmac-native 0.2.0 (phase 2, libairplay stubbed)");
#endif
}
