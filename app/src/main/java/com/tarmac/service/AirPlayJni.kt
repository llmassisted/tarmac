package com.tarmac.service

import android.util.Log
import com.tarmac.media.AudioPipeline
import com.tarmac.media.DisplayCapabilities
import com.tarmac.media.VideoPipeline
import java.nio.ByteBuffer
import java.util.ArrayDeque
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

    /** Max frames to buffer while waiting for MirrorActivity's surface. */
    private const val VIDEO_BUFFER_MAX_FRAMES = 30

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

    /**
     * Buffered video frame for the surface-creation race window. JNI's
     * ByteBuffer is only valid during the callback, so we copy bytes out.
     */
    private data class BufferedFrame(
        val bytes: ByteArray,
        val isH265: Boolean,
        val ntpTimeLocal: Long,
    )

    private val videoBufferLock = Any()
    private val videoBuffer = ArrayDeque<BufferedFrame>(VIDEO_BUFFER_MAX_FRAMES)
    @Volatile private var _videoPipeline: VideoPipeline? = null

    /**
     * Video pipeline sink. When set, any buffered frames from the surface-
     * creation race window are flushed immediately.
     */
    var videoPipeline: VideoPipeline?
        get() = _videoPipeline
        set(value) {
            synchronized(videoBufferLock) {
                _videoPipeline = value
                if (value != null) {
                    while (videoBuffer.isNotEmpty()) {
                        val f = videoBuffer.removeFirst()
                        value.submit(ByteBuffer.wrap(f.bytes), f.bytes.size, f.isH265, f.ntpTimeLocal)
                    }
                }
            }
        }

    @Volatile var audioPipeline: AudioPipeline? = null

    /** Audio session ID from AudioPipeline for tunneled video/audio pairing. */
    @Volatile var audioSessionId: Int = 0

    /**
     * Display capabilities probed once at session start by [TarmacService] and
     * shared with [VideoPipeline] so codec config and Bonjour advertisement
     * can't disagree if an external display is hotplugged mid-session.
     */
    @Volatile var displayCaps: DisplayCapabilities = DisplayCapabilities.UNKNOWN

    external fun startServer(deviceName: String, hwAddr: ByteArray, features: Long, pin: Int, maxWidth: Int, maxHeight: Int): Int
    external fun stopServer()
    external fun nativeVersion(): String

    // --- Java -> Native queries (called by native /playback-info handler) -----

    /**
     * Returns [positionSec, durationSec, rate] for the current AirPlay Video
     * (HLS) playback. Called by native code when handling GET /playback-info.
     * Returns null when no video is playing.
     */
    @Suppress("unused")
    fun getPlaybackInfo(): FloatArray? {
        val info = SessionStateBus.playbackInfo
        if (info.durationSec <= 0f) return null
        return floatArrayOf(info.positionSec, info.durationSec, info.rate)
    }

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
        val pipeline = _videoPipeline
        if (pipeline != null) {
            pipeline.submit(direct, length, isH265, ntpTimeLocal)
            return
        }
        // Pipeline not ready yet (MirrorActivity surface still being created).
        // Buffer the frame so we don't lose the initial IDR + SPS/PPS/SEI.
        synchronized(videoBufferLock) {
            if (_videoPipeline != null) {
                // Pipeline attached while we were waiting for the lock — submit directly.
                _videoPipeline?.submit(direct, length, isH265, ntpTimeLocal)
                return
            }
            if (videoBuffer.size >= VIDEO_BUFFER_MAX_FRAMES) {
                videoBuffer.removeFirst()
            }
            val bytes = ByteArray(length)
            direct.position(0)
            direct.get(bytes, 0, length)
            videoBuffer.addLast(BufferedFrame(bytes, isH265, ntpTimeLocal))
        }
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
        if (s == SessionState.IDLE) {
            synchronized(videoBufferLock) { videoBuffer.clear() }
        }
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
