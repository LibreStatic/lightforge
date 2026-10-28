package com.librestatic.lightforge.feature.viewer.shared

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedZoomMathTest {
    private val container = Size(1000f, 1000f)
    private val fitted = Size(1000f, 500f)

    @Test
    fun fitsContentInsideTheContainer() {
        assertEquals(Size(1000f, 500f), fittedSize(Size(1000f, 1000f), 4000f, 2000f))
        assertEquals(Size(500f, 1000f), fittedSize(Size(1000f, 1000f), 1000f, 2000f))
    }

    @Test
    fun pinchAtTheCentreScalesWithoutPanning() {
        val zoom = SharedZoom().afterGesture(Offset.Zero, Offset.Zero, 2f, container, fitted)
        assertEquals(2f, zoom.scale, 0f)
        assertEquals(Offset.Zero, zoom.translation(container, fitted))
    }

    @Test
    fun panIsClampedToTheZoomedContent() {
        val zoom = SharedZoom().afterGesture(Offset.Zero, Offset(5000f, 5000f), 2f, container, fitted)
        // Width: (1000 * 2 - 1000) / 2; height already fits, so no vertical pan.
        assertEquals(Offset(500f, 0f), zoom.translation(container, fitted))
    }

    @Test
    fun panIsNormalizedToTheFittedSize() {
        val zoom = SharedZoom().afterGesture(Offset.Zero, Offset(100f, 0f), 2f, container, fitted)
        assertEquals(0.1f, zoom.panX, 1e-6f)
        // The same value moves a pane with a smaller fitted width proportionally.
        assertEquals(30f, zoom.translation(Size(300f, 300f), Size(300f, 150f)).x, 1e-3f)
    }

    @Test
    fun returningToFitResetsTheZoom() {
        val zoomed = SharedZoom(2f, 0.2f, 0f)
        assertEquals(SharedZoom(), zoomed.afterGesture(Offset.Zero, Offset.Zero, 0.5f, container, fitted))
    }

    @Test
    fun scaleIsCappedAtTheMaximum() {
        val zoom = SharedZoom(5f, 0f, 0f).afterGesture(Offset.Zero, Offset.Zero, 10f, container, fitted)
        assertEquals(SHARED_ZOOM_MAX_SCALE, zoom.scale, 0f)
    }
}
