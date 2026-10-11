package com.librestatic.lightforge.core.designsystem

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaEditorZoomTest {
    @Test
    fun noPanAtIdentityWhenContentFillsViewport() {
        assertEquals(Offset.Zero, mediaEditorMaxOffsets(Size(100f, 100f), Size(100f, 100f), 1f))
    }

    @Test
    fun letterboxedAxisCannotPanUntilContentOutgrowsViewport() {
        // Wide picture in a tall stage: 100x40 content in 100x100.
        val content = Size(100f, 40f)
        val viewport = Size(100f, 100f)
        assertEquals(Offset(0f, 0f), mediaEditorMaxOffsets(content, viewport, 1f))
        assertEquals(Offset(100f, 10f), mediaEditorMaxOffsets(content, viewport, 3f))
        // The short axis (40 high) only starts to pan past scale 2.5.
        assertEquals(Offset(75f, 0f), mediaEditorMaxOffsets(content, viewport, 2.5f))
        assertEquals(Offset(150f, 30f), mediaEditorMaxOffsets(content, viewport, 4f))
    }

    @Test
    fun fullFitContentPansHalfTheOvershoot() {
        assertEquals(Offset(100f, 100f), mediaEditorMaxOffsets(Size(200f, 200f), Size(200f, 200f), 2f))
    }

    @Test
    fun zeroSizesLockBothAxes() {
        assertEquals(Offset.Zero, mediaEditorMaxOffsets(Size.Zero, Size.Zero, 5f))
    }
}
