package com.librestatic.lightforge.feature.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.geometry.Offset
import com.librestatic.lightforge.core.preferences.GestureSettings
import com.librestatic.lightforge.core.preferences.SlowMotionDragMode
import com.librestatic.lightforge.core.preferences.VideoHoldAction
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the fast-speed pill shows while a fast-forward or rewind hold runs. */
internal data class FastHoldIndicator(val forward: Boolean, val speed: Float)

/**
 * Side effects of a hold on the video: AI slow motion (with shuttle or scrub drags), fast forward
 * and rewind (with live speed steps). [start] returns false when the action cannot run; every
 * started hold must be ended with [end], which is idempotent and restores the play, mute and
 * speed state the hold found, so a cancelled gesture or a disposed viewer can call it blindly.
 */
internal class VideoHoldController(
    private val controller: VideoViewerController,
    private val slowMotion: HoldSlowMotionSession?,
    private val scope: CoroutineScope,
    private val onSpeedStep: () -> Unit = {},
    /** Frame-clock time, so the rewind ticker follows the same clock as `delay` in tests. */
    private val nowMillis: suspend () -> Long = { withFrameMillis { it } },
) {
    var isActive by mutableStateOf(false)
        private set
    var fastIndicator by mutableStateOf<FastHoldIndicator?>(null)
        private set

    private var action = VideoHoldAction.None
    private var settings = GestureSettings()
    private var wasPlaying = false
    private var wasMuted = false
    private var speed = 1f

    private var rewindTarget = 0L
    private var rewindJob: Job? = null

    private var slopPx = 0f
    private var density = 1f
    private var widthPx = 1f
    private var startPositionMillis = 0L
    private var shuttleEngaged = false
    private var anchorX = 0f
    private var anchorMillis = 0L
    private var lastRate = HoldSlowMotionSession.Speed

    fun start(
        action: VideoHoldAction,
        settings: GestureSettings,
        slopPx: Float,
        density: Float,
        widthPx: Float,
    ): Boolean {
        if (isActive) end()
        if (action == VideoHoldAction.None) return false
        if (action == VideoHoldAction.SlowMotion && slowMotion == null) return false
        this.action = action
        this.settings = settings
        this.slopPx = slopPx
        this.density = density
        this.widthPx = widthPx
        val ready = controller.state.value as? VideoViewerState.Ready
        wasPlaying = ready?.isPlaying == true
        wasMuted = ready?.isMuted == true
        speed = settings.videoFastSpeed
        isActive = true
        when (action) {
            VideoHoldAction.SlowMotion -> {
                startPositionMillis = controller.currentPositionMillis()
                shuttleEngaged = false
                lastRate = HoldSlowMotionSession.Speed
                controller.pause()
                slowMotion?.start(startPositionMillis)
            }
            VideoHoldAction.FastForward -> {
                controller.setPlaybackSpeed(speed)
                if (!settings.videoFastForwardAudio) controller.mute()
                controller.play()
                fastIndicator = FastHoldIndicator(forward = true, speed = speed)
            }
            VideoHoldAction.FastRewind -> {
                controller.pause()
                controller.beginScrubbing()
                rewindTarget = controller.currentPositionMillis()
                fastIndicator = FastHoldIndicator(forward = false, speed = speed)
                rewindJob = scope.launch { runRewindTicker() }
            }
            VideoHoldAction.None -> Unit
        }
        return true
    }

    fun move(delta: Offset) {
        if (!isActive) return
        when (action) {
            VideoHoldAction.SlowMotion -> moveSlowMotion(delta)
            VideoHoldAction.FastForward, VideoHoldAction.FastRewind -> moveFast(delta)
            VideoHoldAction.None -> Unit
        }
    }

    fun end() {
        if (!isActive) return
        isActive = false
        fastIndicator = null
        rewindJob?.cancel()
        rewindJob = null
        when (action) {
            VideoHoldAction.SlowMotion -> {
                val stop = slowMotion?.stop()
                controller.seekTo(stop?.resumeAtMillis ?: controller.currentPositionMillis())
                if (wasPlaying) controller.play()
            }
            VideoHoldAction.FastForward -> {
                controller.setPlaybackSpeed(1f)
                if (!wasMuted) controller.unmute()
                if (!wasPlaying) controller.pause()
            }
            VideoHoldAction.FastRewind -> {
                controller.seekTo(rewindTarget)
                controller.endScrubbing()
                if (wasPlaying) controller.play()
            }
            VideoHoldAction.None -> Unit
        }
        action = VideoHoldAction.None
    }

    private fun moveSlowMotion(delta: Offset) {
        val session = slowMotion ?: return
        if (!shuttleEngaged) {
            if (abs(delta.x) <= slopPx) return
            shuttleEngaged = true
            anchorX = delta.x
            anchorMillis = (session.state.value as? HoldSlowMotionState.Playing)?.sourcePositionMillis
                ?: startPositionMillis
        }
        val dx = delta.x - anchorX
        when (settings.slowMotionDragMode) {
            SlowMotionDragMode.Shuttle -> {
                val rate = shuttleRate(dx, density)
                if (rate != lastRate) {
                    lastRate = rate
                    session.setRate(rate)
                }
            }
            SlowMotionDragMode.Scrub -> session.scrubTo(scrubTargetMillis(anchorMillis, dx, widthPx))
        }
    }

    private fun moveFast(delta: Offset) {
        if (!settings.videoHoldLiveSpeed) return
        val next = liveSpeedAfter(settings.videoFastSpeed, liveSpeedSteps(delta.x, delta.y, action == VideoHoldAction.FastForward, density))
        if (next == speed) return
        speed = next
        fastIndicator = FastHoldIndicator(forward = action == VideoHoldAction.FastForward, speed = next)
        if (action == VideoHoldAction.FastForward) controller.setPlaybackSpeed(next)
        onSpeedStep()
    }

    /** Seeks back by speed x real elapsed time; the target is tracked here because seeks are asynchronous. */
    private suspend fun runRewindTicker() {
        var last = nowMillis()
        while (scope.isActive) {
            delay(REWIND_TICK_MILLIS)
            val now = nowMillis()
            val next = rewindTargetMillis(rewindTarget, speed, now - last)
            last = now
            if (next != rewindTarget) {
                rewindTarget = next
                controller.seekTo(next)
            }
        }
    }

    private companion object {
        const val REWIND_TICK_MILLIS = 100L
    }
}
