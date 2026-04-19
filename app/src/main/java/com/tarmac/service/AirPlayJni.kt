package com.tarmac.service

/**
 * JNI bridge to the native AirPlay server built from UxPlay's lib/.
 * Method bodies are implemented in app/src/main/cpp/jni_bridge.cpp in Phase 2.
 */
object AirPlayJni {
    external fun startServer(deviceName: String, features: Long, pin: Int): Int
    external fun stopServer()
    external fun nativeVersion(): String
}
