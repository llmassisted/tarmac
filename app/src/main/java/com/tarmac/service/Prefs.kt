package com.tarmac.service

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Typed accessor for Tarmac's user-tunable preferences. Backed by
 * [PreferenceManager.getDefaultSharedPreferences] so the keys here MUST match
 * the `android:key` attributes in res/xml/preferences.xml.
 *
 * Defaults intentionally favor "do nothing surprising" — let MediaCodec pick
 * the codec the client actually sent, render at 1080p60, no HDR.
 */
object Prefs {

    const val KEY_DEVICE_NAME    = "pref_device_name"
    const val KEY_FORCE_H265     = "pref_force_h265"
    const val KEY_HDR_ENABLED    = "pref_hdr_enabled"
    const val KEY_AUDIO_BUFFER   = "pref_audio_buffer_kb"

    private fun sp(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)

    fun deviceName(ctx: Context): String =
        sp(ctx).getString(KEY_DEVICE_NAME, "")?.takeIf { it.isNotBlank() } ?: ""

    fun forceH265(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_FORCE_H265, false)
    fun hdrEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_HDR_ENABLED, false)

    /** Audio buffer in kilobytes; defaults to 16 kB which matches the Phase 2 baseline. */
    fun audioBufferKb(ctx: Context): Int =
        sp(ctx).getString(KEY_AUDIO_BUFFER, "16")?.toIntOrNull() ?: 16
}
