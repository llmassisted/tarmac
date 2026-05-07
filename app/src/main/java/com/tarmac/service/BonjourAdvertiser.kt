package com.tarmac.service

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.tarmac.media.DisplayCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Locale
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Named, composable feature bits for the AirPlay `ft` / `features` TXT record.
 *
 * Bit positions follow Apple's private AirPlay specification; the three verified
 * names below were cross-referenced from UxPlay's dnssdint.h and open-source
 * AirPlay implementations.  All other bits in [DEFAULT] are present in UxPlay
 * v1.73's advertisement but are not yet individually named.
 */
class FeatureBits(val value: Long) {
    operator fun plus(other: FeatureBits) = FeatureBits(value or other.value)
    operator fun minus(other: FeatureBits) = FeatureBits(value and other.value.inv())

    companion object {
        val AIRPLAY_SCREEN          = FeatureBits(1L shl 7)   // screen mirroring
        val SCREEN_SEPARATE_DISPLAY = FeatureBits(1L shl 14)  // extended-display routing
        val LEGACY_PAIRING          = FeatureBits(1L shl 27)  // HomeKit pairing compat

        // ── Candidate 4K / HDR bits ────────────────────────────────────────────
        // Bit positions are UNVERIFIED.  Enable only after confirming via Mac-side
        // packet capture that the receiver negotiates the expected resolution/HDR.
        // val CANDIDATE_VIDEO_4K  = FeatureBits(1L shl 17)
        // val CANDIDATE_HDR10     = FeatureBits(1L shl 38)

        val NONE = FeatureBits(0L)

        /** Baseline matching UxPlay v1.73's default advertisement. */
        val DEFAULT = FeatureBits(0x5A7FFEE6L)
    }
}

/**
 * Advertises Tarmac as an AirPlay receiver via JmDNS.
 *
 * We previously used Android's NsdManager but Hisense's Android 9 ROM (and
 * other vendor builds) silently drop user-app service announcements: NsdManager
 * reports SERVICE_REGISTERED but the system mDNS daemon never emits packets to
 * the wire. JmDNS opens its own multicast socket on 5353 and announces
 * services itself, bypassing the broken vendor daemon. This matches what
 * AirReceiver and other Android AirPlay receivers do for the same reason.
 *
 * Two services are registered, exactly like UxPlay:
 *   - `_raop._tcp`  — service name format "<HW_HEX>@<DeviceName>"
 *   - `_airplay._tcp` — service name = device name
 *
 * The MulticastLock is still needed: Android suppresses inbound multicast for
 * power reasons by default, so without the lock JmDNS would not see incoming
 * mDNS queries from clients.
 */
class BonjourAdvertiser(private val context: Context) {

    companion object {
        private const val TAG = "BonjourAdvertiser"

        private const val MODEL = "AppleTV3,2"
        private const val SRC_VERSION = "220.68"
        private const val AIRPLAY_PI = "2e388006-13ba-4041-9a67-25dd4a43d536"

        private const val RAOP_TYPE = "_raop._tcp.local."
        private const val AIRPLAY_TYPE = "_airplay._tcp.local."
    }

    private val wifi: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var jmdns: JmDNS? = null

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

        val bindAddr = pickBindAddress()
        if (bindAddr == null) {
            Log.e(TAG, "no usable IPv4 interface — cannot advertise")
            return
        }

        // When candidate 4K/HDR bit positions are confirmed via Mac-side probe,
        // un-comment the additions below to advertise capability-conditioned bits.
        val effective = features

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
            "advertise via JmDNS on $bindAddr features=0x${effective.value.toString(16)} " +
                "displayHdr10=${displayCaps.supportsHdr10} display4k=${displayCaps.supports4k}",
        )

        val instance = runCatching { JmDNS.create(bindAddr, deviceName) }
            .onFailure { Log.e(TAG, "JmDNS.create failed: ${it.message}", it) }
            .getOrNull() ?: return
        jmdns = instance

        val raopProps = linkedMapOf(
            "txtvers" to "1",
            "ch" to "2",
            "cn" to "0,1,2,3",
            "et" to "0,3,5",
            "vv" to "2",
            "ft" to featuresStr,
            "am" to MODEL,
            "md" to "0,1,2",
            "rhd" to "5.6.0.0",
            "sf" to if (pinRequired) "0x8c" else "0x4",
            "pw" to if (pinRequired) "true" else "false",
            "sr" to "44100",
            "ss" to "16",
            "sv" to "false",
            "da" to "true",
            "vs" to SRC_VERSION,
            "vn" to "65537",
            "tp" to "UDP",
            "deviceid" to deviceId,
        )
        val airplayProps = linkedMapOf(
            "deviceid" to deviceId,
            "features" to featuresStr,
            "flags" to "0x4",
            "model" to MODEL,
            "pi" to AIRPLAY_PI,
            "srcvers" to SRC_VERSION,
            "vv" to "2",
            "pw" to if (pinRequired) "true" else "false",
        )

        runCatching {
            instance.registerService(
                ServiceInfo.create(RAOP_TYPE, raopName, port, 0, 0, raopProps)
            )
            Log.i(TAG, "raop registered: $raopName on $port")
        }.onFailure { Log.e(TAG, "raop register failed: ${it.message}", it) }

        runCatching {
            instance.registerService(
                ServiceInfo.create(AIRPLAY_TYPE, deviceName, port, 0, 0, airplayProps)
            )
            Log.i(TAG, "airplay registered: $deviceName on $port")
        }.onFailure { Log.e(TAG, "airplay register failed: ${it.message}", it) }
    }

    fun stop() {
        runCatching {
            jmdns?.unregisterAllServices()
            jmdns?.close()
        }.onFailure { Log.w(TAG, "JmDNS close failed: ${it.message}") }
        jmdns = null
        multicastLock?.runCatching { release() }
        multicastLock = null
    }

    /**
     * JmDNS binds its multicast socket to a single interface address. Pick the
     * first non-loopback, non-link-local IPv4 address on an "up" interface,
     * preferring Wi-Fi (`wlan*`) over Ethernet (`eth*`) over anything else.
     */
    private fun pickBindAddress(): InetAddress? {
        val candidates = mutableListOf<Pair<Int, InetAddress>>()
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback || nif.isVirtual) continue
                val name = nif.name ?: ""
                val priority = when {
                    name.startsWith("wlan") -> 0
                    name.startsWith("eth")  -> 1
                    else                    -> 2
                }
                for (a in nif.inetAddresses) {
                    if (a.isLoopbackAddress || a.isLinkLocalAddress) continue
                    if (a is Inet4Address) candidates += priority to a
                }
            }
        }.onFailure { Log.w(TAG, "interface enumeration failed: ${it.message}") }
        return candidates.minByOrNull { it.first }?.second
    }
}
