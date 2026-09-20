package com.ugallery.app

import org.junit.Assert.*
import org.junit.Test

class PhotosViewerWindowPolicyTest {
    @Test fun ordinaryTouchAndKeyboardKeepExistingNavigation() {
        SurfaceRoute.entries.forEach { assertFalse(retainsPhotosViewerWindow(false, it, true, false)) }
    }
    @Test fun onlyPhotosPublicViewerFamilyRetainsSourceWindow() {
        val family = setOf(SurfaceRoute.Viewer, SurfaceRoute.PhotoEditor, SurfaceRoute.VideoEditor, SurfaceRoute.MotionPhoto)
        SurfaceRoute.entries.forEach { assertEquals(it in family, retainsPhotosViewerWindow(true, it, true, false)) }
    }
    @Test fun externalAndNonPhotosSourcesNeverAdoptRetainedOrigin() {
        SurfaceRoute.entries.forEach {
            assertFalse(retainsPhotosViewerWindow(true, it, false, false))
            assertFalse(retainsPhotosViewerWindow(true, it, true, true))
        }
    }
}
