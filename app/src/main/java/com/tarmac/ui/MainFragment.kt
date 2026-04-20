package com.tarmac.ui

import android.content.Intent
import android.os.Bundle
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tarmac.R
import com.tarmac.service.SessionStateBus
import kotlinx.coroutines.launch

/**
 * Top-level Leanback browse screen. Each row corresponds to a domain
 * (receiver identity, connection state, stream stats, settings entry) and is
 * rebuilt from [SessionStateBus.state] whenever a relevant field changes.
 *
 * Items are simple strings rendered by [StatusItemPresenter] — Phase 3 keeps
 * this a status dashboard, not a card grid.
 */
class MainFragment : BrowseSupportFragment() {

    private lateinit var receiverAdapter: ArrayObjectAdapter
    private lateinit var connectionAdapter: ArrayObjectAdapter
    private lateinit var streamAdapter: ArrayObjectAdapter
    private lateinit var settingsAdapter: ArrayObjectAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        adapter = buildAdapter()
        setOnItemViewClickedListener { _, item, _, _ ->
            if (item == getString(R.string.status_open_settings)) {
                startActivity(Intent(requireContext(), SettingsActivity::class.java))
            }
        }
        observeSessionState()
    }

    private fun buildAdapter(): ArrayObjectAdapter {
        val rows = ArrayObjectAdapter(ListRowPresenter())

        receiverAdapter = ArrayObjectAdapter(StatusItemPresenter())
        connectionAdapter = ArrayObjectAdapter(StatusItemPresenter())
        streamAdapter = ArrayObjectAdapter(StatusItemPresenter())
        settingsAdapter = ArrayObjectAdapter(StatusItemPresenter()).apply {
            add(getString(R.string.status_open_settings))
        }

        rows.add(ListRow(HeaderItem(0, getString(R.string.row_receiver)), receiverAdapter))
        rows.add(ListRow(HeaderItem(1, getString(R.string.row_connection)), connectionAdapter))
        rows.add(ListRow(HeaderItem(2, getString(R.string.row_stream)), streamAdapter))
        rows.add(ListRow(HeaderItem(3, getString(R.string.row_settings)), settingsAdapter))
        return rows
    }

    private fun observeSessionState() {
        viewLifecycleOwnerLiveData.observe(this) { owner ->
            owner ?: return@observe
            owner.lifecycleScope.launch {
                owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    // StateFlow already conflates equal values, so no
                    // distinctUntilChanged here (Kotlin 2.0 errors on the
                    // deprecated overload).
                    SessionStateBus.state.collect { render(it) }
                }
            }
        }
    }

    private fun render(s: SessionStateBus.Snapshot) {
        receiverAdapter.replaceAll(
            listOf(getString(R.string.status_device_name, s.deviceName)),
        )
        connectionAdapter.replaceAll(
            listOf(
                when (s.connection) {
                    SessionStateBus.Connection.ACTIVE -> getString(R.string.status_active)
                    SessionStateBus.Connection.IDLE -> getString(
                        if (s.pin.isEmpty()) R.string.status_pin_idle else R.string.status_pin_waiting,
                        s.pin.ifEmpty { getString(R.string.status_unknown) },
                    )
                },
            ),
        )

        val videoLine = if (s.videoCodec == null) {
            getString(R.string.status_video_pending)
        } else {
            getString(
                R.string.status_video,
                s.videoCodec,
                s.videoResolution ?: getString(R.string.status_unknown),
                s.videoFps?.let { "${it} fps" } ?: getString(R.string.status_unknown),
                s.videoBitrateKbps?.let { "${it} kbps" } ?: getString(R.string.status_unknown),
            )
        }
        val audioLine = if (s.audioCodec == null) {
            getString(R.string.status_audio_pending)
        } else {
            getString(R.string.status_audio, s.audioCodec)
        }
        streamAdapter.replaceAll(listOf(videoLine, audioLine))
    }

    private fun ArrayObjectAdapter.replaceAll(items: List<Any>) {
        // Avoid notifyItemRangeChanged churn on identical state.
        if (size() == items.size) {
            var same = true
            for (i in items.indices) if (get(i) != items[i]) { same = false; break }
            if (same) return
        }
        clear()
        items.forEach { add(it) }
    }
}
