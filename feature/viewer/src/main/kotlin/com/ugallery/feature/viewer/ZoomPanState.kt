package com.ugallery.feature.viewer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
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
 * - Programmatic moves (double-tap, settle back to 1x) run as concurrent spring animations on
 *   scale and both offset axes ([zoomTo]).
 * - On drag release, [fling] uses the gallery motion model: velocity chooses a shared
 *   duration and destination, the destination is constrained by the current zoom bounds, and a
 *   quadratic deceleration animates both axes. At low zoom the nearby bounds shorten the travel
 *   without shortening its duration; deeper zoom exposes more range and therefore more momentum.
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
     * Launches a velocity-driven fling using the gallery duration/destination model.
     *
     * Unlike a spline decay that runs unchanged until it collides with an edge, this computes the
     * unconstrained motion first and then clamps its destination to the pan range available at the
     * current zoom. Duration remains velocity-driven. The same release therefore eases gently over
     * a short distance near 1x and carries farther as zoom exposes a larger canvas.
     */
    fun fling(velocity: Offset, scope: CoroutineScope) {
        if (!isZoomed) return
        val bounds = maxOffsets(scale.value)
        if (bounds.x <= 0f && bounds.y <= 0f) return
        val minimumFling = 50.dp.toPx()
        val plan = createFlingPlan(
            current = Offset(offsetX.value, offsetY.value),
            velocityPxPerSecond = velocity,
            bounds = bounds,
            minimumVelocityPxPerSecond = minimumFling,
        ) ?: return
        flingJob?.cancel()
        flingJob = scope.launch {
            offsetX.updateBounds(-bounds.x.coerceAtLeast(0f), bounds.x.coerceAtLeast(0f))
            offsetY.updateBounds(-bounds.y.coerceAtLeast(0f), bounds.y.coerceAtLeast(0f))
            coroutineScope {
                launch {
                    offsetX.animateTo(
                        plan.target.x,
                        tween(plan.durationMillis, easing = FLING_EASING),
                    )
                }
                launch {
                    offsetY.animateTo(
                        plan.target.y,
                        tween(plan.durationMillis, easing = FLING_EASING),
                    )
                }
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

        private val ZOOM_SPRING: SpringSpec<Float> = spring(
            dampingRatio = 0.9f,
            stiffness = Spring.StiffnessMediumLow,
        )

        /** Android's DecelerateInterpolator with its default factor of 1. */
        private val FLING_EASING = Easing { fraction ->
            1f - (1f - fraction) * (1f - fraction)
        }
    }
}

internal data class FlingPlan(
    val target: Offset,
    val durationMillis: Int,
)

/**
 * Reproduces gallery' fling geometry in screen pixels.
 *
 * Photos first scales detector velocity by 0.5, converts it to px/ms, derives a shared duration
 * from a constant 0.002 px/ms² deceleration, and clamps the resulting destination to the image
 * bounds. Keeping that duration after clamping is what makes the apparent acceleration adapt to
 * the amount of zoom rather than stopping abruptly at a nearby edge.
 */
internal fun createFlingPlan(
    current: Offset,
    velocityPxPerSecond: Offset,
    bounds: Offset,
    minimumVelocityPxPerSecond: Float,
): FlingPlan? {
    if (abs(velocityPxPerSecond.x) < minimumVelocityPxPerSecond &&
        abs(velocityPxPerSecond.y) < minimumVelocityPxPerSecond
    ) {
        return null
    }
    val velocityPxPerMillisecond = velocityPxPerSecond * FLING_INITIAL_VELOCITY_FACTOR / 1_000f
    val speed = velocityPxPerMillisecond.getDistance()
    if (speed == 0f) return null
    val duration = speed / FLING_DECELERATION_PX_PER_MS_SQUARED
    val durationMillis = duration.toInt().coerceAtLeast(1)
    val target = Offset(
        (current.x + velocityPxPerMillisecond.x * duration)
            .coerceIn(-bounds.x.coerceAtLeast(0f), bounds.x.coerceAtLeast(0f)),
        (current.y + velocityPxPerMillisecond.y * duration)
            .coerceIn(-bounds.y.coerceAtLeast(0f), bounds.y.coerceAtLeast(0f)),
    )
    return FlingPlan(target, durationMillis)
}

private const val FLING_INITIAL_VELOCITY_FACTOR = 0.5f
private const val FLING_DECELERATION_PX_PER_MS_SQUARED = 0.002f
