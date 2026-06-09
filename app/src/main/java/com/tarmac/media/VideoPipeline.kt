package com.tarmac.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.tarmac.service.Prefs
import com.tarmac.service.SessionStateBus
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Decodes UxPlay's mirrored H.264 / H.265 NALU stream onto a [Surface] using
 * MediaCodec.
 *
 * Phase 5a uses a lazy-configure model for HEVC so HDR10 static metadata is
 * passed to MediaCodec via `setByteBuffer(KEY_HDR_STATIC_INFO, ...)` at
 * `configure()` time — the only API contract Android guarantees for this key.
 *
 *   1. `start()` captures intent (mime, display caps) but does not instantiate
 *      the codec.
 *   2. `submit()` buffers the first few NALU packets while scanning each for
 *      the HEVC SPS (resolution) and prefix SEI 137/144 (mastering display +
 *      content light level). For H.264 we configure on the first submit.
 *   3. Once we have enough information — or we've hit [MAX_PRECONFIG_SUBMITS]
 *      and stop waiting — we configure the codec with the correct picture
 *      size, HDR color metadata, and (if seen) the HDR_STATIC_INFO blob, then
 *      flush the buffered NALUs and enter steady-state pass-through.
 *
 * Phase 5b additions:
 *  - Probes [MediaCodecList] for FEATURE_TunneledPlayback support when HEVC is
 *    active and a valid [audioSessionId] is available.
 *  - Enables FEATURE_TunneledPlayback on the MediaFormat and sets
 *    KEY_AUDIO_SESSION_ID so the codec pipeline handles A/V sync in hardware.
 *  - Falls back to non-tunneled configure if the codec rejects the tunneled
 *    configuration (catches IllegalStateException / MediaCodec.CodecException).
 *
 * HDR is applied only when the user opted in AND the display reports HDR10
 * support, so we don't send HDR metadata to a TV that can't render it.
 */
