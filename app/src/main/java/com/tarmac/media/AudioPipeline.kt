package com.tarmac.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import com.tarmac.service.Prefs
import com.tarmac.service.SessionStateBus
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Decodes UxPlay's RAOP audio frames (AAC-ELD / ALAC) and pushes PCM to a
 * low-latency [AudioTrack].
 *
 * UxPlay's `audio_decode_struct.ct` mapping (see lib/raop_rtp.c line 121):
 *   2 = ALAC  (spf=352)
 *   8 = AAC-ELD  (spf=480)
 */
class AudioPipeline(private val appContext: Context? = null) {

    companion object {
        private const val TAG = "AudioPipeline"
        private const val SAMPLE_RATE = 44_100
        private const val CHANNEL_COUNT = 2

        /**
         * How long to wait for a free decoder input slot. Dropping an encoded
         * AAC-ELD frame here desyncs the decoder and produces an audible
         * transient on the next decode, so we'd rather block ~one frame than
         * lose data. Output is still drained non-blocking (timeout 0).
         */
        private const val INPUT_DEQUEUE_TIMEOUT_US = 10_000L
        private const val ALAC_MIME = "audio/alac"
        private const val DEFAULT_BUFFER_KB = 32

        /** Consecutive submit errors before we ask the service to restart. */
        private const val FATAL_ERROR_THRESHOLD = 20

        /** Peak amplitude below which a decoded frame is replaced with silence (~-54dB). */
        private const val NOISE_GATE_THRESHOLD = 64

        /**
         * Stereo sample pairs over which to ramp gain when audio resumes after a
         * gated (silent) stretch. AAC-ELD emits a decoder transient — audible as
         * a static burst at dialogue onset — across roughly the first frame or
         * two after silence, so we ramp across ~20ms (≈two ELD frames) to
         * attenuate it where it's sharpest. The ramp is carried across decode
         * frame boundaries via [fadeRemainingPairs], so a transient longer than
         * one frame is still covered.
         */
        private const val FADE_IN_PAIRS = 882
    }

    @Volatile private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private val started = AtomicBoolean(false)

    // Guards codec/track lifecycle against the RAOP submit path. Without it,
    // stop() could release the codec while a worker that had already passed
    // the started check was inside submitEncoded() — caught, but each
    // occurrence counted toward FATAL_ERROR_THRESHOLD and could force a
    // spurious session restart during teardown. The started flag is
    // re-checked inside the lock to close that TOCTOU.
    private val codecLock = Object()
    @Volatile private var currentCt: Int = -1
    @Volatile private var alacUnsupportedLogged = false
    private var previousFrameGated = false
    // Stereo pairs still to ramp after a silence→signal transition. Persists
    // across decode frames so the fade can span a multi-frame decoder transient.
    private var fadeRemainingPairs = 0

    // Cumulative counters for dumpsys / debug-intent diagnostics. Atomic because
    // submit() is driven from RAOP worker threads; plain @Volatile is not safe
    // for RMW (`+= 1`) under concurrent callers.
    private val totalFramesIn = AtomicLong(0L)
    private val totalPcmBytesOut = AtomicLong(0L)
    private val totalDecoderErrors = AtomicLong(0L)
    @Volatile private var consecutiveDecoderErrors: Int = 0

    /** Invoked on a non-recoverable audio codec failure so the service can restart. */
    @Volatile var onFatalError: ((Throwable) -> Unit)? = null

    /** AudioTrack session ID for tunneled video/audio pairing; 0 before start(). */
    val audioSessionId: Int
        get() = track?.audioSessionId ?: 0

    /** Snapshot used by TarmacService.dump. Reads AudioTrack.underrunCount when available. */
    data class Stats(
        val codecLabel: String,
        val audioSessionId: Int,
        val totalFramesIn: Long,
        val totalPcmBytesOut: Long,
        val totalDecoderErrors: Long,
        val underrunCount: Int,
    )

