package com.tarmac.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FeatureBitsTest {

    @Test
    fun `DEFAULT matches UxPlay v1_73 baseline`() {
        assertEquals(0x5A7FFEE6L, FeatureBits.DEFAULT.value)
    }

    @Test
    fun `NONE is zero`() {
        assertEquals(0L, FeatureBits.NONE.value)
    }

    @Test
    fun `plus combines bits`() {
        val combined = FeatureBits.AIRPLAY_SCREEN + FeatureBits.LEGACY_PAIRING
        val expected = (1L shl 7) or (1L shl 27)
        assertEquals(expected, combined.value)
    }

    @Test
    fun `minus removes bits`() {
        val base = FeatureBits.DEFAULT
        val stripped = base - FeatureBits.LEGACY_PAIRING
        assertEquals(0L, stripped.value and (1L shl 27))
        assertNotEquals(base.value, stripped.value)
    }

    @Test
    fun `plus is idempotent`() {
        val once = FeatureBits.DEFAULT + FeatureBits.AIRPLAY_SCREEN
        val twice = once + FeatureBits.AIRPLAY_SCREEN
        assertEquals(once.value, twice.value)
    }

    @Test
    fun `minus of absent bit is no-op`() {
        val base = FeatureBits.NONE
        val result = base - FeatureBits.AIRPLAY_SCREEN
        assertEquals(0L, result.value)
    }

    @Test
    fun `SCREEN_SEPARATE_DISPLAY is bit 14`() {
        assertEquals(1L shl 14, FeatureBits.SCREEN_SEPARATE_DISPLAY.value)
    }
}
