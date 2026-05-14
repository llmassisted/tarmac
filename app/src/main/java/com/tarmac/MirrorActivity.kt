package com.tarmac

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tarmac.media.AudioPipeline
import com.tarmac.media.VideoPipeline
import com.tarmac.service.AirPlayJni
import com.tarmac.service.SessionStateBus
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

class MirrorActivity : FragmentActivity(), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView
    private lateinit var debugOverlay: TextView
    private var pipeline: VideoPipeline? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastRendered = 0L
    private var stalledSince: Long? = null
    @Volatile private var lastEvent = "init"

    private val debugTick = object : Runnable {
        override fun run() {
            val p = pipeline
            if (p != null) {
                val s = p.stats()
                val rendered = s.totalRenderedFrames
                val now = android.os.SystemClock.elapsedRealtime()
                if (rendered == lastRendered && lastRendered > 0) {
                    if (stalledSince == null) stalledSince = now
                } else {
                    stalledSince = null
                }
                lastRendered = rendered
                val stallMs = stalledSince?.let { now - it }
                val stallLabel = if (stallMs != null) " STALL ${stallMs}ms" else ""
                val audioLine = AirPlayJni.audioPipeline?.stats()?.let { a ->
                    "\naudio:${a.codecLabel} in:${a.totalFramesIn} pcm:${a.totalPcmBytesOut} err:${a.totalDecoderErrors}"
                } ?: "\naudio: none"
                debugOverlay.text = "${s.mime} ${s.width}x${s.height}" +
                    "\nin:${s.totalSubmits} out:${rendered} drop:${s.totalDroppedFrames} err:${s.totalDecoderErrors}" +
                    "\nhdr:${s.hdrActive}$stallLabel" +
                    audioLine +
                    "\n$lastEvent"
            } else {
                debugOverlay.text = "pipeline: null\n$lastEvent"
            }
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_mirror)
        surfaceView = findViewById(R.id.mirror_surface)
        debugOverlay = findViewById(R.id.debug_overlay)
        surfaceView.holder.addCallback(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SessionStateBus.state
                    .distinctUntilChangedBy { it.connection }
                    .collect { snap ->
                        if (snap.connection == SessionStateBus.Connection.IDLE && !isFinishing) {
                            lastEvent = "session->IDLE (finishing)"
                            debugTick.run()
                            handler.postDelayed({ if (!isFinishing) finish() }, 3000)
                        }
                    }
            }
        }

        handler.post(debugTick)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        lastEvent = "surfaceCreated"
        val p = VideoPipeline(
            holder.surface,
            applicationContext,
            AirPlayJni.audioSessionId,
            AirPlayJni.displayCaps,
        ).also {
            it.onFatalError = { t ->
                lastEvent = "FATAL: ${t.message}"
                SessionStateBus.reportPipelineFault("video")
            }
            it.start()
        }
        pipeline = p
        AirPlayJni.videoPipeline = p
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        lastEvent = "surfaceDestroyed"
        AirPlayJni.videoPipeline = null
        pipeline?.stop()
        pipeline = null
    }
}
