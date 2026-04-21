package com.tarmac.service

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.tarmac.media.DisplayCapabilities
import java.util.Locale

/**
 * Named, composable feature bits for the AirPlay `ft` / `features` TXT record.
 *
 * Bit positions follow Apple's private AirPlay specification; the three verified
 * names below were cross-referenced from UxPlay's dnssdint.h and open-source
 * AirPlay implementations.  All other bits in [DEFAULT] are present in UxPlay
 * v1.73's advertisement but are not yet individually named.
 *
 * Candidate 4K and HDR bits are declared in comments below — their exact
 * positions require Mac-side Wireshark/LLDB capture to confirm before we
 * advertise them.
 */
class FeatureBits(val value: Long) {
    operator fun plus(other: FeatureBits) = FeatureBits(value or other.value)
    operator fun minus(other: FeatureBits) = FeatureBits(value and other.value.inv())

    companion object {
        // Verified bit positions (UxPlay dnssdint.h + open-source AirPlay specs).
        val AIRPLAY_SCREEN          = FeatureBits(1L shl 7)   // screen mirroring
        val SCREEN_SEPARATE_DISPLAY = FeatureBits(1L shl 14)  // extended-display routing
        val LEGACY_PAIRING          = FeatureBits(1L shl 27)  // HomeKit pairing compat

        // ── Candidate 4K / HDR bits ────────────────────────────────────────────
        // Bit positions are UNVERIFIED.  Enable only after confirming via Mac-side
        // packet capture that the receiver negotiates the expected resolution/HDR.
        // Source: AirPlay2 feature flag survey in open-source implementations;
        //         needs Wireshark cross-check against a real Apple TV 4K packet.
        //
        // val CANDIDATE_VIDEO_4K  = FeatureBits(1L shl 17) // unverified
        // val CANDIDATE_HDR10     = FeatureBits(1L shl 38) // unverified; upper-word bit
        // ──────────────────────────────────────────────────────────────────────

        /** Zero-value sentinel for additive composition. */
        val NONE = FeatureBits(0L)

        /** Baseline matching UxPlay v1.73's default advertisement. */
        val DEFAULT = FeatureBits(0x5A7FFEE6L)
    }
}

/**
 * Advertises Tarmac as an AirPlay receiver via Android's NsdManager.
 *
 * UxPlay normally calls dnssd_register_raop / dnssd_register_airplay (see
 * native/libairplay/lib/dnssd.c). On Android we use NsdManager instead so we
 * don't need a vendored mDNS responder, and so the OS can suppress duplicate
 * advertisements when the app is backgrounded.
 *
 * Two services are registered, exactly like UxPlay:
 *   - `_raop._tcp`  — service name format "<HW_HEX>@<DeviceName>"
 *   - `_airplay._tcp` — service name = device name
 *
 * TXT-record keys mirror dnssd.c (RAOP_*, AIRPLAY_*) so a real Mac will
 * negotiate without surprises. The full set is intentionally close to
 * what UxPlay v1.73 sends.
 */
class BonjourAdvertiser(private val context: Context) {

    companion object {
        private const val TAG = "BonjourAdvertiser"

        // Mirrors GLOBAL_MODEL / GLOBAL_VERSION / *_PI from libairplay headers.
        private const val MODEL = "AppleTV3,2"
        private const val SRC_VERSION = "220.68"
        private const val AIRPLAY_PI = "2e388006-13ba-4041-9a67-25dd4a43d536"
    }

    private val nsd: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var raopListener: NsdManager.RegistrationListener? = null
    private var airplayListener: NsdManager.RegistrationListener? = null

