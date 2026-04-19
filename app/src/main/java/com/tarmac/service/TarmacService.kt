package com.tarmac.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.tarmac.R
import com.tarmac.media.AudioPipeline
import kotlin.random.Random

class TarmacService : LifecycleService(), AirPlayJni.Listener {

    companion object {
        private const val TAG = "TarmacService"
        private const val CHANNEL_ID = "tarmac_service"
        private const val NOTIFICATION_ID = 1
        private const val DEFAULT_DEVICE_NAME = "Tarmac"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var bonjour: BonjourAdvertiser? = null
    private var audioPipeline: AudioPipeline? = null
    @Volatile private var currentPin: String = ""
    @Volatile private var sessionState: AirPlayJni.SessionState = AirPlayJni.SessionState.IDLE

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.service_running)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (bonjour != null) return START_STICKY  // already running

        AirPlayJni.setListener(this)
        currentPin = "%04d".format(Random.nextInt(0, 10_000))

        // Audio pipeline starts immediately; video pipeline binds when
        // MirrorActivity hands its Surface to AirPlayJni.
        audioPipeline = AudioPipeline().also {
            it.start()
            AirPlayJni.audioPipeline = it
        }

        val deviceName = resolveDeviceName()
        val port = AirPlayJni.startServer(
            deviceName,
            BonjourAdvertiser.FEATURES_DEFAULT,
            currentPin.toInt(),
        )
        if (port < 0) {
            Log.e(TAG, "AirPlayJni.startServer returned $port — bailing")
            stopSelf()
            return START_NOT_STICKY
        }
        // When libairplay is stubbed the native side returns 0; advertise
        // 7000 (the conventional AirPlay control port) so Bonjour wiring is
        // still exercised end-to-end.
        val advertisePort = if (port > 0) port else 7000

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Tarmac:airplay").apply {
            setReferenceCounted(false)
            acquire(8 * 60 * 60 * 1000L)
        }

        bonjour = BonjourAdvertiser(this).also {
            it.start(deviceName, deviceHwAddr(), advertisePort)
        }

        updateNotification(getString(R.string.service_running) + " — PIN $currentPin")
        return START_STICKY
    }

    override fun onDestroy() {
        bonjour?.stop()
        bonjour = null
        AirPlayJni.audioPipeline = null
        AirPlayJni.videoPipeline = null
        AirPlayJni.stopServer()
        AirPlayJni.setListener(null)
        audioPipeline?.stop()
        audioPipeline = null
        wakeLock?.runCatching { if (isHeld) release() }
        wakeLock = null
        super.onDestroy()
    }

    // --- AirPlayJni.Listener ----------------------------------------------

    override fun onPinDisplay(pin: String) {
        currentPin = pin
        updateNotification(getString(R.string.service_running) + " — PIN $pin")
    }

    override fun onSessionState(state: AirPlayJni.SessionState) {
        sessionState = state
        // MirrorActivity launching is wired in MainFragment / a session bus in
        // a follow-up commit. For now the notification reflects the state so
        // QA can see the handshake completed.
        updateNotification(
            when (state) {
                AirPlayJni.SessionState.ACTIVE -> "Streaming from client"
                AirPlayJni.SessionState.IDLE -> getString(R.string.service_running) + " — PIN $currentPin"
            }
        )
    }

    // --- helpers ----------------------------------------------------------

    private fun resolveDeviceName(): String {
        val n = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
        return if (n.isNullOrBlank()) DEFAULT_DEVICE_NAME else n
    }

    /**
     * Stable 6-byte identifier used as the AirPlay receiver "MAC". Derived
     * from ANDROID_ID so it survives reboots but doesn't leak the real MAC.
     */
    private fun deviceHwAddr(): ByteArray {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: "00000000"
        val md = java.security.MessageDigest.getInstance("SHA-1")
        val digest = md.digest(androidId.toByteArray())
        return digest.copyOfRange(0, 6).also {
            // Set locally-administered + unicast bit pattern.
            it[0] = ((it[0].toInt() and 0xFE) or 0x02).toByte()
        }
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            nm.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
