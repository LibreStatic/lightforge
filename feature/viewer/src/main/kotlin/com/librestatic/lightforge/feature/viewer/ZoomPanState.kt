package com.librestatic.lightforge.feature.viewer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Animated zoom and pan state for viewer surfaces.
 *
 * - Pinch/drag frames are snapped directly ([applyGesture]) so content tracks fingers exactly.
 * - Programmatic moves (double-tap, settle back to 1x) run scale and both offset axes on one
 *   shared FastOutSlowIn tween ([zoomTo]), matching the double-tap curve measured on Google Photos.
 * - On drag release, [fling] continues at the finger's release velocity and decays it
 *   exponentially (Google Photos' model, measured on device: τ ≈ 0.55 s on both axes). Each axis
 *   stops on its own when it reaches the pan bounds of the current zoom while the other glides on.
 *
 * The owner computes [containerCenter] from its own layout size and provides [maxOffsets] so the
 * same state works for fit-scaled images and plain surface scaling alike.
 */
internal class ZoomPanState(
    density: Density,
    /** Max absolute offset allowed on each axis for a given scale. A zero component locks that axis. */
    private val maxOffsets: (scale: Float) -> Offset,
) : Density by density {

    val scale = Animatable(1f)
    val offsetX = Animatable(0f)
    val offsetY = Animatable(0f)

    val currentScale: Float get() = scale.value
    val isZoomed: Boolean get() = scale.value > ZOOMED_THRESHOLD

    private var flingJob: Job? = null

    /** Monotonic token bumped on every interruption so stale completions skip their final snap. */
    private var transitionSession = 0

    /** Cancels any running transition or fling so a new gesture or animation starts clean. */
    suspend fun stopTransitions() {
        transitionSession++
        flingJob?.cancel()
        flingJob = null
        scale.stop()
        offsetX.stop()
        offsetY.stop()
    }

    /**
     * Applies one transform-gesture frame: multiplicative zoom around [centroid] plus pan delta,
     * clamped to [minScale]..[maxScale] and the pan bounds of the resulting scale.
     */
    suspend fun applyGesture(
        containerCenter: Offset,
        centroid: Offset,
        pan: Offset,
        zoomFactor: Float,
        minScale: Float,
        maxScale: Float,
    ) {
        stopTransitions()
        val oldScale = scale.value
        val newScale = (oldScale * zoomFactor).coerceIn(minScale, maxScale)
        val currentOffset = Offset(offsetX.value, offsetY.value)
        val focusCorrection =
            (centroid - containerCenter - currentOffset) * (1f - newScale / oldScale)
        scale.updateBounds(minScale, maxScale.coerceAtLeast(minScale))
        scale.snapTo(newScale)
        snapOffsetTo(currentOffset + pan + focusCorrection, newScale)
    }

    /**
     * Smoothly animates scale and offset (one shared eased tween) to [targetScale]. When [focus] is
     * provided (the double-tap position), the zoom converges toward that point. A target of 1f
     * recenters the content.
     */
    suspend fun zoomTo(
        containerCenter: Offset,
        targetScale: Float,
        focus: Offset?,
        maxScale: Float,
    ) {
        stopTransitions()
        val session = transitionSession
        val oldScale = scale.value
        val currentOffset = Offset(offsetX.value, offsetY.value)
        val correction =
            focus?.let { (it - containerCenter - currentOffset) * (1f - targetScale / oldScale) }
                ?: Offset.Zero
        val target =
            if (targetScale == 1f) Offset.Zero else clamp(currentOffset + correction, targetScale)
        scale.updateBounds(1f, maxScale.coerceAtLeast(1f))
        // Clear stale offset bounds from a previous gesture/scale: the tween must be free to
        // travel, the final position below re-clamps against the target-scale bounds.
        offsetX.updateBounds(null, null)
        offsetY.updateBounds(null, null)
        coroutineScope {
            launch { scale.animateTo(targetScale, ZOOM_TWEEN) }
            launch { offsetX.animateTo(target.x, ZOOM_TWEEN) }
            launch { offsetY.animateTo(target.y, ZOOM_TWEEN) }
        }
        // Land exactly on the target unless a newer gesture/transition took over meanwhile.
        if (session == transitionSession) {
            scale.snapTo(targetScale)
            snapOffsetTo(target, targetScale)
        }
    }

    /**
     * Launches a velocity-driven fling: an exponential decay per axis that starts at the release
     * velocity and is stopped by that axis' pan bounds at the current zoom.
     */
    fun fling(velocity: Offset, scope: CoroutineScope) {
        if (!isZoomed) return
        val bounds = maxOffsets(scale.value)
        if (bounds.x <= 0f && bounds.y <= 0f) return
        val minimumFling = 50.dp.toPx()
        if (abs(velocity.x) < minimumFling && abs(velocity.y) < minimumFling) return
        flingJob?.cancel()
        flingJob = scope.launch {
            offsetX.updateBounds(-bounds.x.coerceAtLeast(0f), bounds.x.coerceAtLeast(0f))
            offsetY.updateBounds(-bounds.y.coerceAtLeast(0f), bounds.y.coerceAtLeast(0f))
            coroutineScope {
                launch { offsetX.animateDecay(velocity.x, FLING_DECAY) }
                launch { offsetY.animateDecay(velocity.y, FLING_DECAY) }
            }
        }
    }

    private suspend fun snapOffsetTo(candidate: Offset, scaleValue: Float) {
        val bounds = maxOffsets(scaleValue)
        val maxX = bounds.x.coerceAtLeast(0f)
        val maxY = bounds.y.coerceAtLeast(0f)
        offsetX.updateBounds(-maxX, maxX)
        offsetY.updateBounds(-maxY, maxY)
        offsetX.snapTo(candidate.x.coerceIn(-maxX, maxX))
        offsetY.snapTo(candidate.y.coerceIn(-maxY, maxY))
    }

    private fun clamp(candidate: Offset, scaleValue: Float): Offset {
        val bounds = maxOffsets(scaleValue)
        return Offset(
            candidate.x.coerceIn(-bounds.x, bounds.x),
            candidate.y.coerceIn(-bounds.y, bounds.y),
        )
    }

    companion object {
        const val ZOOMED_THRESHOLD = 1.01f

        /**
         * Google Photos' double-tap zoom, measured frame by frame on device: a standard
         * FastOutSlowIn (0.4, 0, 0.2, 1) curve over ~290 ms, linear in scale.
         */
        private val ZOOM_TWEEN: TweenSpec<Float> = tween(
            durationMillis = 290,
            easing = FastOutSlowInEasing,
        )

        /** Release-velocity decay with Google Photos' measured time constant. */
        val FLING_DECAY: DecayAnimationSpec<Float> = exponentialDecay(
            frictionMultiplier = 1f / (EXPONENTIAL_DECAY_FRICTION * FLING_TIME_CONSTANT_SECONDS),
        )
    }
}

/** Seconds for a fling's velocity to fall to 1/e; distance travelled is velocity × this. */
internal const val FLING_TIME_CONSTANT_SECONDS = 0.55f

/** Compose's base friction for [exponentialDecay] (velocity ∝ e^(-4.2 · multiplier · t)). */
private const val EXPONENTIAL_DECAY_FRICTION = 4.2f
