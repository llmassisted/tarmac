package com.tarmac.media

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HevcBitstreamTest {

    @Test
    fun `empty buffer returns all nulls`() {
        val result = HevcBitstream.parse(ByteBuffer.allocate(0), 0)
        assertNull(result.widthPx)
        assertNull(result.heightPx)
        assertNull(result.hdrStaticInfo)
    }

    @Test
    fun `garbage bytes return all nulls`() {
        val junk = byteArrayOf(0x41, 0x42, 0x43, 0x44)
        val result = HevcBitstream.parse(ByteBuffer.wrap(junk), junk.size)
        assertNull(result.widthPx)
        assertNull(result.heightPx)
        assertNull(result.hdrStaticInfo)
    }

    @Test
    fun `SPS NALU yields resolution`() {
        // Minimal HEVC SPS: start code + NAL header (type 33 = SPS) + RBSP.
        // NAL header for SPS: (33 shl 1) = 0x42, second byte = 0x01
        // RBSP: sps_video_parameter_set_id(4) + max_sub_layers_minus1(3) +
        //       temporal_id_nesting_flag(1) + profile_tier_level(96 bits) +
        //       sps_seq_parameter_set_id(ue) + chroma_format_idc(ue) +
        //       pic_width(ue) + pic_height(ue)
        //
        // Build a hand-crafted SPS for 1920x1080:
        val sps = buildMinimalSps(1920, 1080)
        val nalu = byteArrayOf(0x00, 0x00, 0x00, 0x01) + sps
        val result = HevcBitstream.parse(ByteBuffer.wrap(nalu), nalu.size)
        assertEquals(1920, result.widthPx)
        assertEquals(1080, result.heightPx)
    }

    @Test
    fun `hdrStaticInfo blob is 25 bytes when SEI present`() {
        // Build a prefix SEI NALU containing MDCV (type 137) + CLL (type 144).
        val sei = buildHdrSei()
        val nalu = byteArrayOf(0x00, 0x00, 0x00, 0x01) + sei
        val result = HevcBitstream.parse(ByteBuffer.wrap(nalu), nalu.size)
        assertNotNull(result.hdrStaticInfo)
        assertEquals(25, result.hdrStaticInfo!!.size)
        assertEquals(0x00.toByte(), result.hdrStaticInfo!![0])
    }

    @Test
    fun `parse does not modify source buffer position`() {
        val data = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x42, 0x01, 0x00)
        val buf = ByteBuffer.wrap(data)
        buf.position(3)
        HevcBitstream.parse(buf, data.size)
        assertEquals(3, buf.position())
    }

    private fun buildMinimalSps(width: Int, height: Int): ByteArray {
        // NAL header: type 33 (SPS), layer_id 0, temporal_id_plus1 1
        // (33 shl 1) or 0 = 0x42, 0x01
        val header = byteArrayOf(0x42, 0x01)
        // RBSP bits: vps_id(4)=0, max_sub_layers_minus1(3)=0, temporal_nesting(1)=1,
        //   profile_tier_level: 96 bits of zeros,
        //   sps_seq_parameter_set_id: ue(0)=1 bit,
        //   chroma_format_idc: ue(1)=010,
        //   pic_width: ue(width), pic_height: ue(height)
        val bits = mutableListOf<Int>()
        // vps_id = 0 (4 bits)
        repeat(4) { bits.add(0) }
        // max_sub_layers_minus1 = 0 (3 bits)
        repeat(3) { bits.add(0) }
        // temporal_id_nesting = 1
        bits.add(1)
        // profile_tier_level: 96 bits
        repeat(96) { bits.add(0) }
        // sps_seq_parameter_set_id = ue(0) = "1"
        bits.add(1)
        // chroma_format_idc = ue(1) = "010"
        bits.add(0); bits.add(1); bits.add(0)
        // pic_width = ue(width)
        bits.addAll(encodeUe(width))
        // pic_height = ue(height)
        bits.addAll(encodeUe(height))

        // Pad to byte boundary
        while (bits.size % 8 != 0) bits.add(0)
        val rbsp = ByteArray(bits.size / 8)
        for (i in rbsp.indices) {
            var b = 0
            for (j in 0..7) {
                b = (b shl 1) or bits[i * 8 + j]
            }
            rbsp[i] = b.toByte()
        }
        return header + rbsp
    }

    private fun encodeUe(value: Int): List<Int> {
        // Exp-Golomb: (value + 1) in binary, prefixed by (bitLength - 1) zeros
        val code = value + 1
        val numBits = 32 - Integer.numberOfLeadingZeros(code)
        val bits = mutableListOf<Int>()
        repeat(numBits - 1) { bits.add(0) }
        for (i in numBits - 1 downTo 0) {
            bits.add((code shr i) and 1)
        }
        return bits
    }

    private fun buildHdrSei(): ByteArray {
        // NAL header for prefix SEI: type 39 → (39 shl 1) = 0x4E, 0x01
        val header = byteArrayOf(0x4E.toByte(), 0x01)
        // SEI payload: MDCV (type 137, size 24) + CLL (type 144, size 4)
        val mdcvType = byteArrayOf(137.toByte())
        val mdcvSize = byteArrayOf(24)
        val mdcvPayload = ByteArray(24) // zeros = valid primaries/luminance

        val cllType = byteArrayOf(144.toByte())
        val cllSize = byteArrayOf(4)
        val cllPayload = ByteArray(4)

        val trailing = byteArrayOf(0x80.toByte())
        return header + mdcvType + mdcvSize + mdcvPayload + cllType + cllSize + cllPayload + trailing
    }
}
