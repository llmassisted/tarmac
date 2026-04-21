package com.tarmac.media

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Surface
import com.tarmac.service.Prefs
import com.tarmac.service.SessionStateBus
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decodes UxPlay's mirrored H.264 / H.265 NALU stream onto a [Surface] using
 * MediaCodec.
 *
 * Phase 5a:
 *  - Probes the active `Display` for 4K and HDR10 capability. When the TV can
 *    do 4K, configures adaptive playback (`KEY_MAX_WIDTH / HEIGHT = 3840 /
 *    2160`) so the codec can accept a 4K CSD without a reconfigure.
 *  - Parses each submitted HEVC NALU for picture size (SPS) and HDR10 static
 *    metadata (prefix SEI 137 + 144). First time the stream carries HDR10
 *    metadata we push it to MediaCodec via `setParameters(KEY_HDR_STATIC_INFO)`
 *    so the HDMI output signals the TV to enter HDR mode.
 *  - HDR is applied only when the user opted in AND the display reports HDR10
 *    support — otherwise the stream decodes in SDR without surprising the TV.
 */
class VideoPipeline(
    private val outputSurface: Surface,
    private val appContext: Context? = null,
) {

    companion object {
        private const val TAG = "VideoPipeline"
        private const val FHD_W = 1920
        private const val FHD_H = 1080
        private const val UHD_W = 3840
        private const val UHD_H = 2160
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val STATS_INTERVAL_MS = 1_000L
    }

    private var codec: MediaCodec? = null
    private val started = AtomicBoolean(false)
    @Volatile private var currentMime: String = MediaFormat.MIMETYPE_VIDEO_AVC
    @Volatile private var width: Int = FHD_W
    @Volatile private var height: Int = FHD_H
    @Volatile private var hdrEnabled: Boolean = false
    @Volatile private var displaySupportsHdr10: Boolean = false
    @Volatile private var displaySupports4k: Boolean = false
    @Volatile private var hdrStaticInfoApplied: Boolean = false

    // Stats — only mutated from the RAOP callback thread inside submit().
    private var statsWindowStartMs: Long = 0L
    private var statsFrames: Int = 0
    private var statsBytes: Int = 0

    fun start(useHevc: Boolean = false) {
        if (!started.compareAndSet(false, true)) return
        val ctx = appContext
        val forceHevc = ctx?.let { Prefs.forceH265(it) } ?: false
        val hdrPrefOn = ctx?.let { Prefs.hdrEnabled(it) } ?: false

        probeDisplayCapabilities(ctx)
        hdrEnabled = hdrPrefOn && displaySupportsHdr10
        hdrStaticInfoApplied = false

        val effectiveHevc = useHevc || forceHevc
        currentMime = if (effectiveHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        width = FHD_W
        height = FHD_H

        // Configure the codec at 1080p but allow it to adapt up to 4K if the
        // display can render it. This avoids a stop/start when the Mac sends
        // a 4K CSD mid-session.
        val maxW = if (displaySupports4k) UHD_W else FHD_W
        val maxH = if (displaySupports4k) UHD_H else FHD_H
        val format = MediaFormat.createVideoFormat(currentMime, width, height).apply {
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_MAX_WIDTH, maxW)
            setInteger(MediaFormat.KEY_MAX_HEIGHT, maxH)
            if (hdrEnabled) {
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
        }
        val c = MediaCodec.createDecoderByType(currentMime)
        c.configure(format, outputSurface, null, 0)
        c.start()
        codec = c
        Log.i(
            TAG,
            "VideoPipeline started mime=$currentMime base=${width}x${height} " +
                "max=${maxW}x${maxH} hdr=$hdrEnabled (prefOn=$hdrPrefOn displayHdr10=$displaySupportsHdr10)",
        )
        publishStats(reset = true)
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        codec?.runCatching { stop(); release() }
        codec = null
        hdrStaticInfoApplied = false
    }

    fun submit(direct: ByteBuffer, length: Int, isH265: Boolean, ntpTimeLocal: Long) {
        val c = codec ?: return
        val expectedMime = if (isH265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        if (expectedMime != currentMime) {
            stop()
            start(useHevc = isH265)
        }
        val codecRef = codec ?: return

        // Cheap pre-queue scan of the bitstream for resolution changes and
        // HDR10 static metadata. Only HEVC carries what we're looking for.
        if (isH265) maybeExtractHevcSideData(codecRef, direct, length)

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

    private fun maybeExtractHevcSideData(codecRef: MediaCodec, direct: ByteBuffer, length: Int) {
        // Skip the scan once we've seen everything we need.
        val needResolution = width == FHD_W && height == FHD_H
        val needHdr = hdrEnabled && !hdrStaticInfoApplied
        if (!needResolution && !needHdr) return

        val parsed = HevcBitstream.parse(direct, length)
        parsed.widthPx?.let { w ->
            parsed.heightPx?.let { h ->
                if (w != width || h != height) {
                    width = w
                    height = h
                    Log.i(TAG, "HEVC SPS resolution detected ${w}x${h}")
                    publishStats(reset = false)
                }
            }
        }
        if (needHdr) {
            val blob = parsed.hdrStaticInfo ?: return
            runCatching {
                val params = Bundle().apply { putByteArray(MediaFormat.KEY_HDR_STATIC_INFO, blob) }
                codecRef.setParameters(params)
                hdrStaticInfoApplied = true
                Log.i(TAG, "HDR10 static metadata applied (${blob.size} bytes)")
            }.onFailure { Log.w(TAG, "setParameters(HDR_STATIC_INFO) failed: ${it.message}") }
        }
    }

    private fun probeDisplayCapabilities(ctx: Context?) {
        displaySupportsHdr10 = false
        displaySupports4k = false
        if (ctx == null) return
        val dm = runCatching {
            ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        }.getOrNull() ?: return
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // `supportedHdrTypes` is deprecated on API 34+ in favor of
            // Display.Mode.getSupportedHdrTypes, but we need to support minSdk 26.
            @Suppress("DEPRECATION")
            val types = display.hdrCapabilities?.supportedHdrTypes
            displaySupportsHdr10 = types?.any { it == Display.HdrCapabilities.HDR_TYPE_HDR10 } == true
        }
        displaySupports4k = display.supportedModes.any { mode ->
            (mode.physicalWidth >= UHD_W && mode.physicalHeight >= UHD_H) ||
                (mode.physicalWidth >= UHD_H && mode.physicalHeight >= UHD_W)
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
