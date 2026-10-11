package com.librestatic.lightforge.core.editing.image

import com.librestatic.lightforge.core.model.EditOperation
import com.librestatic.lightforge.core.model.NormalizedPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class PerspectiveGeometryTest {
    @Test
    fun identityQuadGivesIdentityMatrix() {
        val h = PerspectiveGeometry.matrixValues(EditOperation.Perspective.Identity, 400, 300)!!
        floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f).forEachIndexed { i, v -> assertEquals(v, h[i], 1e-5f) }
    }

    @Test
    fun cornersMapToTheOutputRectangle() {
        val quad = listOf(
            NormalizedPoint(0.1f, 0.05f), NormalizedPoint(0.9f, 0.15f),
            NormalizedPoint(0.95f, 0.9f), NormalizedPoint(0.05f, 0.8f),
        )
        val w = 640
        val height = 480
        val h = PerspectiveGeometry.matrixValues(quad, w, height)!!
        val expected = listOf(0f to 0f, w.toFloat() to 0f, w.toFloat() to height.toFloat(), 0f to height.toFloat())
        quad.forEachIndexed { i, p ->
            val (x, y) = PerspectiveGeometry.map(h, p.x * w, p.y * height)
            assertEquals(expected[i].first, x, 0.05f)
            assertEquals(expected[i].second, y, 0.05f)
        }
        val (cx, cy) = PerspectiveGeometry.map(h, 0.5f * w, 0.45f * height)
        assert(cx in 0f..w.toFloat() && cy in 0f..height.toFloat())
    }
}
