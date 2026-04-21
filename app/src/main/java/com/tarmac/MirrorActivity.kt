package com.tarmac

import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tarmac.media.VideoPipeline
import com.tarmac.service.AirPlayJni
import com.tarmac.service.SessionStateBus
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

class MirrorActivity : FragmentActivity(), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView
    private var pipeline: VideoPipeline? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_mirror)
        surfaceView = findViewById(R.id.mirror_surface)
        surfaceView.holder.addCallback(this)

        // Auto-dismiss when the session goes IDLE — typically Mac sleep, the
        // user toggling mirroring off, or a network drop. Without this the
        // user is stuck looking at a black surface until they hit Back.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SessionStateBus.state
                    .distinctUntilChangedBy { it.connection }
                    .collect { snap ->
                        if (snap.connection == SessionStateBus.Connection.IDLE && !isFinishing) {
                            finish()
                        }
                    }
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val p = VideoPipeline(
            holder.surface,
            applicationContext,
            AirPlayJni.audioSessionId,
            AirPlayJni.displayCaps,
        ).also { it.start() }
        pipeline = p
        AirPlayJni.videoPipeline = p
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        AirPlayJni.videoPipeline = null
        pipeline?.stop()
        pipeline = null
    }
}
