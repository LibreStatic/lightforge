package com.ugallery.feature.viewer

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoViewerControllerTest {
    @Test
    fun `rapid selection reuses one engine and clears previous decoder`() {
        val engine = FakeVideoEngine()
        val controller = VideoViewerController(engine)

        controller.select(Uri.parse("content://media/1"))
        controller.select(Uri.parse("content://media/2"))
        controller.select(Uri.parse("content://media/3"))

        assertEquals(3, engine.media.size)
        assertEquals(3, engine.clearCalls)
        assertEquals("content://media/3", engine.media.last().toString())
        controller.close()
        controller.close()
        assertEquals(4, engine.clearCalls)
        assertEquals(1, engine.releaseCalls)
    }

    @Test
    fun `background pauses and unsupported failure is explicit`() {
        val engine = FakeVideoEngine()
        val controller = VideoViewerController(engine)
        val uri = Uri.parse("content://media/video")
        controller.select(uri)
        engine.listener?.onReady(5_000, true)
        assertTrue((controller.state.value as VideoViewerState.Ready).isPlaying)

        controller.onBackground()
        assertEquals(1, engine.pauseCalls)
        engine.listener?.onFailure(4_003, true)
        val failure = controller.state.value as VideoViewerState.Failure
        assertTrue(failure.unsupported)
        assertEquals(uri, failure.uri)
        assertFalse(controller.state.value is VideoViewerState.Ready)
    }

    @Test
    fun `autoplay starts muted and explicit user intent unmutes`() {
        val engine = FakeVideoEngine()
        val controller = VideoViewerController(engine)

        controller.select(
            Uri.parse("content://media/video"),
            autoplay = true,
            startMuted = true,
        )

        assertEquals(1, engine.playCalls)
        assertEquals(0f, engine.volumes.single(), 0f)
        engine.listener?.onReady(5_000, true)
        assertTrue((controller.state.value as VideoViewerState.Ready).isMuted)

        controller.unmute()

        assertEquals(1f, engine.volumes.last(), 0f)
        assertFalse((controller.state.value as VideoViewerState.Ready).isMuted)
    }

    private class FakeVideoEngine : VideoEngine {
        override var listener: VideoEngine.Listener? = null
        val media = mutableListOf<Uri>()
        var clearCalls = 0
        var playCalls = 0
        var pauseCalls = 0
        var releaseCalls = 0
        val volumes = mutableListOf<Float>()
        override fun setMedia(uri: Uri) { media += uri }
        override fun prepare() = Unit
        override fun play() { playCalls++ }
        override fun pause() { pauseCalls++ }
        override fun seekTo(positionMillis: Long) = Unit
        override fun stopAndClear() { clearCalls++ }
        override fun release() { releaseCalls++ }
        override fun attachSurface(surfaceView: android.view.SurfaceView?) = Unit
        override fun setVolume(volume: Float) { volumes += volume }
    }
}
