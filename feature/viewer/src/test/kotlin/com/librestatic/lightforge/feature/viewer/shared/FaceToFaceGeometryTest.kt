package com.librestatic.lightforge.feature.viewer.shared

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceToFaceGeometryTest {
    @Test
    fun verticalHingeSplitsLeftAndRightAroundTheGap() {
        val layout = faceToFaceLayout(1346f, 1682f, FaceToFaceFold(SplitAxis.Vertical, 660f, 686f))
        assertEquals(SplitAxis.Vertical, layout.axis)
        assertEquals(Rect(0f, 0f, 660f, 1682f), layout.first.bounds)
        assertEquals(Rect(686f, 0f, 1346f, 1682f), layout.second.bounds)
        // The tops of both halves point at the hinge.
        assertEquals(90, layout.first.rotationDegrees)
        assertEquals(270, layout.second.rotationDegrees)
    }

    @Test
    fun horizontalHingeSplitsTopAndBottomAroundTheGap() {
        val layout = faceToFaceLayout(1682f, 1346f, FaceToFaceFold(SplitAxis.Horizontal, 660f, 686f))
        assertEquals(SplitAxis.Horizontal, layout.axis)
        assertEquals(Rect(0f, 0f, 1682f, 660f), layout.first.bounds)
        assertEquals(Rect(0f, 686f, 1682f, 1346f), layout.second.bounds)
        assertEquals(180, layout.first.rotationDegrees)
        assertEquals(0, layout.second.rotationDegrees)
    }

    @Test
    fun withoutFoldLandscapeSplitsLeftAndRightAtTheMidpoint() {
        val layout = faceToFaceLayout(2000f, 1000f)
        assertEquals(SplitAxis.Vertical, layout.axis)
        assertEquals(Rect(0f, 0f, 1000f, 1000f), layout.first.bounds)
        assertEquals(Rect(1000f, 0f, 2000f, 1000f), layout.second.bounds)
    }

    @Test
    fun withoutFoldPortraitSplitsTopAndBottomAtTheMidpoint() {
        val layout = faceToFaceLayout(1000f, 2000f)
        assertEquals(SplitAxis.Horizontal, layout.axis)
        assertEquals(Rect(0f, 0f, 1000f, 1000f), layout.first.bounds)
        assertEquals(Rect(0f, 1000f, 1000f, 2000f), layout.second.bounds)
        assertEquals(180, layout.first.rotationDegrees)
        assertEquals(0, layout.second.rotationDegrees)
    }

    @Test
    fun squareWindowPrefersLeftAndRight() {
        assertEquals(SplitAxis.Vertical, faceToFaceLayout(900f, 900f).axis)
    }

    @Test
    fun asymmetricFoldGivesUnequalHalves() {
        val layout = faceToFaceLayout(1000f, 600f, FaceToFaceFold(SplitAxis.Vertical, 300f, 320f))
        assertEquals(300f, layout.first.bounds.width, 0f)
        assertEquals(680f, layout.second.bounds.width, 0f)
    }

    @Test
    fun axisOverrideIgnoresAFoldOnTheOtherAxis() {
        val fold = FaceToFaceFold(SplitAxis.Vertical, 660f, 686f)
        val layout = faceToFaceLayout(1346f, 1682f, fold, axisOverride = SplitAxis.Horizontal)
        assertEquals(SplitAxis.Horizontal, layout.axis)
        assertEquals(Rect(0f, 0f, 1346f, 841f), layout.first.bounds)
        assertEquals(Rect(0f, 841f, 1346f, 1682f), layout.second.bounds)
    }

    @Test
    fun axisOverrideMatchingTheFoldKeepsTheHingeGap() {
        val fold = FaceToFaceFold(SplitAxis.Vertical, 660f, 686f)
        val layout = faceToFaceLayout(1346f, 1682f, fold, axisOverride = SplitAxis.Vertical)
        assertEquals(660f, layout.first.bounds.right, 0f)
        assertEquals(686f, layout.second.bounds.left, 0f)
    }

    @Test
    fun togglingTheAxisFlipsIt() {
        assertEquals(SplitAxis.Horizontal, SplitAxis.Vertical.other)
        assertEquals(SplitAxis.Vertical, SplitAxis.Horizontal.other)
    }

    @Test
    fun hingeGapIsExcludedFromBothHalves() {
        val layout = faceToFaceLayout(1346f, 1682f, FaceToFaceFold(SplitAxis.Vertical, 650f, 700f))
        val gap = layout.second.bounds.left - layout.first.bounds.right
        assertEquals(50f, gap, 0f)
        assertTrue(layout.first.bounds.right <= 650f && layout.second.bounds.left >= 700f)
        assertEquals(1346f - 50f, layout.first.bounds.width + layout.second.bounds.width, 0f)
    }

    @Test
    fun foldOutsideTheWindowIsClamped() {
        val layout = faceToFaceLayout(1000f, 500f, FaceToFaceFold(SplitAxis.Vertical, 900f, 1200f))
        assertEquals(900f, layout.first.bounds.right, 0f)
        assertEquals(1000f, layout.second.bounds.left, 0f)
        assertEquals(0f, layout.second.bounds.width, 0f)
    }
}
