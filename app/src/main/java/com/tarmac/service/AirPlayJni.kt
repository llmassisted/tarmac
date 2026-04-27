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
    private class BufferedFrame(
        val bytes: ByteArray,
        val isH265: Boolean,
        val ntpTimeLocal: Long,
        val isKeyFrame: Boolean,
    )

    /**
     * Scan an Annex-B NAL stream for a keyframe-class unit. For H.264 that
     * means an IDR slice (5) or parameter sets (SPS=7, PPS=8). For H.265 it
     * means VCL IRAP types (16..21) or VPS/SPS/PPS (32/33/34) and prefix SEI
     * (39), which Apple senders bundle with the IDR.
     */
    private fun containsKeyFrame(bytes: ByteArray, length: Int, isH265: Boolean): Boolean {
        var i = 0
        val end = minOf(length, bytes.size)
        while (i + 3 < end) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = bytes[i + 1].toInt() and 0xFF
            val b2 = bytes[i + 2].toInt() and 0xFF
            val skip = when {
                b0 == 0 && b1 == 0 && b2 == 1 -> 3
                b0 == 0 && b1 == 0 && b2 == 0 && (bytes[i + 3].toInt() and 0xFF) == 1 -> 4
                else -> 0
            }
            if (skip == 0) { i++; continue }
            val hdrIdx = i + skip
            if (hdrIdx >= end) return false
            val hdr = bytes[hdrIdx].toInt() and 0xFF
            if (isH265) {
                val type = (hdr ushr 1) and 0x3F
                if (type in 16..21 || type == 32 || type == 33 || type == 34 || type == 39) return true
            } else {
                val type = hdr and 0x1F
                if (type == 5 || type == 7 || type == 8) return true
            }
            i += skip
        }
        return false
    }

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
        // Copy outside the lock — the JNI direct buffer is only valid for this
        // call and we don't want to hold the buffer mutex during the memcpy.
        val bytes = ByteArray(length)
        direct.position(0)
        direct.get(bytes, 0, length)
        val isKey = containsKeyFrame(bytes, length, isH265)
        synchronized(videoBufferLock) {
            if (_videoPipeline != null) {
                // Pipeline attached while we were waiting for the lock — submit directly.
                _videoPipeline?.submit(ByteBuffer.wrap(bytes), length, isH265, ntpTimeLocal)
                return
            }
            val frame = BufferedFrame(bytes, isH265, ntpTimeLocal, isKey)
            if (isKey) {
                // Fresh keyframe — older buffered frames are no longer needed
                // (and may be stale relative to a stream restart).
                videoBuffer.clear()
                videoBuffer.addLast(frame)
                return@synchronized
            }
            if (videoBuffer.size >= VIDEO_BUFFER_MAX_FRAMES) {
                // Preserve a leading keyframe so the first delivery to the
                // pipeline is decodable. Drop the second frame instead.
                if (videoBuffer.size > 1 && videoBuffer.first().isKeyFrame) {
                    val head = videoBuffer.removeFirst()
                    videoBuffer.removeFirst()
                    videoBuffer.addFirst(head)
                } else {
                    videoBuffer.removeFirst()
                }
            }
            videoBuffer.addLast(frame)
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
