package com.ugallery.feature.viewer

import android.net.Uri
import android.view.SurfaceView
import androidx.media3.common.Effect
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoViewerControllerTest {
    @Test
    fun refreshingEffectsSeeksPausedFrameAgain() {
        val engine = FakeVideoEngine()
        val controller = VideoViewerController(engine)
        engine.position = 1_234L
        engine.listener?.onReady(10_000L, false)

        controller.setVideoEffects(emptyList())

        assertEquals(1_234L, engine.lastSeek)
    }

    @Test
    fun changingEffectsDoesNotInterruptPlayback() {
        val engine = FakeVideoEngine()
        val controller = VideoViewerController(engine)
        engine.listener?.onReady(10_000L, true)

        controller.setVideoEffects(emptyList())

        assertEquals(null, engine.lastSeek)
    }
}

private class FakeVideoEngine : VideoEngine {
    override var listener: VideoEngine.Listener? = null
    var position = 0L
    var lastSeek: Long? = null
    override fun setMedia(uri: Uri) = Unit
    override fun prepare() = Unit
    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMillis: Long) { lastSeek = positionMillis }
    override fun stopAndClear() = Unit
    override fun release() = Unit
    override fun attachSurface(surfaceView: SurfaceView?) = Unit
    override fun setVolume(volume: Float) = Unit
    override fun setVideoEffects(effects: List<Effect>) = Unit
    override fun currentPositionMillis(): Long = position
}