    fun stats(): Stats {
        val underruns = track?.let {
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    it.underrunCount
                } else -1
            }.getOrDefault(-1)
        } ?: -1
        return Stats(
            codecLabel = audioCodecLabel(currentCt),
            audioSessionId = audioSessionId,
            totalFramesIn = totalFramesIn.get(),
            totalPcmBytesOut = totalPcmBytesOut.get(),
            totalDecoderErrors = totalDecoderErrors.get(),
            underrunCount = underruns,
        )
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        synchronized(codecLock) {
            track = buildAudioTrack().also { it.play() }
        }
        Log.i(TAG, "AudioPipeline started")
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        synchronized(codecLock) {
            codec?.runCatching { stop(); release() }
            codec = null
            track?.runCatching { stop(); release() }
            track = null
            currentCt = -1
            previousFrameGated = false
            fadeRemainingPairs = 0
        }
    }

    fun submit(direct: ByteBuffer, length: Int, compressionType: Int, ntpTimeLocal: Long) {
        if (!started.get()) return
        if (compressionType != currentCt) {
            reconfigureCodec(compressionType)
        }
        totalFramesIn.incrementAndGet()
        submitEncoded(direct, length, ntpTimeLocal)
    }

    private fun reconfigureCodec(ct: Int): Unit = synchronized(codecLock) {
        // Re-check under the lock: a concurrent stop() must not be followed
        // by a fresh codec we'd then leak.
        if (!started.get()) return
        codec?.runCatching { stop(); release() }
        codec = null
        currentCt = ct
        val mime = when (ct) {
            2 -> ALAC_MIME
            8 -> MediaFormat.MIMETYPE_AUDIO_AAC
            else -> {
                Log.w(TAG, "Unknown compression type $ct — skipping codec setup")
                return
            }
        }
        SessionStateBus.setAudioCodec(audioCodecLabel(ct))
        if (mime == ALAC_MIME && !hasDecoderFor(ALAC_MIME)) {
            if (!alacUnsupportedLogged) {
                Log.w(TAG, "No ALAC decoder on this device; dropping ALAC frames. " +
                        "macOS mirror audio uses AAC-ELD so this typically only " +
                        "trips on iTunes/Music AirPlay.")
                alacUnsupportedLogged = true
            }
            return
        }
        val format = MediaFormat.createAudioFormat(mime, SAMPLE_RATE, CHANNEL_COUNT).apply {
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfoCompat.AAC_ELD)
                // AAC-ELD 44100Hz stereo
                setByteBuffer("csd-0", ByteBuffer.wrap(
                    byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00)
                ))
            }
        }
        try {
            val c = MediaCodec.createDecoderByType(mime)
            c.configure(format, null, null, 0)
            c.start()
            codec = c
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to configure audio codec for ct=$ct: ${t.message}")
            // Leave currentCt unset so the next frame of this compression type
            // retries configuration instead of matching currentCt and silently
            // dropping audio forever (codec stays null). The intentional skips
            // above (unknown ct / no ALAC decoder) deliberately keep currentCt.
            currentCt = -1
        }
    }

    private fun hasDecoderFor(mime: String): Boolean {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        return list.codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
    }

    private fun submitEncoded(direct: ByteBuffer, length: Int, ntpTimeLocal: Long): Unit = synchronized(codecLock) {
        if (!started.get()) return
        val c = codec ?: return
        try {
            val inIdx = c.dequeueInputBuffer(INPUT_DEQUEUE_TIMEOUT_US)
            if (inIdx >= 0) {
                val inBuf = c.getInputBuffer(inIdx)
                if (inBuf != null) {
                    inBuf.clear()
                    direct.position(0)
                    direct.limit(length)
                    inBuf.put(direct)
                    c.queueInputBuffer(inIdx, 0, length, ntpTimeLocal / 1000L, 0)
                }
            }
            val info = MediaCodec.BufferInfo()
            var outIdx = c.dequeueOutputBuffer(info, 0)
            while (outIdx != MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (outIdx >= 0) {
                    val outBuf = c.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0) {
                        val pcm = ByteArray(info.size)
                        outBuf.position(info.offset)
                        outBuf.get(pcm, 0, info.size)
                        applyNoiseGate(pcm)
                        // Blocking write so a momentarily full AudioTrack buffer
                        // back-pressures decode instead of silently discarding
                        // PCM (WRITE_NON_BLOCKING returns a short count we'd drop,
                        // which underruns the track and glitches the audio).
                        track?.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                        totalPcmBytesOut.addAndGet(pcm.size.toLong())
                    }
                    c.releaseOutputBuffer(outIdx, false)
                }
                outIdx = c.dequeueOutputBuffer(info, 0)
            }
            consecutiveDecoderErrors = 0
        } catch (t: Throwable) {
            totalDecoderErrors.incrementAndGet()
            consecutiveDecoderErrors += 1
            Log.w(TAG, "submitEncoded failed: ${t.message}")
            val fatal = t is MediaCodec.CodecException && !t.isRecoverable ||
                consecutiveDecoderErrors >= FATAL_ERROR_THRESHOLD
            if (fatal) {
                Log.e(TAG, "audio codec unrecoverable after $consecutiveDecoderErrors errors")
                onFatalError?.invoke(t)
            }
        }
    }

    private fun applyNoiseGate(pcm: ByteArray) {
        if (peakAmplitude(pcm) <= NOISE_GATE_THRESHOLD) {
            pcm.fill(0)
            previousFrameGated = true
            return
        }
        if (previousFrameGated) {
            // Silence → signal: arm a fresh ramp spanning the decoder's
            // post-silence transient. It may run past this frame, so the
            // remaining length is tracked and continued on subsequent frames.
            fadeRemainingPairs = FADE_IN_PAIRS
            previousFrameGated = false
        }
        if (fadeRemainingPairs > 0) applyFadeIn(pcm)
    }

    private fun peakAmplitude(pcm: ByteArray): Int {
        var maxAbs = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            val signed = if (sample > 32767) sample - 65536 else sample
            val abs = if (signed >= 0) signed else -signed
            if (abs > maxAbs) maxAbs = abs
            if (maxAbs > NOISE_GATE_THRESHOLD) return maxAbs
            i += 2
        }
        return maxAbs
    }

    /**
     * Ramp gain from where the ongoing fade left off up toward unity, one
     * stereo pair (L+R, 4 bytes) at a time, decrementing [fadeRemainingPairs]
     * so the ramp continues seamlessly on the next frame if it runs long.
     */
    private fun applyFadeIn(pcm: ByteArray) {
        var i = 0
        while (i + 3 < pcm.size && fadeRemainingPairs > 0) {
            val gain = (FADE_IN_PAIRS - fadeRemainingPairs).toFloat() / FADE_IN_PAIRS
            scaleSampleAt(pcm, i, gain)       // left
            scaleSampleAt(pcm, i + 2, gain)   // right
            i += 4
            fadeRemainingPairs--
        }
    }

    private fun scaleSampleAt(pcm: ByteArray, idx: Int, gain: Float) {
        val sample = (pcm[idx].toInt() and 0xFF) or (pcm[idx + 1].toInt() shl 8)
        val signed = if (sample > 32767) sample - 65536 else sample
        val scaled = (signed * gain).toInt().coerceIn(-32768, 32767)
        pcm[idx] = (scaled and 0xFF).toByte()
        pcm[idx + 1] = ((scaled shr 8) and 0xFF).toByte()
    }

    private fun audioCodecLabel(ct: Int): String = when (ct) {
        2 -> "ALAC"
        8 -> "AAC-ELD"
        else -> "ct=$ct"
    }

    private fun buildAudioTrack(): AudioTrack {
        val channelMask =
            if (CHANNEL_COUNT == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(8192)
        // User-tunable buffer (KB → bytes), floored at MediaCodec's minimum so
        // we don't hand AudioTrack a buffer it'll reject.
        val requestedBytes = (appContext?.let { Prefs.audioBufferKb(it) } ?: DEFAULT_BUFFER_KB) * 1024
        val bufBytes = maxOf(requestedBytes, minBuf)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
            .build()
    }
}

private object MediaCodecInfoCompat {
    // android.media.MediaCodecInfo.CodecProfileLevel constants, redeclared so
    // the imports stay short here.
    const val AAC_LC = 2
    const val AAC_ELD = 39
}
