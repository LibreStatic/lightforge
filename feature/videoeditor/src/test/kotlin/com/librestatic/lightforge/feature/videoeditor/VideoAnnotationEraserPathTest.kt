package com.librestatic.lightforge.feature.videoeditor

import com.librestatic.lightforge.core.editing.video.NormalizedPoint
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class VideoAnnotationEraserPathTest {
    @Test
    fun densifyEraserPath_fillsLongSegmentsAndKeepsEndpoints() {
        val path = densifyEraserPath(listOf(NormalizedPoint(0.1f, 0.5f), NormalizedPoint(0.5f, 0.5f)))
        assertEquals(NormalizedPoint(0.1f, 0.5f), path.first())
        assertEquals(0.5f, path.last().x, 1e-4f)
        assertTrue(path.zipWithNext().all { (a, b) -> kotlin.math.abs(b.x - a.x) <= 0.0121f })
    }

    @Test
    fun densifyEraserPath_leavesSinglePointAlone() {
        val single = listOf(NormalizedPoint(0.2f, 0.2f))
        assertEquals(single, densifyEraserPath(single))
    }
}
