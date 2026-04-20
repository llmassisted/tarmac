package com.tarmac.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.tarmac.service.Prefs
import com.tarmac.service.SessionStateBus
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decodes UxPlay's mirrored H.264 / H.265 NALU stream onto a [Surface] using
 * MediaCodec.
 *
 * Phase 3 additions:
 *  - Reads `force_h265`, `force_1080p`, `hdr_enabled` prefs at start().
 *  - Publishes codec / resolution / fps / bitrate to SessionStateBus once a
 *    second so MainFragment can render live stream stats.
 *
 * HDR10 hardware tunneling stays Phase 5 — we set the HDR color metadata
 * here when the user opts in, but actual tunneled output is wired later.
 */
class VideoPipeline(
    private val outputSurface: Surface,
    private val appContext: Context? = null,
) {

    companion object {
        private const val TAG = "VideoPipeline"
        private const val DEFAULT_WIDTH = 1920
        private const val DEFAULT_HEIGHT = 1080
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val STATS_INTERVAL_MS = 1_000L
    }

    private var codec: MediaCodec? = null
    private val started = AtomicBoolean(false)
    @Volatile private var currentMime: String = MediaFormat.MIMETYPE_VIDEO_AVC
    @Volatile private var width: Int = DEFAULT_WIDTH
    @Volatile private var height: Int = DEFAULT_HEIGHT
    @Volatile private var hdrEnabled: Boolean = false

    // Stats — only mutated from the RAOP callback thread inside submit().
    private var statsWindowStartMs: Long = 0L
    private var statsFrames: Int = 0
    private var statsBytes: Int = 0

    fun start(useHevc: Boolean = false) {
        if (!started.compareAndSet(false, true)) return
        val ctx = appContext
        val forceHevc = ctx?.let { Prefs.forceH265(it) } ?: false
        val cap1080p = ctx?.let { Prefs.force1080p(it) } ?: false
        hdrEnabled = ctx?.let { Prefs.hdrEnabled(it) } ?: false

        val effectiveHevc = useHevc || forceHevc
        currentMime = if (effectiveHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        width = if (cap1080p) DEFAULT_WIDTH else DEFAULT_WIDTH
        height = if (cap1080p) DEFAULT_HEIGHT else DEFAULT_HEIGHT

        val format = MediaFormat.createVideoFormat(currentMime, width, height).apply {
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            if (hdrEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
        }
        val c = MediaCodec.createDecoderByType(currentMime)
        c.configure(format, outputSurface, null, 0)
        c.start()
        codec = c
        Log.i(TAG, "VideoPipeline started ($currentMime ${width}x${height} hdr=$hdrEnabled)")
        publishStats(reset = true)
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        codec?.runCatching { stop(); release() }
        codec = null
    }

    fun submit(direct: ByteBuffer, length: Int, isH265: Boolean, ntpTimeLocal: Long) {
        val c = codec ?: return
        val expectedMime = if (isH265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        if (expectedMime != currentMime) {
            stop()
            start(useHevc = isH265)
        }
        val codecRef = codec ?: return
        try {
            val inIdx = codecRef.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inIdx < 0) return
            val inBuf = codecRef.getInputBuffer(inIdx) ?: return
            inBuf.clear()
            direct.position(0)
            direct.limit(length)
            inBuf.put(direct)
            val ptsUs = ntpTimeLocal / 1000L
            codecRef.queueInputBuffer(inIdx, 0, length, ptsUs, 0)

            val info = MediaCodec.BufferInfo()
            var outIdx = codecRef.dequeueOutputBuffer(info, 0)
            while (outIdx >= 0) {
                codecRef.releaseOutputBuffer(outIdx, /*render*/true)
                outIdx = codecRef.dequeueOutputBuffer(info, 0)
            }
            statsFrames += 1
            statsBytes += length
            maybePublishStats()
        } catch (t: Throwable) {
            Log.w(TAG, "submit failed: ${t.message}")
        }
    }

    private fun maybePublishStats() {
        val now = SystemClock.elapsedRealtime()
        if (statsWindowStartMs == 0L) {
            statsWindowStartMs = now
            return
        }
        val elapsed = now - statsWindowStartMs
        if (elapsed < STATS_INTERVAL_MS) return
        publishStats(reset = false)
        statsWindowStartMs = now
        statsFrames = 0
        statsBytes = 0
    }

    private fun publishStats(reset: Boolean) {
        val codecLabel = if (currentMime == MediaFormat.MIMETYPE_VIDEO_HEVC) "H.265" else "H.264"
        val res = "${width}×${height}"
        if (reset) {
            SessionStateBus.updateVideo(codecLabel, res, fps = null, bitrateKbps = null)
            statsWindowStartMs = 0L
            statsFrames = 0
            statsBytes = 0
            return
        }
        val seconds = (SystemClock.elapsedRealtime() - statsWindowStartMs) / 1000.0
        val fps = if (seconds > 0) (statsFrames / seconds).toInt() else null
        val kbps = if (seconds > 0) ((statsBytes * 8) / seconds / 1000.0).toInt() else null
        SessionStateBus.updateVideo(codecLabel, res, fps, kbps)
    }
}
