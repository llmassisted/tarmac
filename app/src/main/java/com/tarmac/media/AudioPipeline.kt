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

/**
 * Decodes UxPlay's RAOP audio frames (AAC-ELD / ALAC / PCM) and pushes PCM to
 * a low-latency [AudioTrack].
 *
 * UxPlay's `audio_decode_struct.ct` mapping (see lib/raop_handlers.h):
 *   1 = ALAC
 *   2 = AAC-LC
 *   4 = AAC-ELD
 *   8 = PCM (passthrough)
 *
 * Phase 2 baseline: AAC-ELD → MediaCodec → AudioTrack PCM 44.1 kHz stereo.
 * ALAC is handled by switching the MediaCodec MIME type. PCM bypasses the
 * codec entirely.
 */
class AudioPipeline(private val appContext: Context? = null) {

    companion object {
        private const val TAG = "AudioPipeline"
        private const val SAMPLE_RATE = 44_100
        private const val CHANNEL_COUNT = 2
        private const val DEQUEUE_TIMEOUT_US = 5_000L
        private const val ALAC_MIME = "audio/alac"
        private const val DEFAULT_BUFFER_KB = 16
    }

    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private val started = AtomicBoolean(false)
    @Volatile private var currentCt: Int = -1
    @Volatile private var alacUnsupportedLogged = false

    // Cumulative counters for dumpsys / debug-intent diagnostics.
    @Volatile private var totalFramesIn: Long = 0L
    @Volatile private var totalPcmBytesOut: Long = 0L
    @Volatile private var totalDecoderErrors: Long = 0L

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
            totalFramesIn = totalFramesIn,
            totalPcmBytesOut = totalPcmBytesOut,
            totalDecoderErrors = totalDecoderErrors,
            underrunCount = underruns,
        )
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        track = buildAudioTrack().also { it.play() }
        Log.i(TAG, "AudioPipeline started")
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        codec?.runCatching { stop(); release() }
        codec = null
        track?.runCatching { stop(); release() }
        track = null
        currentCt = -1
    }

    fun submit(direct: ByteBuffer, length: Int, compressionType: Int, ntpTimeLocal: Long) {
        if (!started.get()) return
        if (compressionType != currentCt) {
            reconfigureCodec(compressionType)
        }
        totalFramesIn += 1
        when (compressionType) {
            8 -> submitPcm(direct, length)
            else -> submitEncoded(direct, length, ntpTimeLocal)
        }
    }

    private fun reconfigureCodec(ct: Int) {
        codec?.runCatching { stop(); release() }
        codec = null
        currentCt = ct
        val mime = when (ct) {
            1 -> ALAC_MIME
            2, 4 -> MediaFormat.MIMETYPE_AUDIO_AAC
            8 -> {
                SessionStateBus.setAudioCodec("PCM")
                return  // PCM: no codec needed
            }
            else -> {
                Log.w(TAG, "Unknown compression type $ct — skipping codec setup")
                return
            }
        }
        SessionStateBus.setAudioCodec(audioCodecLabel(ct))
        // Android has no guaranteed ALAC decoder; a lot of TV devices ship
        // without one. Detect up-front so we don't crash MediaCodec with
        // MediaCodec$CodecException on createDecoderByType.
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
                val profile = if (ct == 4) {
                    MediaCodecInfoCompat.AAC_ELD
                } else {
                    MediaCodecInfoCompat.AAC_LC
                }
                setInteger(MediaFormat.KEY_AAC_PROFILE, profile)
            }
        }
        try {
            val c = MediaCodec.createDecoderByType(mime)
            c.configure(format, null, null, 0)
            c.start()
            codec = c
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to configure audio codec for ct=$ct: ${t.message}")
        }
    }

    private fun hasDecoderFor(mime: String): Boolean {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        return list.codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
    }

    private fun submitEncoded(direct: ByteBuffer, length: Int, ntpTimeLocal: Long) {
        val c = codec ?: return
        try {
            val inIdx = c.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inIdx < 0) return
            val inBuf = c.getInputBuffer(inIdx) ?: return
            inBuf.clear()
            direct.position(0)
            direct.limit(length)
            inBuf.put(direct)
            c.queueInputBuffer(inIdx, 0, length, ntpTimeLocal / 1000L, 0)

            val info = MediaCodec.BufferInfo()
            var outIdx = c.dequeueOutputBuffer(info, 0)
            while (outIdx >= 0) {
                val outBuf = c.getOutputBuffer(outIdx)
                if (outBuf != null && info.size > 0) {
                    val pcm = ByteArray(info.size)
                    outBuf.position(info.offset)
                    outBuf.get(pcm, 0, info.size)
                    track?.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
                    totalPcmBytesOut += pcm.size.toLong()
                }
                c.releaseOutputBuffer(outIdx, false)
                outIdx = c.dequeueOutputBuffer(info, 0)
            }
        } catch (t: Throwable) {
            totalDecoderErrors += 1
            Log.w(TAG, "submitEncoded failed: ${t.message}")
        }
    }

    private fun submitPcm(direct: ByteBuffer, length: Int) {
        val pcm = ByteArray(length)
        direct.position(0)
        direct.limit(length)
        direct.get(pcm)
        track?.write(pcm, 0, length, AudioTrack.WRITE_NON_BLOCKING)
        totalPcmBytesOut += length.toLong()
    }

    private fun audioCodecLabel(ct: Int): String = when (ct) {
        1 -> "ALAC"
        2 -> "AAC-LC"
        4 -> "AAC-ELD"
        8 -> "PCM"
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
                    .setFlags(AudioAttributes.FLAG_LOW_LATENCY)
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
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }
}

private object MediaCodecInfoCompat {
    // android.media.MediaCodecInfo.CodecProfileLevel constants, redeclared so
    // the imports stay short here.
    const val AAC_LC = 2
    const val AAC_ELD = 39
}
