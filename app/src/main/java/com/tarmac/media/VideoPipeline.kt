package com.tarmac.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decodes UxPlay's mirrored H.264 / H.265 NALU stream onto a [Surface] using
 * MediaCodec in async mode.
 *
 * Phase 2 baseline: 1080p60 H.264, surface-bound output (zero-copy render).
 * HEVC/HDR10 tunneled output is Phase 5.
 *
 * The native callback delivers a direct ByteBuffer that is only valid for the
 * duration of the call, so [submit] copies the bytes into the codec's input
 * buffer immediately.
 */
class VideoPipeline(private val outputSurface: Surface) {

    companion object {
        private const val TAG = "VideoPipeline"
        private const val WIDTH = 1920
        private const val HEIGHT = 1080
        private const val DEQUEUE_TIMEOUT_US = 10_000L
    }

    private var codec: MediaCodec? = null
    private val started = AtomicBoolean(false)
    @Volatile private var currentMime: String = MediaFormat.MIMETYPE_VIDEO_AVC

    fun start(useHevc: Boolean = false) {
        if (!started.compareAndSet(false, true)) return
        currentMime = if (useHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val format = MediaFormat.createVideoFormat(currentMime, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
        }
        val c = MediaCodec.createDecoderByType(currentMime)
        c.configure(format, outputSurface, null, 0)
        c.start()
        codec = c
        Log.i(TAG, "VideoPipeline started ($currentMime ${WIDTH}x${HEIGHT})")
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        codec?.runCatching { stop(); release() }
        codec = null
    }

    /**
     * Called from a native callback thread (see jni_bridge.cpp::cb_video_process).
     * [direct] is a JNI direct ByteBuffer valid only for the duration of this
     * call.
     */
    fun submit(direct: ByteBuffer, length: Int, isH265: Boolean, ntpTimeLocal: Long) {
        val c = codec ?: return
        // Hot-swap codec on first H.265 frame after a H.264 session, etc.
        val expectedMime = if (isH265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        if (expectedMime != currentMime) {
            stop()
            start(useHevc = isH265)
        }
        val codecRef = codec ?: return
        try {
            val inIdx = codecRef.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inIdx < 0) {
                // Drop: queue full. Better than blocking the RAOP thread.
                return
            }
            val inBuf = codecRef.getInputBuffer(inIdx) ?: return
            inBuf.clear()
            direct.position(0)
            direct.limit(length)
            inBuf.put(direct)
            // ntp_time_local is nanoseconds; MediaCodec wants microseconds.
            val ptsUs = ntpTimeLocal / 1000L
            codecRef.queueInputBuffer(inIdx, 0, length, ptsUs, 0)

            val info = MediaCodec.BufferInfo()
            var outIdx = codecRef.dequeueOutputBuffer(info, 0)
            while (outIdx >= 0) {
                codecRef.releaseOutputBuffer(outIdx, /*render*/true)
                outIdx = codecRef.dequeueOutputBuffer(info, 0)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "submit failed: ${t.message}")
        }
    }
}
