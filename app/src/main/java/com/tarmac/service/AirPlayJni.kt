package com.tarmac.service

import android.util.Log
import com.tarmac.media.AudioPipeline
import com.tarmac.media.VideoPipeline
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

/**
 * JNI bridge to the native AirPlay server built from UxPlay's lib/.
 *
 * Native methods are implemented in app/src/main/cpp/jni_bridge.cpp.
 * The native side calls back into the four `on…` methods below from RAOP
 * worker threads — never the main thread — so handlers must marshal to the
 * Android main looper themselves before touching UI.
 */
object AirPlayJni {

    private const val TAG = "AirPlayJni"

    init {
        System.loadLibrary("tarmac-native")
    }

    /**
     * Session lifecycle states surfaced by `onSessionState`.
     * Mirrors raop_callbacks_t.conn_init / conn_destroy / conn_reset.
     */
    enum class SessionState { IDLE, ACTIVE }

    private val listenerRef = AtomicReference<Listener?>(null)

    interface Listener {
        fun onPinDisplay(pin: String)
        fun onSessionState(state: SessionState)
        fun onVideoPlay(url: String, startSec: Float)
        fun onVideoStop()
        fun onVideoRate(rate: Float)
        fun onVideoScrub(positionSec: Float)
    }

    /** Java-side delegates. Set before calling [startServer]. */
    @JvmStatic
    fun setListener(listener: Listener?) {
        listenerRef.set(listener)
    }

    @Volatile var videoPipeline: VideoPipeline? = null
    @Volatile var audioPipeline: AudioPipeline? = null

    external fun startServer(deviceName: String, hwAddr: ByteArray, features: Long, pin: Int): Int
    external fun stopServer()
    external fun nativeVersion(): String

    // --- Native -> Java callbacks. Names + signatures must match the JNI lookup
    // in jni_bridge.cpp::resolve_callback_methods. ----------------------------

    /**
     * H.264 / H.265 encoded NALU(s). [direct] is a JNI direct ByteBuffer that
     * is only valid for the duration of this call — copy out before returning
     * if you queue it.
     */
    // Instance (not @JvmStatic) — the JNI bridge holds a global ref to the
    // singleton instance and calls these via GetMethodID + CallVoidMethod.
    @Suppress("unused")
    fun onVideoData(direct: ByteBuffer, length: Int, isH265: Boolean, ntpTimeLocal: Long) {
        videoPipeline?.submit(direct, length, isH265, ntpTimeLocal)
    }

    /**
     * AAC-ELD / ALAC / PCM audio frame. [compressionType] is UxPlay's
     * `audio_decode_struct.ct` — 1=ALAC, 2=AAC-LC, 4=AAC-ELD, 8=PCM.
     */
    @Suppress("unused")
    fun onAudioData(direct: ByteBuffer, length: Int, compressionType: Int, ntpTimeLocal: Long) {
        audioPipeline?.submit(direct, length, compressionType, ntpTimeLocal)
    }

    @Suppress("unused")
    fun onPinDisplay(pin: String) {
        Log.i(TAG, "onPinDisplay($pin)")
        listenerRef.get()?.onPinDisplay(pin)
    }

    @Suppress("unused")
    fun onSessionState(state: Int) {
        val s = if (state == 1) SessionState.ACTIVE else SessionState.IDLE
        Log.i(TAG, "onSessionState($s)")
        listenerRef.get()?.onSessionState(s)
    }

    @Suppress("unused")
    fun onVideoPlay(url: String, startSec: Float) {
        Log.i(TAG, "onVideoPlay(url=$url, start=$startSec)")
        listenerRef.get()?.onVideoPlay(url, startSec)
    }

    @Suppress("unused")
    fun onVideoStop() {
        Log.i(TAG, "onVideoStop()")
        listenerRef.get()?.onVideoStop()
    }

    @Suppress("unused")
    fun onVideoRate(rate: Float) {
        Log.i(TAG, "onVideoRate($rate)")
        listenerRef.get()?.onVideoRate(rate)
    }

    @Suppress("unused")
    fun onVideoScrub(positionSec: Float) {
        Log.i(TAG, "onVideoScrub($positionSec)")
        listenerRef.get()?.onVideoScrub(positionSec)
    }
}