    /**
     * Register both `_raop._tcp` and `_airplay._tcp` services on [port].
     *
     * [displayCaps] is used to gate candidate 4K/HDR feature bits once their
     * exact bit positions are confirmed via Mac-side probe (see [FeatureBits]).
     *
     * @param deviceName user-visible name shown in the AirPlay menu
     * @param hwAddr 6-byte MAC-style identifier; typically a hash of the
     *               device's actual hardware ID
     * @param features composable feature bitmask (default [FeatureBits.DEFAULT])
     * @param displayCaps probed display capabilities; reserved for future bit gating
     * @param pinRequired true if the receiver will display an on-screen PIN
     */
    fun start(
        deviceName: String,
        hwAddr: ByteArray,
        port: Int,
        features: FeatureBits = FeatureBits.DEFAULT,
        displayCaps: DisplayCapabilities = DisplayCapabilities(supportsHdr10 = false, supports4k = false),
        pinRequired: Boolean = true,
    ) {
        require(hwAddr.size == 6) { "hwAddr must be 6 bytes (MAC-style)" }
        if (multicastLock == null) {
            multicastLock = wifi.createMulticastLock(TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        // When candidate 4K/HDR bit positions are confirmed via Mac-side probe,
        // un-comment the additions below to advertise capability-conditioned bits.
        val effective = features
        //  + (if (displayCaps.supports4k)    FeatureBits.CANDIDATE_VIDEO_4K  else FeatureBits.NONE)
        //  + (if (displayCaps.supportsHdr10) FeatureBits.CANDIDATE_HDR10     else FeatureBits.NONE)

        val deviceId = hwAddr.joinToString(":") { "%02X".format(it) }
        val raopName = "${hwAddr.joinToString("") { "%02X".format(it) }}@$deviceName"
        val featuresStr = String.format(
            Locale.ROOT,
            "0x%X,0x%X",
            effective.value and 0xFFFFFFFFL,
            (effective.value ushr 32) and 0xFFFFFFFFL,
        )

        Log.d(
            TAG,
            "advertise features=0x${effective.value.toString(16)} " +
                "displayHdr10=${displayCaps.supportsHdr10} display4k=${displayCaps.supports4k}",
        )

        registerRaop(raopName, port, deviceId, featuresStr, pinRequired)
        registerAirplay(deviceName, port, deviceId, featuresStr, pinRequired)
    }

    fun stop() {
        raopListener?.let { runCatching { nsd.unregisterService(it) } }
        airplayListener?.let { runCatching { nsd.unregisterService(it) } }
        raopListener = null
        airplayListener = null
        multicastLock?.runCatching { release() }
        multicastLock = null
    }

    private fun registerRaop(
        serviceName: String,
        port: Int,
        deviceId: String,
        featuresStr: String,
        pinRequired: Boolean,
    ) {
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = "_raop._tcp."
            this.port = port
            // From dnssd.c: keys/values match UxPlay's RAOP TXT record.
            setAttribute("txtvers", "1")
            setAttribute("ch", "2")            // 2 audio channels
            setAttribute("cn", "0,1,2,3")      // PCM, ALAC, AAC, AAC-ELD
            setAttribute("et", "0,3,5")        // Encryption: None, FairPlay, FairPlay SAPv2.5
            setAttribute("vv", "2")
            setAttribute("ft", featuresStr)
            setAttribute("am", MODEL)
            setAttribute("md", "0,1,2")        // text, artwork, progress
            setAttribute("rhd", "5.6.0.0")
            setAttribute("sf", if (pinRequired) "0x8c" else "0x4")
            setAttribute("pw", if (pinRequired) "true" else "false")
            setAttribute("sr", "44100")
            setAttribute("ss", "16")
            setAttribute("sv", "false")
            setAttribute("da", "true")
            setAttribute("vs", SRC_VERSION)
            setAttribute("vn", "65537")
            setAttribute("tp", "UDP")
            setAttribute("deviceid", deviceId)
        }
        raopListener = registrationListener("raop")
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, raopListener)
    }

    private fun registerAirplay(
        deviceName: String,
        port: Int,
        deviceId: String,
        featuresStr: String,
        pinRequired: Boolean,
    ) {
        val info = NsdServiceInfo().apply {
            this.serviceName = deviceName
            this.serviceType = "_airplay._tcp."
            this.port = port
            setAttribute("deviceid", deviceId)
            setAttribute("features", featuresStr)
            setAttribute("flags", "0x4")
            setAttribute("model", MODEL)
            setAttribute("pi", AIRPLAY_PI)
            setAttribute("srcvers", SRC_VERSION)
            setAttribute("vv", "2")
            setAttribute("pw", if (pinRequired) "true" else "false")
        }
        airplayListener = registrationListener("airplay")
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, airplayListener)
    }

    private fun registrationListener(label: String) = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            Log.i(TAG, "$label registered: ${serviceInfo.serviceName} on ${serviceInfo.port}")
        }
        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "$label registration failed code=$errorCode")
        }
        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            Log.i(TAG, "$label unregistered: ${serviceInfo.serviceName}")
        }
        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "$label unregistration failed code=$errorCode")
        }
    }
}
