package com.tarmac.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.preference.PreferenceManager
import com.tarmac.MirrorActivity
import com.tarmac.R
import com.tarmac.VideoPlayerActivity
import com.tarmac.media.AudioPipeline
import com.tarmac.media.DisplayCapabilities
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

    /**
     * When the user changes device name from Settings while the service is
     * running, restart the AirPlay server so the new name is reflected in the
     * mDNS advertisement. Other prefs (codec/HDR/buffer) take effect on next
     * stream and don't require a restart.
     */
    private val prefListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Prefs.KEY_DEVICE_NAME) {
                Log.i(TAG, "device name changed — restarting AirPlay server")
                restartAirPlay()
            }
        }

    override fun onCreate() {
        super.onCreate()
        val notification = buildNotification(getString(R.string.service_running))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        PreferenceManager.getDefaultSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (bonjour == null) startAirPlay()
        return START_STICKY
    }

    private fun startAirPlay() {
        AirPlayJni.setListener(this)
        currentPin = "%04d".format(Random.nextInt(0, 10_000))
        SessionStateBus.setPin(currentPin)
        SessionStateBus.setConnection(SessionStateBus.Connection.IDLE)

        audioPipeline = AudioPipeline(applicationContext).also {
            it.start()
            AirPlayJni.audioPipeline = it
            AirPlayJni.audioSessionId = it.audioSessionId
        }

        val displayCaps = DisplayCapabilities.probe(applicationContext)
        AirPlayJni.displayCaps = displayCaps
        val deviceName = resolveDeviceName()
        SessionStateBus.setDeviceName(deviceName)
        val hwAddr = deviceHwAddr()
        val port = AirPlayJni.startServer(
            deviceName,
            hwAddr,
            FeatureBits.DEFAULT.value,
            currentPin.toInt(),
        )
        if (port < 0) {
            Log.e(TAG, "AirPlayJni.startServer returned $port — bailing")
            stopSelf()
            return
        }
        val advertisePort = if (port > 0) port else 7000

        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Tarmac:airplay").apply {
                setReferenceCounted(false)
                acquire(8 * 60 * 60 * 1000L)
            }
        }

        bonjour = BonjourAdvertiser(this).also {
            it.start(deviceName, hwAddr, advertisePort, displayCaps = displayCaps)
        }

        updateNotification(getString(R.string.service_running) + " — PIN $currentPin")
    }

    private fun stopAirPlay() {
        bonjour?.stop()
        bonjour = null
        AirPlayJni.audioPipeline = null
        AirPlayJni.videoPipeline = null
        AirPlayJni.stopServer()
        AirPlayJni.setListener(null)
        audioPipeline?.stop()
        audioPipeline = null
        SessionStateBus.setConnection(SessionStateBus.Connection.IDLE)
        SessionStateBus.clearMediaStats()
    }

    private fun restartAirPlay() {
        stopAirPlay()
        startAirPlay()
    }

    override fun onDestroy() {
        PreferenceManager.getDefaultSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(prefListener)
        stopAirPlay()
        wakeLock?.runCatching { if (isHeld) release() }
        wakeLock = null
        super.onDestroy()
    }

    // --- AirPlayJni.Listener ----------------------------------------------

    override fun onPinDisplay(pin: String) {
        currentPin = pin
        SessionStateBus.setPin(pin)
        updateNotification(getString(R.string.service_running) + " — PIN $pin")
    }

    override fun onSessionState(state: AirPlayJni.SessionState) {
        sessionState = state
        val busState = when (state) {
            AirPlayJni.SessionState.ACTIVE -> SessionStateBus.Connection.ACTIVE
            AirPlayJni.SessionState.IDLE -> SessionStateBus.Connection.IDLE
        }
        SessionStateBus.setConnection(busState)
        if (state == AirPlayJni.SessionState.IDLE) SessionStateBus.clearMediaStats()
        updateNotification(
            when (state) {
                AirPlayJni.SessionState.ACTIVE -> "Streaming from client"
                AirPlayJni.SessionState.IDLE -> getString(R.string.service_running) + " — PIN $currentPin"
            }
        )
        when (state) {
            AirPlayJni.SessionState.ACTIVE -> launchMirrorActivity()
            AirPlayJni.SessionState.IDLE -> Unit
        }
    }

    private fun launchMirrorActivity() {
        val intent = Intent(this, MirrorActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "Failed to launch MirrorActivity: ${it.message}") }
    }

    override fun onVideoPlay(url: String, startSec: Float) {
        SessionStateBus.emitVideoEvent(SessionStateBus.VideoEvent.Play(url, startSec))
        val intent = Intent(this, VideoPlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(VideoPlayerActivity.EXTRA_URL, url)
            putExtra(VideoPlayerActivity.EXTRA_START_SEC, startSec)
        }
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "Failed to launch VideoPlayerActivity: ${it.message}") }
    }

    override fun onVideoStop() {
        SessionStateBus.emitVideoEvent(SessionStateBus.VideoEvent.Stop)
    }

    override fun onVideoRate(rate: Float) {
        SessionStateBus.emitVideoEvent(SessionStateBus.VideoEvent.Rate(rate))
    }

    override fun onVideoScrub(positionSec: Float) {
        SessionStateBus.emitVideoEvent(SessionStateBus.VideoEvent.Scrub(positionSec))
    }

    // --- helpers ----------------------------------------------------------

    /**
     * Settings → Identity → Device name wins; otherwise fall back to the
     * system Settings.Global.DEVICE_NAME (e.g. "Living Room TV"); finally
     * the static [DEFAULT_DEVICE_NAME].
     */
    private fun resolveDeviceName(): String {
        Prefs.deviceName(this).takeIf { it.isNotBlank() }?.let { return it }
        val sys = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
        return if (sys.isNullOrBlank()) DEFAULT_DEVICE_NAME else sys
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
