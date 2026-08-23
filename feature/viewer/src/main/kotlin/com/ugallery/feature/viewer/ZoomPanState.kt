package com.ugallery.feature.viewer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.splineBasedDecay
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
 * - Programmatic moves (double-tap, settle back to 1x) run as concurrent spring animations on
 *   scale and both offset axes ([zoomTo]).
 * - On drag release, [fling] applies platform spline decay per axis; bounds installed through
 *   Animatable.updateBounds make the decay stop hard at the content edges.
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

    /**
     * Platform spline decay; density never changes for a live instance so build it once.
     * Lazy because construction touches ViewConfiguration friction constants that do not
     * exist on the JVM unit-test classpath and fling is not exercised there.
     */
    private val flingDecay: DecayAnimationSpec<Float> by lazy { splineBasedDecay(this) }

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
     * Smoothly animates scale and offset (parallel springs) to [targetScale]. When [focus] is
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
        // Clear stale offset bounds from a previous gesture/scale: springs must be free to
        // travel, the final position below re-clamps against the target-scale bounds.
        offsetX.updateBounds(null, null)
        offsetY.updateBounds(null, null)
        coroutineScope {
            launch { scale.animateTo(targetScale, ZOOM_SPRING) }
            launch { offsetX.animateTo(target.x, ZOOM_SPRING) }
            launch { offsetY.animateTo(target.y, ZOOM_SPRING) }
        }
        // Springs settle within a visibility threshold of the target; land exactly on it unless
        // a newer gesture/transition already took over while the springs were running.
        if (session == transitionSession) {
            scale.snapTo(targetScale)
            snapOffsetTo(target, targetScale)
        }
    }

    /**
     * Launches a velocity-driven fling per axis using platform spline decay. Bounds installed on
     * the Animatables terminate the decay exactly at the content edges.
     */
    fun fling(velocity: Offset, scope: CoroutineScope) {
        if (!isZoomed) return
        val bounds = maxOffsets(scale.value)
        if (bounds.x <= 0f && bounds.y <= 0f) return
        val minimumFling = 50.dp.toPx()
        flingJob?.cancel()
        flingJob = scope.launch {
            coroutineScope {
                launchFlingAxis(offsetX, velocity.x, bounds.x, minimumFling)
                launchFlingAxis(offsetY, velocity.y, bounds.y, minimumFling)
            }
        }
    }

    private fun CoroutineScope.launchFlingAxis(
        axis: Animatable<Float, AnimationVector1D>,
        initialVelocity: Float,
        maxAbs: Float,
        minimumFling: Float,
    ): Job = launch {
        if (maxAbs <= 0f || abs(initialVelocity) < minimumFling) return@launch
        axis.updateBounds(-maxAbs, maxAbs)
        axis.animateDecay(initialVelocity, flingDecay)
        // BoundReached ends the decay close to but not exactly on the bound; pin it.
        axis.snapTo(axis.value.coerceIn(-maxAbs, maxAbs))
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

        private val ZOOM_SPRING: SpringSpec<Float> = spring(
            dampingRatio = 0.9f,
            stiffness = Spring.StiffnessMediumLow,
        )
    }
}
