package com.librestatic.lightforge

import androidx.compose.ui.unit.dp
import androidx.window.core.ExperimentalWindowApi
import androidx.window.area.WindowAreaCapability.Status
import com.librestatic.lightforge.core.designsystem.GalleryFoldInfo
import com.librestatic.lightforge.core.designsystem.GalleryFoldOrientation
import com.librestatic.lightforge.core.designsystem.GalleryWindowClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalWindowApi::class)
class SharedViewAvailabilityTest {
    private fun fold(separating: Boolean) = GalleryFoldInfo(
        orientation = GalleryFoldOrientation.Vertical,
        isSeparating = separating,
        left = 0.dp, top = 0.dp, right = 0.dp, bottom = 0.dp,
    )

    @Test
    fun presentsOnlyOnRearFacingAvailableOrActiveAreas() {
        assertTrue(canPresentOnArea(true, Status.WINDOW_AREA_STATUS_AVAILABLE))
        assertTrue(canPresentOnArea(true, Status.WINDOW_AREA_STATUS_ACTIVE))
        assertFalse(canPresentOnArea(true, Status.WINDOW_AREA_STATUS_UNAVAILABLE))
        assertFalse(canPresentOnArea(true, Status.WINDOW_AREA_STATUS_UNSUPPORTED))
        assertFalse(canPresentOnArea(true, null))
        assertFalse(canPresentOnArea(false, Status.WINDOW_AREA_STATUS_AVAILABLE))
    }

    @Test
    fun faceToFaceNeedsFoldOrLargeWindowAndNoTrash() {
        fun offers(trash: Boolean, fold: GalleryFoldInfo?, wc: GalleryWindowClass) =
            sharedViewOffers(trash, fold, wc, false, false).faceToFace
        assertFalse(offers(false, null, GalleryWindowClass.Compact))
        assertFalse(offers(false, fold(false), GalleryWindowClass.Compact))
        assertTrue(offers(false, fold(true), GalleryWindowClass.Compact))
        assertTrue(offers(false, null, GalleryWindowClass.Medium))
        assertTrue(offers(false, null, GalleryWindowClass.Expanded))
        assertFalse(offers(true, fold(true), GalleryWindowClass.Expanded))
    }

    @Test
    fun outerDisplayNeedsAvailabilityIdleAndNoTrash() {
        fun offers(trash: Boolean, available: Boolean, active: Boolean) =
            sharedViewOffers(trash, null, GalleryWindowClass.Compact, available, active).outerDisplay
        assertTrue(offers(false, true, false))
        assertFalse(offers(false, false, false))
        assertFalse(offers(false, true, true))
        assertFalse(offers(true, true, false))
        assertEquals(false, sharedViewOffers(true, null, GalleryWindowClass.Compact, true, false).faceToFace)
    }
}
