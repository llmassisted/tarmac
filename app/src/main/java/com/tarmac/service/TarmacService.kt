package com.tarmac.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.FileDescriptor
import java.io.PrintWriter
import java.io.StringWriter
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.tarmac.MirrorActivity
import com.tarmac.R
import com.tarmac.VideoPlayerActivity
import com.tarmac.media.AudioPipeline
import com.tarmac.media.DisplayCapabilities
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TarmacService : LifecycleService(), AirPlayJni.Listener {

    companion object {
        private const val TAG = "TarmacService"
        private const val CHANNEL_ID = "tarmac_service"
        private const val NOTIFICATION_ID = 1
        private const val DEFAULT_DEVICE_NAME = "Tarmac"

        /**
         * Upper bound on a single streaming session. Long enough for a feature-
         * length movie; short enough that a runaway bug can't hold the CPU
         * awake indefinitely. Reset on every ACTIVE transition.
         */
        private const val WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L

        /**
         * Debounce window for network-change storms (e.g. Wi-Fi roam emits
         * onLost + onAvailable in rapid succession). Coalesce into one
         * re-advertise so we don't thrash NsdManager.
         */
        private const val NET_CHANGE_DEBOUNCE_MS = 750L

        /**
         * Debug-only broadcast that prints the same diagnostics as
         * `dumpsys activity service com.tarmac/.service.TarmacService` to
         * logcat. Registered NOT_EXPORTED; fire with:
         *   adb shell am broadcast -p com.tarmac -a com.tarmac.action.DUMP_STATS
         */
        const val ACTION_DUMP_STATS = "com.tarmac.action.DUMP_STATS"
    }

    // wakeLock is touched from both the JNI callback thread (onSessionState)
    // and the main thread (onDestroy). @Volatile gives us the visibility we
    // need; the acquire/release calls are idempotent so torn writes aren't a
    // concern, but without @Volatile a racing session start could observe a
    // null and create a second WakeLock.
    @Volatile private var wakeLock: PowerManager.WakeLock? = null
    private var bonjour: BonjourAdvertiser? = null
    private var audioPipeline: AudioPipeline? = null
    @Volatile private var currentPin: String = ""
    @Volatile private var sessionState: AirPlayJni.SessionState = AirPlayJni.SessionState.IDLE

    private val mainHandler = Handler(Looper.getMainLooper())
    private var connMgr: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Parameters of the last successful Bonjour advertisement, stashed so
     * [reregisterBonjour] can restart it verbatim after a network change.
     */
    private data class BonjourArgs(
        val deviceName: String,
        val hwAddr: ByteArray,
        val port: Int,
        val displayCaps: DisplayCapabilities,
    )
    @Volatile private var lastBonjourArgs: BonjourArgs? = null

    private val reregisterBonjourTask = Runnable {
        // Hop off the main thread for the actual NsdManager IPC — StrictMode's
        // detectNetwork() would otherwise flag this. The debounce itself runs
        // on main so the remove+post pair is race-free.
        lifecycleScope.launch { withContext(Dispatchers.IO) { reregisterBonjour() } }
    }

    @Volatile private var serviceStartElapsedMs: Long = 0L
    @Volatile private var sessionActiveStartElapsedMs: Long = 0L
    @Volatile private var totalSessionActivations: Long = 0L
    private var dumpReceiver: BroadcastReceiver? = null

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
        serviceStartElapsedMs = SystemClock.elapsedRealtime()
        registerDumpReceiverIfDebug()
        lifecycleScope.launch {
            SessionStateBus.pipelineFaults.collect { source ->
                Log.w(TAG, "pipeline fault from $source — restarting session")
                restartAirPlay()
            }
        }
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

    @Synchronized
    private fun startAirPlay() {
        AirPlayJni.setListener(this)
        currentPin = "%04d".format(Random.nextInt(0, 10_000))
        SessionStateBus.setPin(currentPin)
        SessionStateBus.setConnection(SessionStateBus.Connection.IDLE)

        audioPipeline = AudioPipeline(applicationContext).also {
            it.onFatalError = { SessionStateBus.reportPipelineFault("audio") }
            it.start()
            AirPlayJni.audioPipeline = it
            AirPlayJni.audioSessionId = it.audioSessionId
        }

        val displayCaps = DisplayCapabilities.probe(applicationContext)
        AirPlayJni.displayCaps = displayCaps
        val deviceName = resolveDeviceName()
        SessionStateBus.setDeviceName(deviceName)
        val hwAddr = deviceHwAddr()
        val maxW = if (displayCaps.supports4k) 3840 else 1920
        val maxH = if (displayCaps.supports4k) 2160 else 1080
        val port = AirPlayJni.startServer(
            deviceName,
            hwAddr,
            FeatureBits.DEFAULT.value,
            currentPin.toInt(),
            maxW,
            maxH,
        )
        if (port < 0) {
            Log.e(TAG, "AirPlayJni.startServer returned $port — bailing")
            stopSelf()
            return
        }
        val advertisePort = if (port > 0) port else 7000

        val args = BonjourArgs(deviceName, hwAddr, advertisePort, displayCaps)
        lastBonjourArgs = args
        bonjour = BonjourAdvertiser(this).also {
            it.start(args.deviceName, args.hwAddr, args.port, displayCaps = args.displayCaps)
        }
        registerNetworkCallback()

        updateNotification(getString(R.string.service_running) + " — PIN $currentPin")
    }

    @Synchronized
    private fun stopAirPlay() {
        unregisterNetworkCallback()
        lastBonjourArgs = null
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
        dumpReceiver?.let { runCatching { unregisterReceiver(it) } }
        dumpReceiver = null
        // Belt-and-suspenders: stopAirPlay() already clears this, but if the
        // service is destroyed via a path that skips it, a queued re-advertise
        // could otherwise fire after teardown.
        mainHandler.removeCallbacks(reregisterBonjourTask)
        stopAirPlay()
        releaseSessionWakeLock()
        wakeLock = null
        super.onDestroy()
    }

    override fun dump(fd: FileDescriptor?, writer: PrintWriter, args: Array<out String>?) {
        writeDiagnostics(writer)
    }

    /**
     * Register a NOT_EXPORTED receiver so a developer can request a stats dump
     * to logcat without hooking `dumpsys`. No-op on non-debuggable builds.
     */
    private fun registerDumpReceiverIfDebug() {
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ACTION_DUMP_STATS) return
                val buf = StringWriter()
                writeDiagnostics(PrintWriter(buf))
                buf.toString().lineSequence().forEach { Log.i(TAG, it) }
            }
        }
        val filter = IntentFilter(ACTION_DUMP_STATS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(rcv, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(rcv, filter)
        }
        dumpReceiver = rcv
        Log.i(TAG, "debug dump receiver registered ($ACTION_DUMP_STATS)")
    }

    private fun writeDiagnostics(w: PrintWriter) {
        val now = SystemClock.elapsedRealtime()
        val uptimeMs = now - serviceStartElapsedMs
        val sessionMs = if (sessionActiveStartElapsedMs > 0L) now - sessionActiveStartElapsedMs else 0L
        w.println("Tarmac service diagnostics")
        w.println("  uptime              : ${formatDuration(uptimeMs)}")
        w.println("  session             : $sessionState ($totalSessionActivations total)")
        w.println("  current session for : ${formatDuration(sessionMs)}")
        w.println("  wakelock held       : ${wakeLock?.isHeld == true}")
        w.println("  bonjour advertising : ${bonjour != null}")
        w.println("  network callback    : ${networkCallback != null}")
        w.println("  device name / pin   : ${lastBonjourArgs?.deviceName} / $currentPin")
        val caps = AirPlayJni.displayCaps
        w.println("  display caps        : 4k=${caps.supports4k} hdr10=${caps.supportsHdr10}")

        AirPlayJni.videoPipeline?.stats()?.let { s ->
            w.println("Video pipeline")
            w.println("  mime                : ${s.mime}")
            w.println("  size                : ${s.width}x${s.height}")
            w.println("  hdr active          : ${s.hdrActive}")
            w.println("  submits             : ${s.totalSubmits}")
            w.println("  rendered frames     : ${s.totalRenderedFrames}")
            w.println("  decoder errors      : ${s.totalDecoderErrors}")
        } ?: w.println("Video pipeline      : (inactive)")

        AirPlayJni.audioPipeline?.stats()?.let { s ->
            w.println("Audio pipeline")
            w.println("  codec               : ${s.codecLabel}")
            w.println("  audio session id    : ${s.audioSessionId}")
            w.println("  frames in           : ${s.totalFramesIn}")
            w.println("  pcm bytes out       : ${s.totalPcmBytesOut}")
            w.println("  underruns           : ${s.underrunCount}")
            w.println("  decoder errors      : ${s.totalDecoderErrors}")
        } ?: w.println("Audio pipeline      : (inactive)")
        w.flush()
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "0s"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return buildString {
            if (h > 0) append("${h}h ")
            if (h > 0 || m > 0) append("${m}m ")
            append("${s}s")
        }
    }

    /**
     * Hold a PARTIAL_WAKE_LOCK only while a client is actively streaming.
     * The foreground service keeps the process alive regardless; this lock
     * prevents the CPU from sleeping mid-frame. Acquired on session ACTIVE,
     * released on IDLE so the TV can doze between sessions.
     */
    private fun acquireSessionWakeLock() {
        val lock = wakeLock ?: run {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Tarmac:session").also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
        }
        if (!lock.isHeld) lock.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    private fun releaseSessionWakeLock() {
        wakeLock?.runCatching { if (isHeld) release() }
    }

    /**
     * Listen for Wi-Fi drops / roams / TV wake-from-standby network events so
     * the Bonjour advertisement follows the active interface. NsdManager
     * otherwise keeps the old registration bound to a disappeared netif, and
     * Mac clients see a stale service record until the OS times it out.
     *
     * The native RAOP/HTTP listener is bound to 0.0.0.0 so it already accepts
     * connections on any interface; only the mDNS advertisement needs restart.
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = (connMgr ?: (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager))
            .also { connMgr = it }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { scheduleReregister("onAvailable") }
            override fun onLost(network: Network) { scheduleReregister("onLost") }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                scheduleReregister("onCapabilitiesChanged")
            }
        }
        runCatching { cm.registerNetworkCallback(request, cb) }
            .onSuccess {
                networkCallback = cb
                Log.i(TAG, "network callback registered")
            }
            .onFailure { Log.w(TAG, "registerNetworkCallback failed: ${it.message}") }
    }

    private fun unregisterNetworkCallback() {
        mainHandler.removeCallbacks(reregisterBonjourTask)
        val cb = networkCallback ?: return
        runCatching { connMgr?.unregisterNetworkCallback(cb) }
        networkCallback = null
    }

    private fun scheduleReregister(source: String) {
        Log.d(TAG, "network change ($source) — coalescing re-advertise")
        mainHandler.removeCallbacks(reregisterBonjourTask)
        mainHandler.postDelayed(reregisterBonjourTask, NET_CHANGE_DEBOUNCE_MS)
    }

    @Synchronized
    private fun reregisterBonjour() {
        // Runs on Dispatchers.IO. @Synchronized (shared with startAirPlay /
        // stopAirPlay) serializes us against a concurrent teardown or start so
        // we don't stomp on a fresh advertisement or resurrect one after
        // stopAirPlay cleared lastBonjourArgs. The lock is on `this`; the
        // three functions form a single critical section.
        val args = lastBonjourArgs ?: return
        Log.i(TAG, "re-advertising Bonjour after network change")
        bonjour?.stop()
        bonjour = BonjourAdvertiser(this).also {
            runCatching {
                it.start(args.deviceName, args.hwAddr, args.port, displayCaps = args.displayCaps)
            }.onFailure { t -> Log.w(TAG, "Bonjour re-advertise failed: ${t.message}") }
        }
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
        when (state) {
            AirPlayJni.SessionState.ACTIVE -> {
                acquireSessionWakeLock()
                sessionActiveStartElapsedMs = SystemClock.elapsedRealtime()
                totalSessionActivations += 1
            }
            AirPlayJni.SessionState.IDLE -> {
                releaseSessionWakeLock()
                sessionActiveStartElapsedMs = 0L
            }
        }
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
        val tapIntent = Intent(this, com.tarmac.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingTap = PendingIntent.getActivity(
            this, 0, tapIntent, PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingTap)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
