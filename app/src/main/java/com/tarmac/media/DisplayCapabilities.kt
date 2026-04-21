package com.tarmac.media

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display

/**
 * Snapshot of display hardware capabilities used both to gate HDR/4K codec
 * configuration in [VideoPipeline] and to inform Bonjour feature-bit
 * advertisement in [com.tarmac.service.BonjourAdvertiser].
 */
data class DisplayCapabilities(
    val supportsHdr10: Boolean,
    val supports4k: Boolean,
) {
    companion object {
        private const val UHD_LONG = 3840
        private const val UHD_SHORT = 2160

        fun probe(ctx: Context?): DisplayCapabilities {
            if (ctx == null) return DisplayCapabilities(supportsHdr10 = false, supports4k = false)
            val dm = runCatching {
                ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            }.getOrNull() ?: return DisplayCapabilities(supportsHdr10 = false, supports4k = false)
            val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
                ?: return DisplayCapabilities(supportsHdr10 = false, supports4k = false)

            val supportsHdr10 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // `supportedHdrTypes` deprecated on API 34+ but needed for minSdk 26 compat.
                @Suppress("DEPRECATION")
                display.hdrCapabilities?.supportedHdrTypes?.any {
                    it == Display.HdrCapabilities.HDR_TYPE_HDR10
                } == true
            } else {
                false
            }

            val supports4k = display.supportedModes.any { mode ->
                (mode.physicalWidth >= UHD_LONG && mode.physicalHeight >= UHD_SHORT) ||
                    (mode.physicalWidth >= UHD_SHORT && mode.physicalHeight >= UHD_LONG)
            }

            return DisplayCapabilities(supportsHdr10 = supportsHdr10, supports4k = supports4k)
        }
    }
}
