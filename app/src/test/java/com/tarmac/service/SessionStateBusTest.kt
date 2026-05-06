package com.tarmac.service

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SessionStateBusTest {

    @Before
    fun reset() {
        SessionStateBus.setConnection(SessionStateBus.Connection.IDLE)
        SessionStateBus.setPin("")
        SessionStateBus.setDeviceName("Tarmac")
        SessionStateBus.clearMediaStats()
    }

    @Test
    fun `default snapshot is IDLE with empty pin`() = runTest {
        val snap = SessionStateBus.state.first()
        assertEquals(SessionStateBus.Connection.IDLE, snap.connection)
        assertEquals("", snap.pin)
        assertEquals("Tarmac", snap.deviceName)
        assertNull(snap.videoCodec)
        assertNull(snap.audioCodec)
    }

    @Test
    fun `setConnection updates snapshot`() = runTest {
        SessionStateBus.setConnection(SessionStateBus.Connection.ACTIVE)
        val snap = SessionStateBus.state.first()
        assertEquals(SessionStateBus.Connection.ACTIVE, snap.connection)
    }

    @Test
    fun `setPin updates snapshot`() = runTest {
        SessionStateBus.setPin("1234")
        val snap = SessionStateBus.state.first()
        assertEquals("1234", snap.pin)
    }

    @Test
    fun `updateVideo populates codec and resolution`() = runTest {
        SessionStateBus.updateVideo("H.265", "3840×2160", fps = 60, bitrateKbps = 25000)
        val snap = SessionStateBus.state.first()
        assertEquals("H.265", snap.videoCodec)
        assertEquals("3840×2160", snap.videoResolution)
        assertEquals(60, snap.videoFps)
        assertEquals(25000, snap.videoBitrateKbps)
    }

    @Test
    fun `clearMediaStats nulls all media fields`() = runTest {
        SessionStateBus.updateVideo("H.264", "1920×1080", fps = 30, bitrateKbps = 5000)
        SessionStateBus.setAudioCodec("AAC-ELD")
        SessionStateBus.clearMediaStats()
        val snap = SessionStateBus.state.first()
        assertNull(snap.videoCodec)
        assertNull(snap.videoResolution)
        assertNull(snap.videoFps)
        assertNull(snap.videoBitrateKbps)
        assertNull(snap.audioCodec)
    }

    @Test
    fun `setDeviceName updates snapshot`() = runTest {
        SessionStateBus.setDeviceName("Living Room TV")
        val snap = SessionStateBus.state.first()
        assertEquals("Living Room TV", snap.deviceName)
    }

    @Test
    fun `reportPipelineFault emits to pipelineFaults flow`() = runTest {
        val collected = mutableListOf<String>()
        val job = launch {
            SessionStateBus.pipelineFaults.take(2).toList(collected)
        }
        // Yield so the collector subscribes before tryEmit fires.
        kotlinx.coroutines.yield()
        SessionStateBus.reportPipelineFault("video")
        SessionStateBus.reportPipelineFault("audio")
        job.join()
        assertEquals(listOf("video", "audio"), collected)
    }
}
