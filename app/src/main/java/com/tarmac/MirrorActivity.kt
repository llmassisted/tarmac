package com.tarmac

import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import com.tarmac.media.VideoPipeline
import com.tarmac.service.AirPlayJni

class MirrorActivity : FragmentActivity(), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView
    private var pipeline: VideoPipeline? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_mirror)
        surfaceView = findViewById(R.id.mirror_surface)
        surfaceView.holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val p = VideoPipeline(holder.surface).also { it.start() }
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