class VideoPipeline(
    private val outputSurface: Surface,
    private val appContext: Context? = null,
    private val audioSessionId: Int = 0,
    private val presetCaps: DisplayCapabilities? = null,
) {

    companion object {
        private const val TAG = "VideoPipeline"
        private const val FHD_W = 1920
        private const val FHD_H = 1080
        private const val UHD_W = 3840
        private const val UHD_H = 2160
        private const val DEQUEUE_TIMEOUT_US = 1_000L
        private const val STATS_INTERVAL_MS = 1_000L

        // Enough to cover the initial IDR plus any delayed SEI. At 60fps this
        // is ~260ms of startup buffering before we give up and configure in
        // whatever state we have.
        private const val MAX_PRECONFIG_SUBMITS = 16

        /**
         * Consecutive submit errors at which we give up on the current codec
         * state and ask the service to restart the session. Covers the case
         * where the codec has silently entered an error state without raising
         * a non-recoverable CodecException.
         */
        private const val FATAL_ERROR_THRESHOLD = 10

        /**
         * Frame-pacing cushion. Decoded frames are scheduled for display at
         * their stream PTS mapped onto the local clock plus this latency, so
         * network arrival jitter doesn't translate into on-screen judder. ~50ms
         * sits below AirPlay mirroring's existing end-to-end latency and is wide
         * enough to absorb typical jitter.
         */
        private const val PACING_TARGET_LATENCY_NS = 50_000_000L

        /**
         * If a frame's mapped render time lands more than this far in the future,
         * the PTS timeline jumped (stream restart / CLOCK_REALTIME step) — we
         * re-anchor and render promptly rather than freeze on a bogus timestamp.
         */
        private const val PACING_MAX_AHEAD_NS = 1_000_000_000L
    }

    private data class Pending(val bytes: ByteArray, val ptsUs: Long)

    @Volatile private var codec: MediaCodec? = null
    private val started = AtomicBoolean(false)
    @Volatile private var currentMime: String = MediaFormat.MIMETYPE_VIDEO_AVC
    @Volatile private var width: Int = FHD_W
    @Volatile private var height: Int = FHD_H
    @Volatile private var hdrEnabled: Boolean = false
    @Volatile private var displaySupportsHdr10: Boolean = false
    @Volatile private var displaySupports4k: Boolean = false

    // Pre-configure buffering state. submit() may be invoked from multiple
    // RAOP worker threads and races the stop()/reset paths, so all access is
    // guarded by codecLock.
    private val pending = ArrayDeque<Pending>()
    private var pendingWidth: Int? = null
    private var pendingHeight: Int? = null
    private var pendingHdrBlob: ByteArray? = null
    @Volatile private var configuredHdrBlob: ByteArray? = null

    // Stats — only mutated from the RAOP callback thread inside submit().
    private var statsWindowStartMs: Long = 0L
    private var statsFrames: Int = 0
    private var statsBytes: Int = 0

    // Cumulative counters for dumpsys / debug-intent diagnostics. Atomic because
    // submit() may be invoked from multiple RAOP worker threads; plain @Volatile
    // gives visibility but not RMW atomicity, so increments would race.
    private val totalSubmits = AtomicLong(0L)
    private val totalRenderedFrames = AtomicLong(0L)
    private val totalDecoderErrors = AtomicLong(0L)
    private val totalDroppedFrames = AtomicLong(0L)
    // Async codec I/O state. submitToCodec (RAOP thread) and codec callbacks
    // (handler thread) access these under codecLock.
    private var codecThread: HandlerThread? = null
    private val codecLock = Object()
    private val asyncInput = ArrayDeque<Pending>()
    private val freeInputBuffers = ArrayDeque<Int>()

    // Codec lifecycle generation, guarded by codecLock. Bumped on every
    // teardown and configure so callbacks created for an earlier codec
    // instance detect they are stale and bail instead of touching a codec
    // that is being (or has been) released. Buffer indices in
    // freeInputBuffers are implicitly generation-bound: both queues are
    // cleared under the lock at every bump, and stale callbacks can no
    // longer enqueue indices afterward.
    private var codecGeneration = 0

    // True while incoming NALUs are discarded after an overflow flush, until
    // the next keyframe re-anchors the decoder. Guarded by codecLock.
    private var droppingUntilKeyframe = false

    @Volatile private var lastOutputTimeMs: Long = 0L
    @Volatile private var lastResetTimeMs: Long = 0L

    // Frame-pacing anchor mapping stream PTS (µs, on UxPlay's local NTP clock)
    // onto System.nanoTime(). Established on the first rendered frame and reset
    // to 0 on every (re)configure. @Volatile: written from the RAOP thread
    // (configure/reset/stop) and read on the codec callback thread.
    @Volatile private var pacingAnchorNs: Long = 0L
    @Volatile private var pacingAnchorPtsUs: Long = 0L

    /**
     * Callbacks are minted per codec instance with that instance's generation
     * baked in. A callback whose generation no longer matches
     * [codecGeneration] is racing a teardown/reset: it must neither feed the
     * dying codec nor donate its buffer index to the next one. Every codec
     * touch is also exception-guarded — an uncaught throw here lands on the
     * codec HandlerThread with no handler and kills the process.
     */
    private fun newCodecCallback(generation: Int) = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
            synchronized(codecLock) {
                if (generation != codecGeneration) return
                val p = asyncInput.removeFirstOrNull()
                if (p != null) {
                    try {
                        fillInputBuffer(mc, index, p)
                    } catch (t: Throwable) {
                        totalDecoderErrors.incrementAndGet()
                        Log.w(TAG, "async input fill failed: ${t.message}")
                    }
                } else {
                    freeInputBuffers.addLast(index)
                }
            }
        }
        override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            // Render at the frame's paced wall-clock time instead of "now" so a
            // burst of decoded frames is spread back out to the stream cadence.
            try {
                mc.releaseOutputBuffer(index, computeRenderTimestamp(info.presentationTimeUs))
                totalRenderedFrames.incrementAndGet()
                lastOutputTimeMs = SystemClock.elapsedRealtime()
            } catch (t: Throwable) {
                totalDecoderErrors.incrementAndGet()
                Log.w(TAG, "releaseOutputBuffer failed: ${t.message}")
            }
        }
        override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
            totalDecoderErrors.incrementAndGet()
            Log.e(TAG, "codec error: ${e.diagnosticInfo}")
            if (!e.isRecoverable) onFatalError?.invoke(e)
        }
        override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "output format changed: $format")
        }
    }

    /**
     * Invoked once per fatal (non-recoverable) codec failure so the owning
     * service can tear down and re-advertise rather than silently spinning on
     * a dead codec. Set by TarmacService.
     */
    @Volatile var onFatalError: ((Throwable) -> Unit)? = null

    /** Snapshot used by TarmacService.dump. Thread-safe via @Volatile reads. */
    data class Stats(
        val mime: String,
        val width: Int,
        val height: Int,
        val hdrActive: Boolean,
        val totalSubmits: Long,
        val totalRenderedFrames: Long,
        val totalDecoderErrors: Long,
        val totalDroppedFrames: Long,
    )

    fun stats() = Stats(
        mime = currentMime,
        width = width,
        height = height,
        hdrActive = hdrEnabled,
        totalSubmits = totalSubmits.get(),
        totalRenderedFrames = totalRenderedFrames.get(),
        totalDecoderErrors = totalDecoderErrors.get(),
        totalDroppedFrames = totalDroppedFrames.get(),
    )

    fun start(useHevc: Boolean = false) {
        if (!started.compareAndSet(false, true)) return
        val ctx = appContext
        val forceHevc = ctx?.let { Prefs.forceH265(it) } ?: false
        val hdrPrefOn = ctx?.let { Prefs.hdrEnabled(it) } ?: false

        val caps = presetCaps ?: DisplayCapabilities.probe(ctx)
        displaySupportsHdr10 = caps.supportsHdr10
        displaySupports4k = caps.supports4k
        hdrEnabled = hdrPrefOn && displaySupportsHdr10

        val effectiveHevc = useHevc || forceHevc
        currentMime = if (effectiveHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        width = FHD_W
        height = FHD_H

        synchronized(codecLock) { clearPendingLocked() }

        Log.i(
            TAG,
            "VideoPipeline pending configure mime=$currentMime hdr=$hdrEnabled " +
                "(prefOn=$hdrPrefOn displayHdr10=$displaySupportsHdr10 display4k=$displaySupports4k " +
                "audioSessionId=$audioSessionId)",
        )
        publishStats(reset = true)
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        releaseCodecAndThread()
        synchronized(codecLock) { clearPendingLocked() }
        lastOutputTimeMs = 0L
        lastResetTimeMs = 0L
        pacingAnchorNs = 0L
    }

    /**
     * Tears down the current codec and its callback thread atomically.
     *
     * Under [codecLock] the generation is advanced (so in-flight callbacks of
     * the old generation bail instead of touching the dying codec), the
     * codec/thread fields are detached, and the queued input state is
     * cleared. The codec is stopped and released only *after* the callback
     * thread has been quit and joined — releasing earlier races async
     * callbacks against the released codec, which used to surface as an
     * uncaught IllegalStateException on the callback thread mid-teardown or
     * mid-stall-recovery.
     */
    private fun releaseCodecAndThread() {
        val oldCodec: MediaCodec?
        val oldThread: HandlerThread?
        synchronized(codecLock) {
            codecGeneration++
            oldCodec = codec
            codec = null
            oldThread = codecThread
            codecThread = null
            asyncInput.clear()
            freeInputBuffers.clear()
            droppingUntilKeyframe = false
        }
        oldThread?.let {
            it.quitSafely()
            // join() outside codecLock: drained callbacks briefly take the
            // lock, so joining while holding it would deadlock. Never
            // self-join (defensive: teardown is not expected on this thread).
            if (Thread.currentThread() !== it) {
                runCatching { it.join(1_000) }
            }
        }
        oldCodec?.runCatching { stop(); release() }
    }

    /** Guarded by codecLock. */
    private fun clearPendingLocked() {
        pending.clear()
        pendingWidth = null
        pendingHeight = null
        pendingHdrBlob = null
    }

    fun submit(direct: ByteBuffer, length: Int, isH265: Boolean, ntpTimeLocal: Long) {
        if (!started.get() || length <= 0) return
        val expectedMime = if (isH265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        if (expectedMime != currentMime) {
            stop()
            start(useHevc = isH265)
        }
        val ptsUs = ntpTimeLocal / 1000L

        // The buffer-vs-submit decision must be made under codecLock: during
        // the surface-attach window multiple threads reach here, and an
        // unlocked check would let two of them race bufferForConfig's
        // mutation of `pending` (or buffer a frame that configureAndFlush
        // already flushed past).
        val configured = synchronized(codecLock) {
            if (codec == null) {
                bufferForConfigLocked(direct, length, ptsUs, isH265)
                false
            } else {
                true
            }
        }
        if (configured) submitToCodec(direct, length, ptsUs)
    }

    /**
     * Capture the NALU into the pre-config queue, update any side-data we can
     * extract from it, and configure the codec once we have what we need (or
     * we've waited long enough). Caller holds [codecLock].
     */
    private fun bufferForConfigLocked(direct: ByteBuffer, length: Int, ptsUs: Long, isH265: Boolean) {
        val bytes = ByteArray(length)
        val savedPos = direct.position()
        direct.position(0)
        direct.get(bytes, 0, length)
        direct.position(savedPos)

        // Only start buffering from a keyframe so the codec is always configured
        // and first fed a decodable IDR. Without this, the first frame after a
        // stall reset (or a late surface attach) is a mid-GOP P-frame the decoder
        // can't anchor to — it produces no output, the stall detector fires
        // again, and the screen stays frozen until the sender's next
        // spontaneous IDR (the "restart AirPlay to fix it" symptom).
        if (pending.isEmpty() && !containsKeyFrame(bytes, length, isH265)) return

        pending.addLast(Pending(bytes, ptsUs))

        if (isH265) {
            val parsed = HevcBitstream.parse(ByteBuffer.wrap(bytes), length)
            if (pendingWidth == null) {
                parsed.widthPx?.let { w -> parsed.heightPx?.let { h -> pendingWidth = w; pendingHeight = h } }
            }
            if (pendingHdrBlob == null) parsed.hdrStaticInfo?.let { pendingHdrBlob = it }
        }

        // Ready when:
        //  - H.264 (nothing to extract; configure with defaults and let the codec
        //    adapt to real resolution from its own SPS parse).
        //  - HEVC + SPS seen and either HDR not requested, or HDR blob also seen.
        //  - We've waited long enough — configure in whatever state we're in so a
        //    slow/absent SEI doesn't hang startup.
        val ready = !isH265 ||
            (pendingWidth != null && (!hdrEnabled || pendingHdrBlob != null)) ||
            pending.size >= MAX_PRECONFIG_SUBMITS
        if (ready) configureAndFlushLocked()
    }

    /** Caller holds [codecLock]. `codec`/`codecThread` are null on entry —
     *  releaseCodecAndThread() detaches both together before any path that
     *  re-enters pre-configure buffering. */
    private fun configureAndFlushLocked() {
        width = pendingWidth ?: FHD_W
        height = pendingHeight ?: FHD_H
        val hdrBlob = pendingHdrBlob

        codecGeneration++
        val generation = codecGeneration
        val thread = HandlerThread("VideoCodec").also { it.start() }
        codecThread = thread
        val handler = Handler(thread.looper)
        val now = SystemClock.elapsedRealtime()
        lastOutputTimeMs = now
        lastResetTimeMs = now
        pacingAnchorNs = 0L

        configuredHdrBlob = hdrBlob
        codec = tryTunneledConfigure(hdrBlob, handler, generation)
            ?: configureStandard(hdrBlob, handler, generation)

        Log.i(
            TAG,
            "VideoPipeline configured ${width}x${height} " +
                "max=${if (displaySupports4k) UHD_W else FHD_W}x${if (displaySupports4k) UHD_H else FHD_H} " +
                "hdr=$hdrEnabled hdrStaticInfo=${hdrBlob != null} queued=${pending.size}",
        )
        publishStats(reset = true)

        while (pending.isNotEmpty()) {
            val p = pending.removeFirst()
            submitToCodec(ByteBuffer.wrap(p.bytes), p.bytes.size, p.ptsUs)
        }
    }

    /** Builds the common MediaFormat keys shared by both tunneled and standard paths. */
    private fun buildBaseFormat(hdrBlob: ByteArray?): MediaFormat {
        val maxW = if (displaySupports4k) UHD_W else FHD_W
        val maxH = if (displaySupports4k) UHD_H else FHD_H
        return MediaFormat.createVideoFormat(currentMime, width, height).apply {
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
                if (hdrBlob != null) {
                    setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, ByteBuffer.wrap(hdrBlob))
                }
            }
        }
    }

    /**
     * Attempts to configure a tunneled MediaCodec decoder.  Tunneling pairs the
     * video decoder to the AudioTrack session so the codec pipeline handles A/V
     * sync in hardware — lower latency and drift-free on devices that support it.
     *
     * Only attempted for HEVC when a valid [audioSessionId] is available.
     * Returns null (caller falls back to [configureStandard]) if:
     *  - Not HEVC, or no audio session yet.
     *  - No HEVC decoder on the device supports FEATURE_TunneledPlayback.
     *  - The chosen codec rejects the tunneled configure (illegal state or
     *    codec error), indicating the feature is present in the codec list but
     *    not actually usable in this configuration.
     */
    private fun tryTunneledConfigure(hdrBlob: ByteArray?, handler: Handler, generation: Int): MediaCodec? {
        if (currentMime != MediaFormat.MIMETYPE_VIDEO_HEVC || audioSessionId <= 0) return null

        val probeFormat = MediaFormat.createVideoFormat(currentMime, width, height).apply {
            setFeatureEnabled(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback, true)
        }
        if (MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(probeFormat) == null) {
            Log.d(TAG, "No HEVC tunneled-playback decoder on this device; using non-tunneled")
            return null
        }

        val format = buildBaseFormat(hdrBlob).apply {
            setFeatureEnabled(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback, true)
            setInteger(MediaFormat.KEY_AUDIO_SESSION_ID, audioSessionId)
        }
        var c: MediaCodec? = null
        return try {
            MediaCodec.createDecoderByType(currentMime).also {
                c = it
                it.setCallback(newCodecCallback(generation), handler)
                it.configure(format, outputSurface, null, 0)
                it.start()
                Log.i(TAG, "Tunneled HEVC active (audioSessionId=$audioSessionId)")
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Tunneled HEVC configure rejected (${e.message}); falling back to non-tunneled")
            c?.runCatching { release() } // don't leak the half-configured codec
            null
        } catch (e: MediaCodec.CodecException) {
            Log.w(TAG, "Tunneled HEVC configure rejected (${e.diagnosticInfo}); falling back to non-tunneled")
            c?.runCatching { release() }
            null
        }
    }

    private fun configureStandard(hdrBlob: ByteArray?, handler: Handler, generation: Int): MediaCodec {
        val format = buildBaseFormat(hdrBlob)
        return MediaCodec.createDecoderByType(currentMime).also {
            it.setCallback(newCodecCallback(generation), handler)
            it.configure(format, outputSurface, null, 0)
            it.start()
        }
    }

    private fun fillInputBuffer(mc: MediaCodec, index: Int, p: Pending) {
        val buf = mc.getInputBuffer(index) ?: return
        buf.clear()
        buf.put(p.bytes, 0, p.bytes.size)
        mc.queueInputBuffer(index, 0, p.bytes.size, p.ptsUs, 0)
        statsFrames += 1
        statsBytes += p.bytes.size
        totalSubmits.incrementAndGet()
    }

    private fun submitToCodec(src: ByteBuffer, length: Int, ptsUs: Long) {
        val bytes = ByteArray(length)
        src.position(0)
        src.get(bytes, 0, length)
        val p = Pending(bytes, ptsUs)

        val isH265 = currentMime == MediaFormat.MIMETYPE_VIDEO_HEVC
        synchronized(codecLock) {
            // Capture inside the lock so the codec we feed is the same
            // generation as the freeInputBuffers index we pair it with.
            val c = codec ?: return
            if (droppingUntilKeyframe) {
                if (!containsKeyFrame(bytes, bytes.size, isH265)) {
                    totalDroppedFrames.incrementAndGet()
                    return
                }
                droppingUntilKeyframe = false
            }
            val idx = freeInputBuffers.removeFirstOrNull()
            if (idx != null) {
                try {
                    fillInputBuffer(c, idx, p)
                } catch (t: Throwable) {
                    totalDecoderErrors.incrementAndGet()
                    Log.w(TAG, "submit failed: ${t.message}")
                }
            } else if (asyncInput.size > 30) {
                // Dropping a single mid-GOP NALU punches a hole in the
                // reference chain — the decoder then gets P-frames whose
                // references never arrived and paints macroblock soup until
                // the next IDR. On overflow, flush the whole backlog and
                // discard until a keyframe re-anchors the stream: a brief
                // freeze instead of visible corruption.
                totalDroppedFrames.addAndGet(asyncInput.size.toLong())
                asyncInput.clear()
                if (containsKeyFrame(bytes, bytes.size, isH265)) {
                    asyncInput.addLast(p) // restart the GOP from this keyframe
                } else {
                    totalDroppedFrames.incrementAndGet()
                    droppingUntilKeyframe = true
                }
            } else {
                asyncInput.addLast(p)
            }
        }
        maybePublishStats()
        maybeResetOnStall()
    }

    /**
     * Map a decoded frame's stream PTS onto the [System.nanoTime] timeline so
     * MediaCodec renders it at the stream's cadence instead of as fast as it
     * decodes. The first frame establishes the anchor (PTS↔clock offset plus a
     * latency cushion); later frames are scheduled relative to it. A render time
     * far in the future means the PTS timeline jumped, so we re-anchor; a frame
     * already late is rendered immediately without moving the anchor, so
     * transient jitter doesn't ratchet latency upward.
     *
     * Runs only on the codec callback (handler) thread.
     */
    private fun computeRenderTimestamp(ptsUs: Long): Long {
        val now = System.nanoTime()
        if (pacingAnchorNs == 0L) {
            pacingAnchorPtsUs = ptsUs
            pacingAnchorNs = now + PACING_TARGET_LATENCY_NS
            return pacingAnchorNs
        }
        val renderNs = pacingAnchorNs + (ptsUs - pacingAnchorPtsUs) * 1000L
        return when {
            renderNs - now > PACING_MAX_AHEAD_NS -> {
                pacingAnchorPtsUs = ptsUs
                pacingAnchorNs = now + PACING_TARGET_LATENCY_NS
                pacingAnchorNs
            }
            renderNs < now -> now
            else -> renderNs
        }
    }

    /**
     * Scan an Annex-B NAL stream for a keyframe-class unit. H.264: IDR slice (5)
     * or parameter sets (SPS=7, PPS=8). H.265: VCL IRAP (16..21), VPS/SPS/PPS
     * (32/33/34), or prefix SEI (39) — the units Apple senders bundle with an
     * IDR. Mirrors [com.tarmac.service.AirPlayJni]'s pre-surface detector.
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

    private fun maybeResetOnStall() {
        val outputTime = lastOutputTimeMs
        if (outputTime == 0L || totalSubmits.get() < 60) return
        val now = SystemClock.elapsedRealtime()
        if (now - outputTime < 3_000) return
        if (now - lastResetTimeMs < 10_000) return

        Log.w(TAG, "Video stall: ${totalSubmits.get()} submitted, " +
            "${totalRenderedFrames.get()} rendered — resetting to pre-configure")
        lastResetTimeMs = now
        releaseCodecAndThread()
        synchronized(codecLock) { clearPendingLocked() }
        lastOutputTimeMs = now
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
