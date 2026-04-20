package com.tarmac.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide hub for UI-visible AirPlay session state.
 *
 * TarmacService is the only writer. UI components (MainFragment,
 * MirrorActivity) collect from [state] on the main thread.
 *
 * Why not LiveData: collectors live across the service↔activity boundary and
 * we already pull in coroutines. StateFlow keeps the API uniform between
 * the (background) producer and the (lifecycle-scoped) consumers.
 */
object SessionStateBus {

    enum class Connection { IDLE, ACTIVE }

    /**
     * Snapshot of everything the user-facing surfaces care about. Numeric
     * fields are nullable so MainFragment can render "—" until the first
     * decoded frame lands.
     */
    data class Snapshot(
        val deviceName: String = "Tarmac",
        val pin: String = "",
        val connection: Connection = Connection.IDLE,
        val videoCodec: String? = null,        // "H.264" / "H.265"
        val videoResolution: String? = null,   // "1920×1080"
        val videoFps: Int? = null,
        val videoBitrateKbps: Int? = null,
        val audioCodec: String? = null,        // "AAC-ELD" / "ALAC" / "PCM"
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun setDeviceName(name: String) = _state.update { it.copy(deviceName = name) }
    fun setPin(pin: String) = _state.update { it.copy(pin = pin) }
    fun setConnection(c: Connection) = _state.update { it.copy(connection = c) }

    fun updateVideo(codec: String, resolution: String, fps: Int?, bitrateKbps: Int?) =
        _state.update {
            it.copy(
                videoCodec = codec,
                videoResolution = resolution,
                videoFps = fps,
                videoBitrateKbps = bitrateKbps,
            )
        }

    fun setAudioCodec(codec: String) = _state.update { it.copy(audioCodec = codec) }

    fun clearMediaStats() = _state.update {
        it.copy(
            videoCodec = null,
            videoResolution = null,
            videoFps = null,
            videoBitrateKbps = null,
            audioCodec = null,
        )
    }

    // --- AirPlay Video (HLS) playback control events ---

    sealed class VideoEvent {
        data class Play(val url: String, val startSec: Float) : VideoEvent()
        data class Rate(val rate: Float) : VideoEvent()
        data class Scrub(val positionSec: Float) : VideoEvent()
        object Stop : VideoEvent()
    }

    private val _videoEvents = MutableSharedFlow<VideoEvent>(extraBufferCapacity = 4)
    val videoEvents: SharedFlow<VideoEvent> = _videoEvents.asSharedFlow()

    fun emitVideoEvent(event: VideoEvent) { _videoEvents.tryEmit(event) }
}
