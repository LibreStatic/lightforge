package com.librestatic.lightforge

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionPhotoRouteStateTest {
    @Test fun motionRouteReturnsToLibraryAfterSourceStateWasLost() {
        assertEquals(SurfaceRoute.Root, availableSurfaceRoute(SurfaceRoute.MotionPhoto, false, false, false))
    }
    @Test fun motionRouteKeepsTheCurrentSourceWithoutAnAlbumOrEditor() {
        assertEquals(SurfaceRoute.MotionPhoto, availableSurfaceRoute(SurfaceRoute.MotionPhoto, true, false, false, false, false))
    }
    @Test fun portableBackupReviewDoesNotRequireViewerOrAlbumIdentity() {
        assertEquals(SurfaceRoute.LocalBackup, availableSurfaceRoute(SurfaceRoute.LocalBackup, false, false, false))
    }
}
