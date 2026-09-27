package com.librestatic.lightforge.feature.viewer

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

        assertEquals(1_233L, engine.lastSeek)
    }

    @Test
    fun refreshingEffectsAtEndReturnsToFirstFrame() {
        val engine = FakeVideoEngine()
        val controller = VideoViewerController(engine)
        engine.position = 10_000L
        engine.listener?.onReady(10_000L, false)

        controller.setVideoEffects(emptyList())

        assertEquals(0L, engine.lastSeek)
    }

    @Test
    fun changingEffectsDoesNotInterruptPlayback() {
        val engine = FakeVideoEngine()
        val controller = VideoViewerController(engine)
        engine.listener?.onReady(10_000L, true)

        controller.setVideoEffects(emptyList())

        assertEquals(null, engine.lastSeek)
    }

    @Test
    fun refreshingUpdatedEffectDoesNotCreateAnotherEffectBoundary() {
        val engine = FakeVideoEngine()
        val controller = VideoViewerController(engine)
        engine.position = 1_234L
        engine.listener?.onReady(10_000L, false)

        controller.refreshVideoFrame()

        assertEquals(0, engine.effectCalls)
        assertEquals(1_233L, engine.lastSeek)
    }

    @Test
    fun loopingCanStartEnabledAndBeChangedWithoutRecreatingPlayback() {
        val engine = FakeVideoEngine()
        val controller = VideoViewerController(engine, initialLooping = true)

        assertEquals(listOf(true), engine.repeatEnabled)

        controller.setLooping(false)

        assertEquals(listOf(true, false), engine.repeatEnabled)
    }

    @Test
    fun loopingIsDisabledByDefaultForViewerControllers() {
        val engine = FakeVideoEngine()

        VideoViewerController(engine)

        assertEquals(listOf(false), engine.repeatEnabled)
    }

    @Test
    fun relativeSeekIsClampedToPlaybackBounds() {
        val engine = FakeVideoEngine()
        val controller = VideoViewerController(engine)
        engine.listener?.onReady(10_000L, true)
        engine.position = 9_500L

        controller.seekBy(2_000L)
        assertEquals(10_000L, engine.lastSeek)

        engine.position = 500L
        controller.seekBy(-2_000L)
        assertEquals(0L, engine.lastSeek)
    }

    @Test
    fun scrubbingModeIsIdempotentAndCleanedUpAcrossTheControllerLifecycle() {
        val engine = FakeVideoEngine()
        val controller = VideoViewerController(engine)

        controller.beginScrubbing()
        controller.beginScrubbing()
        controller.endScrubbing()
        controller.endScrubbing()
        controller.beginScrubbing()
        controller.close()

        assertEquals(listOf(true, false, true, false), engine.scrubbingModeChanges)
    }
}

private class FakeVideoEngine : VideoEngine {
    override var listener: VideoEngine.Listener? = null
    var position = 0L
    var lastSeek: Long? = null
    val repeatEnabled = mutableListOf<Boolean>()
    val scrubbingModeChanges = mutableListOf<Boolean>()
    var effectCalls = 0
    override fun setMedia(uri: Uri) = Unit
    override fun prepare() = Unit
    override fun play() = Unit
    override fun pause() = Unit
    override fun setScrubbingModeEnabled(enabled: Boolean) { scrubbingModeChanges += enabled }
    override fun seekTo(positionMillis: Long) { lastSeek = positionMillis }
    override fun stopAndClear() = Unit
    override fun release() = Unit
    override fun attachSurface(surfaceView: SurfaceView?) = Unit
    override fun setVolume(volume: Float) = Unit
    override fun setRepeatEnabled(enabled: Boolean) { repeatEnabled += enabled }
    override fun setVideoEffects(effects: List<Effect>) { effectCalls++ }
    override fun currentPositionMillis(): Long = position
}
