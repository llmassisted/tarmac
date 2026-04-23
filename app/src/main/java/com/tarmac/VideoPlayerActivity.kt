package com.tarmac

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.tarmac.service.SessionStateBus
import kotlinx.coroutines.launch

class VideoPlayerActivity : FragmentActivity() {

    companion object {
        private const val TAG = "VideoPlayerActivity"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_START_SEC = "extra_start_sec"
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_video_player)
        playerView = findViewById(R.id.video_player_view)

        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val startSec = intent.getFloatExtra(EXTRA_START_SEC, 0f)

        initPlayer(url, startSec)
        observeVideoEvents()
    }

    private fun initPlayer(url: String, startSec: Float) {
        val exo = try {
            ExoPlayer.Builder(this).build().also { player = it }
        } catch (t: Throwable) {
            Log.w(TAG, "ExoPlayer init failed: ${t.message}")
            finish()
            return
        }
        playerView.player = exo

        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Unreachable URL or unsupported media: log and bail cleanly
                // so the AirPlay session can return to IDLE on the Mac side
                // without dragging the activity into an uninitialised state.
                Log.w(TAG, "playback error code=${error.errorCode} msg=${error.message}")
                finish()
            }
        })

        runCatching {
            exo.setMediaItem(MediaItem.fromUri(url))
            exo.prepare()
            if (startSec > 0f) exo.seekTo((startSec * 1000).toLong())
            exo.playWhenReady = true
        }.onFailure {
            Log.w(TAG, "media prepare failed: ${it.message}")
            finish()
        }
    }

    private fun observeVideoEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SessionStateBus.videoEvents.collect { event ->
                    val exo = player ?: return@collect
                    when (event) {
                        is SessionStateBus.VideoEvent.Stop -> finish()
                        is SessionStateBus.VideoEvent.Rate -> {
                            if (event.rate > 0f) {
                                exo.setPlaybackSpeed(event.rate)
                                exo.play()
                            } else {
                                exo.pause()
                            }
                        }
                        is SessionStateBus.VideoEvent.Scrub -> exo.seekTo((event.positionSec * 1000).toLong())
                        is SessionStateBus.VideoEvent.Play -> {
                            exo.setMediaItem(MediaItem.fromUri(event.url))
                            exo.prepare()
                            if (event.startSec > 0f) exo.seekTo((event.startSec * 1000).toLong())
                            exo.playWhenReady = true
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        player?.play()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
