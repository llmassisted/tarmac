package com.tarmac.media

import java.nio.ByteBuffer

/**
 * Minimal HEVC (H.265) bitstream helpers used by [VideoPipeline] for phase 5a:
 *
 *  - Picture resolution from the SPS (used for live stats + adaptive playback
 *    bookkeeping).
 *  - HDR10 static metadata from prefix SEI (mastering display colour volume +
 *    content light level info) assembled into the 25-byte CTA-861.3 blob that
 *    `MediaFormat.KEY_HDR_STATIC_INFO` expects.
 *
 * Runs on the RAOP callback thread, so keep it cheap: scan and bail as soon as
 * each field is found. Only HEVC is handled — H.264 streams don't carry the
 * same HDR10 SEI and we don't advertise 4K HDR over H.264.
 */
object HevcBitstream {

    // H.265 Table 7-1 — NAL unit types we care about.
    private const val NAL_SPS = 33
    private const val NAL_PREFIX_SEI = 39

    // H.265 Annex D, Table D.1 — SEI payload types.
    private const val SEI_MASTERING_DISPLAY = 137
    private const val SEI_CONTENT_LIGHT_LEVEL = 144

    data class Parsed(
        val widthPx: Int? = null,
        val heightPx: Int? = null,
        /** 25-byte CTA-861.3 Type 1 blob ready for `KEY_HDR_STATIC_INFO`. */
        val hdrStaticInfo: ByteArray? = null,
    )

    /**
     * Scan [len] bytes of Annex-B framed NALUs in [src] (reading from index 0)
     * and return whichever of (resolution, HDR10 metadata) was recoverable.
     * Fields are `null` when the corresponding NAL unit wasn't in this buffer.
     * Leaves [src]'s position unchanged.
     */
    fun parse(src: ByteBuffer, len: Int): Parsed {
        if (len <= 0) return Parsed()
        val bytes = ByteArray(len)
        val savedPos = src.position()
        src.position(0)
        src.get(bytes, 0, len)
        src.position(savedPos)

        var width: Int? = null
        var height: Int? = null
        var mdcv: ByteArray? = null
        var cll: ByteArray? = null

        forEachNalu(bytes) { nalu ->
            if (nalu.size < 3) return@forEachNalu
            val nalType = (nalu[0].toInt() ushr 1) and 0x3F
            when (nalType) {
                NAL_SPS -> if (width == null) {
                    parseSpsResolution(rbsp(nalu, 2))?.let { (w, h) -> width = w; height = h }
                }
                NAL_PREFIX_SEI -> parseSeiPayloads(rbsp(nalu, 2)) { type, payload ->
                    when (type) {
                        SEI_MASTERING_DISPLAY -> if (mdcv == null && payload.size >= 24)
                            mdcv = payload.copyOfRange(0, 24)
                        SEI_CONTENT_LIGHT_LEVEL -> if (cll == null && payload.size >= 4)
                            cll = payload.copyOfRange(0, 4)
                    }
                }
            }
        }

        val hdrBlob = mdcv?.let { buildHdrStaticInfo(it, cll) }
        return Parsed(width, height, hdrBlob)
    }

    private inline fun forEachNalu(bytes: ByteArray, onNalu: (ByteArray) -> Unit) {
        var i = 0
        val n = bytes.size
        var start = -1
        while (i < n - 2) {
            val longSc = i + 3 < n &&
                    bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() &&
                    bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte()
            val shortSc = bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() &&
                    bytes[i + 2] == 1.toByte()
            if (longSc || shortSc) {
                if (start >= 0) onNalu(bytes.copyOfRange(start, i))
                i += if (longSc) 4 else 3
                start = i
                continue
            }
            i++
        }
        if (start in 0..n) onNalu(bytes.copyOfRange(start, n))
    }

    /** EBSP → RBSP: drop emulation prevention bytes (0x03 after two 0x00). */
    private fun rbsp(nalu: ByteArray, startOffset: Int): ByteArray {
        val out = ByteArray(nalu.size - startOffset)
        var oi = 0
        var zeros = 0
        for (i in startOffset until nalu.size) {
            val b = nalu[i]
            if (zeros >= 2 && b == 0x03.toByte()) {
                zeros = 0
                continue
            }
            zeros = if (b == 0.toByte()) zeros + 1 else 0
            out[oi++] = b
        }
        return out.copyOfRange(0, oi)
    }

    private class BitReader(private val buf: ByteArray) {
        private var bytePos = 0
        private var bitPos = 0

        fun readBit(): Int {
            if (bytePos >= buf.size) return 0
            val v = (buf[bytePos].toInt() ushr (7 - bitPos)) and 1
            bitPos++
            if (bitPos == 8) { bitPos = 0; bytePos++ }
            return v
        }

        fun readBits(n: Int): Int {
            var v = 0
            repeat(n) { v = (v shl 1) or readBit() }
            return v
        }

        fun readUe(): Int {
            var zeros = 0
            while (bytePos < buf.size && readBit() == 0) {
                zeros++
                if (zeros > 32) return 0
            }
            if (zeros == 0) return 0
            val suffix = readBits(zeros)
            return (1 shl zeros) - 1 + suffix
        }
    }

