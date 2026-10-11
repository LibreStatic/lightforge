package com.librestatic.lightforge.feature.photoeditor

import com.librestatic.lightforge.core.model.EditOperation
import com.librestatic.lightforge.core.model.NormalizedPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoPerspectiveTest {
    private val identity = IdentityCorners

    @Test
    fun identityIsConvexAndReadsZeroKeystone() {
        assertTrue(isConvexQuad(identity))
        assertEquals(0f, verticalKeystone(identity), 0f)
        assertEquals(0f, horizontalKeystone(identity), 0f)
    }

    @Test
    fun verticalKeystonePullsTheTopEdgeInAndRoundTrips() {
        val corners = withVerticalKeystone(identity, 0.5f)

        assertEquals(0.125f, corners[0].x, 1e-5f)
        assertEquals(0.875f, corners[1].x, 1e-5f)
        assertEquals(0f, corners[3].x, 1e-5f)
        assertEquals(1f, corners[2].x, 1e-5f)
        assertEquals(0.5f, verticalKeystone(corners), 1e-4f)
        assertTrue(isConvexQuad(corners))
        EditOperation.Perspective(corners)
    }

    @Test
    fun negativeVerticalKeystonePullsTheBottomEdgeIn() {
        val corners = withVerticalKeystone(identity, -1f)

        assertEquals(0f, corners[0].x, 1e-5f)
        assertEquals(0.25f, corners[3].x, 1e-5f)
        assertEquals(0.75f, corners[2].x, 1e-5f)
        assertEquals(-1f, verticalKeystone(corners), 1e-4f)
    }

    @Test
    fun horizontalKeystoneMovesLeftOrRightEdgeAndRoundTrips() {
        val left = withHorizontalKeystone(identity, 1f)
        assertEquals(0.25f, left[0].y, 1e-5f)
        assertEquals(0.75f, left[3].y, 1e-5f)
        assertEquals(0f, left[1].y, 1e-5f)
        assertEquals(1f, horizontalKeystone(left), 1e-4f)

        val right = withHorizontalKeystone(identity, -0.4f)
        assertEquals(0.1f, right[1].y, 1e-5f)
        assertEquals(0.9f, right[2].y, 1e-5f)
        assertEquals(-0.4f, horizontalKeystone(right), 1e-4f)
    }

    @Test
    fun slidersCombineWithoutLosingEachOther() {
        val both = withHorizontalKeystone(withVerticalKeystone(identity, 0.6f), -0.3f)

        assertEquals(0.6f, verticalKeystone(both), 1e-4f)
        assertEquals(-0.3f, horizontalKeystone(both), 1e-4f)
        assertTrue(isConvexQuad(both))
    }

    @Test
    fun movingASliderBackToZeroRestoresTheIdentity() {
        val back = withVerticalKeystone(withVerticalKeystone(identity, 0.8f), 0f)

        back.forEachIndexed { i, p ->
            assertEquals(identity[i].x, p.x, 1e-5f)
            assertEquals(identity[i].y, p.y, 1e-5f)
        }
    }

    @Test
    fun draggedCornerThatKeepsTheQuadConvexLandsExactly() {
        val moved = movePerspectiveCorner(identity, 0, NormalizedPoint(0.2f, 0.15f))

        assertEquals(0.2f, moved[0].x, 0f)
        assertEquals(0.15f, moved[0].y, 0f)
        assertEquals(identity[1], moved[1])
    }

    @Test
    fun dragPastTheOppositeEdgeIsClampedToAConvexQuad() {
        // Dragging top-left across the bottom edge would flip the quad inside out.
        val moved = movePerspectiveCorner(identity, 0, NormalizedPoint(0.6f, 1f))

        assertTrue(isConvexQuad(moved))
        assertTrue("corner must stop short of the far edge", moved[0].y < 1f)
        EditOperation.Perspective(moved)
    }

    @Test
    fun dragThatMakesACornerConcaveStopsAtTheBoundary() {
        // Bottom-right pulled onto the top-left/top-right diagonal side makes a dart.
        val start = listOf(NormalizedPoint(0f, 0f), NormalizedPoint(1f, 0f), NormalizedPoint(0.9f, 0.9f), NormalizedPoint(0f, 1f))
        val moved = movePerspectiveCorner(start, 2, NormalizedPoint(0.05f, 0.05f))

        assertTrue(isConvexQuad(moved))
        assertFalse(moved[2] == NormalizedPoint(0.05f, 0.05f))
    }

    @Test
    fun rotatingAndFlippingTheQuadMatchesTheModelRemap() {
        val tilted = withVerticalKeystone(identity, 0.4f)
        val cw = tilted.rotatedClockwise()
        val expected = EditOperation.Perspective(tilted).let { op ->
            // Model: rotate every source corner, then re-order to the output rectangle.
            val rotated = op.corners.map { NormalizedPoint(1f - it.y, it.x) }
            listOf(rotated[3], rotated[0], rotated[1], rotated[2])
        }

        assertCornersEqual(expected, cw)
        assertTrue(isConvexQuad(cw))
        // Four quarter turns and two identical flips are the identity.
        var turned = tilted
        repeat(4) { turned = turned.rotatedClockwise() }
        assertCornersEqual(tilted, turned)
        assertCornersEqual(tilted, tilted.flipped(true).flipped(true))
        assertCornersEqual(tilted, tilted.flipped(false).flipped(false))
    }

    private fun assertCornersEqual(expected: List<NormalizedPoint>, actual: List<NormalizedPoint>) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach {
            assertEquals(expected[it].x, actual[it].x, 1e-5f)
            assertEquals(expected[it].y, actual[it].y, 1e-5f)
        }
    }
}
