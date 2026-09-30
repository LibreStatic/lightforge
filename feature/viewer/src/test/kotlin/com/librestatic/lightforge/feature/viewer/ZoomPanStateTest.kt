package com.librestatic.lightforge.feature.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ZoomPanStateTest {

    private val density = Density(1f, 1f)

    // Container 100x100; content displayed size equals container at scale 1 (fit = 1),
    // so max offsets at scale s are (100*s - 100)/2 on each axis.
    private fun state() = ZoomPanState(density) { candidateScale ->
        val span = (100f * candidateScale - 100f) / 2f
        Offset(span, span)
    }

    /**
     * runTest plus a frame clock that answers every withFrameNanos immediately so spring
     * animations converge deterministically. Each frame also pumps the virtual scheduler so
     * spring jobs resume, progress monotonically, and settle instead of spinning.
     */
    private fun runZoomPanTest(block: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(EagerFrameClock(testScheduler)) { block() }
    }

    private class EagerFrameClock(private val scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) : MonotonicFrameClock {
        private var frame = 0L

        private companion object {
            // withFrameNanos expects nanoseconds: one 60 Hz frame per call.
            const val FRAME_MILLIS = 16L
            const val FRAME_NANOS = FRAME_MILLIS * 1_000_000L
        }
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R =
            onFrame(++frame * FRAME_NANOS).also { scheduler.advanceTimeBy(FRAME_MILLIS) }
    }

    @Test
    fun pinchGestureClampsPanWithinBoundsAndTracksScale() = runZoomPanTest {
        val zoom = state()
        zoom.applyGesture(
            containerCenter = Offset(50f, 50f),
            centroid = Offset(50f, 50f),
            pan = Offset(10_000f, -10_000f),
            zoomFactor = 2f,
            minScale = 1f,
            maxScale = 8f,
        )
        assertEquals(2f, zoom.currentScale, 0.001f)
        assertEquals(50f, zoom.offsetX.value, 0.5f)
        assertEquals(-50f, zoom.offsetY.value, 0.5f)
        assertTrue(zoom.isZoomed)
    }

    @Test
    fun zoomBelowThresholdIsNotReportedAsZoomed() = runZoomPanTest {
        val zoom = state()
        zoom.applyGesture(Offset(50f, 50f), Offset(50f, 50f), Offset.Zero, 1.005f, 1f, 8f)
        assertFalse(zoom.isZoomed)
    }

    @Test
    fun zoomToOneRecentersContentAfterAGesture() = runZoomPanTest {
        val zoom = state()
        zoom.applyGesture(Offset(50f, 50f), Offset(20f, 20f), Offset(30f, 30f), 3f, 1f, 8f)
        assertTrue(zoom.isZoomed)
        zoom.zoomTo(Offset(50f, 50f), 1f, null, 8f)
        assertEquals(1f, zoom.currentScale, 0.0001f)
        assertEquals(0f, zoom.offsetX.value, 0.0001f)
        assertEquals(0f, zoom.offsetY.value, 0.0001f)
    }

    @Test
    fun doubleTapZoomConvergesTowardTapFocusEvenAfterPriorGestures() = runZoomPanTest {
        val zoom = state()
        // A prior gesture installs offset bounds for its own scale; the next double-tap must
        // still converge to the tap focus instead of being trapped by stale bounds.
        zoom.applyGesture(Offset(50f, 50f), Offset(50f, 50f), Offset(40f, 40f), 1.4f, 1f, 8f)
        zoom.zoomTo(Offset(50f, 50f), 2f, Offset(80f, 80f), 8f)
        assertEquals(2f, zoom.currentScale, 0.001f)
        assertTrue(zoom.offsetX.value in -50f..50f)
        assertTrue(zoom.offsetY.value in -50f..50f)
        assertTrue(zoom.isZoomed)
    }

    @Test
    fun flingKeepsVelocityDurationWhileZoomBoundsAdaptItsTravel() {
        val velocity = Offset(1_000f, 0f)
        val nearIdentity = createFlingPlan(
            current = Offset.Zero,
            velocityPxPerSecond = velocity,
            bounds = Offset(40f, 40f),
            minimumVelocityPxPerSecond = 50f,
        )
        val deepZoom = createFlingPlan(
            current = Offset.Zero,
            velocityPxPerSecond = velocity,
            bounds = Offset(500f, 500f),
            minimumVelocityPxPerSecond = 50f,
        )

        requireNotNull(nearIdentity)
        requireNotNull(deepZoom)
        // Matches the float-to-int truncation used by the reference behavior.
        assertEquals(249, nearIdentity.durationMillis)
        assertEquals(nearIdentity.durationMillis, deepZoom.durationMillis)
        assertEquals(40f, nearIdentity.target.x, 0.001f)
        assertEquals(125f, deepZoom.target.x, 0.001f)
    }

    @Test
    fun flingUsesSharedDurationForBothAxesAndClampsEachToItsZoomBounds() {
        val plan = createFlingPlan(
            current = Offset(10f, -10f),
            velocityPxPerSecond = Offset(1_000f, -1_000f),
            bounds = Offset(80f, 200f),
            minimumVelocityPxPerSecond = 50f,
        )

        requireNotNull(plan)
        assertEquals(353, plan.durationMillis)
        assertEquals(80f, plan.target.x, 0.001f)
        assertEquals(-186.7767f, plan.target.y, 0.001f)
    }

    @Test
    fun flingIgnoresReleaseBelowMinimumVelocity() {
        val plan = createFlingPlan(
            current = Offset.Zero,
            velocityPxPerSecond = Offset(49f, -49f),
            bounds = Offset(500f, 500f),
            minimumVelocityPxPerSecond = 50f,
        )

        assertEquals(null, plan)
    }
}