    /**
     * Parse HEVC SPS up to pic_width/height. Returns null if the SPS looks
     * malformed. Ignores conformance-window cropping — our use is stats + an
     * adaptive-playback ceiling, both fine with a few pixels of slack.
     */
    private fun parseSpsResolution(rbsp: ByteArray): Pair<Int, Int>? = try {
        val br = BitReader(rbsp)
        br.readBits(4)                              // sps_video_parameter_set_id
        val maxSubLayersMinus1 = br.readBits(3)
        br.readBit()                                // sps_temporal_id_nesting_flag
        skipProfileTierLevel(br, maxSubLayersMinus1)
        br.readUe()                                 // sps_seq_parameter_set_id
        val chromaFormat = br.readUe()
        if (chromaFormat == 3) br.readBit()         // separate_colour_plane_flag
        val w = br.readUe()
        val h = br.readUe()
        if (w in 1..8192 && h in 1..8192) w to h else null
    } catch (_: Throwable) { null }

    private fun skipProfileTierLevel(br: BitReader, maxSubLayersMinus1: Int) {
        // general_profile_tier_level — 96 bits / 12 bytes fixed layout.
        br.readBits(8)                              // profile_space + tier + profile_idc
        br.readBits(32)                             // profile_compatibility_flag
        br.readBits(4)                              // source flags
        br.readBits(32); br.readBits(12)            // reserved/constraint bits + inbld
        br.readBits(8)                              // level_idc

        val profilePresent = BooleanArray(maxSubLayersMinus1)
        val levelPresent = BooleanArray(maxSubLayersMinus1)
        for (i in 0 until maxSubLayersMinus1) {
            profilePresent[i] = br.readBit() == 1
            levelPresent[i] = br.readBit() == 1
        }
        if (maxSubLayersMinus1 > 0) {
            repeat(8 - maxSubLayersMinus1) { br.readBits(2) }
        }
        for (i in 0 until maxSubLayersMinus1) {
            if (profilePresent[i]) {
                br.readBits(8); br.readBits(32); br.readBits(4)
                br.readBits(32); br.readBits(12)
            }
            if (levelPresent[i]) br.readBits(8)
        }
    }

    private inline fun parseSeiPayloads(rbsp: ByteArray, onPayload: (Int, ByteArray) -> Unit) {
        var i = 0
        while (i < rbsp.size) {
            var type = 0
            while (i < rbsp.size && rbsp[i] == 0xFF.toByte()) { type += 255; i++ }
            if (i >= rbsp.size) return
            type += rbsp[i].toInt() and 0xFF; i++
            var size = 0
            while (i < rbsp.size && rbsp[i] == 0xFF.toByte()) { size += 255; i++ }
            if (i >= rbsp.size) return
            size += rbsp[i].toInt() and 0xFF; i++
            if (i + size > rbsp.size) return
            onPayload(type, rbsp.copyOfRange(i, i + size))
            i += size
            // rbsp_trailing_bits: 0x80 marks end.
            if (i < rbsp.size && rbsp[i] == 0x80.toByte()) return
        }
    }

    /**
     * Build the 25-byte `KEY_HDR_STATIC_INFO` blob from HEVC SEI payloads.
     *
     * HEVC SEI 137 is big-endian and interleaves primary x/y pairs
     * (x0,y0,x1,y1,x2,y2). CTA-861.3 Type 1 is little-endian and groups all
     * x's then all y's (x0,x1,x2,y0,y1,y2). Luminance scaling: HEVC max is
     * uint32 in 0.0001 cd/m²; CTA max is uint16 in cd/m², so divide by 10000.
     * HEVC min is uint32 in 0.0001 cd/m²; CTA min is uint16 in 0.0001 cd/m²
     * (fits comfortably since min is always near zero).
     */
    private fun buildHdrStaticInfo(mdcv24: ByteArray, cll4: ByteArray?): ByteArray {
        fun be16(o: Int) = ((mdcv24[o].toInt() and 0xFF) shl 8) or (mdcv24[o + 1].toInt() and 0xFF)
        fun be32(o: Int) = (be16(o) shl 16) or be16(o + 2)

        val primariesX = intArrayOf(be16(0), be16(4), be16(8))
        val primariesY = intArrayOf(be16(2), be16(6), be16(10))
        val wpX = be16(12)
        val wpY = be16(14)
        val maxLumCta = (be32(16) / 10_000).coerceIn(0, 0xFFFF)
        val minLumCta = be32(20).coerceIn(0, 0xFFFF)

        val maxCll = if (cll4 != null && cll4.size >= 2)
            ((cll4[0].toInt() and 0xFF) shl 8) or (cll4[1].toInt() and 0xFF) else 0
        val maxFall = if (cll4 != null && cll4.size >= 4)
            ((cll4[2].toInt() and 0xFF) shl 8) or (cll4[3].toInt() and 0xFF) else 0

        val out = ByteArray(25)
        out[0] = 0
        var o = 1
        fun putLe16(v: Int) {
            out[o++] = (v and 0xFF).toByte()
            out[o++] = ((v ushr 8) and 0xFF).toByte()
        }
        primariesX.forEach { putLe16(it) }
        primariesY.forEach { putLe16(it) }
        putLe16(wpX); putLe16(wpY)
        putLe16(maxLumCta); putLe16(minLumCta)
        putLe16(maxCll); putLe16(maxFall)
        return out
    }
}
