package com.ugallery.app

import org.junit.Assert.*
import org.junit.Test

class CreationCollageRouteStateTest {
    @Test fun sessionKeySurvivesRecreationButNeverReusesAnotherCreation() {
        fun key(id: String) = surfaceStateKey(SurfaceRoute.Collage, RootTab.Photos, null, creationCollageSessionId = id)
        assertEquals("creation-collage:first", key("first"))
        assertEquals(key("first"), key("first"))
        assertNotEquals(key("first"), key("second"))
        assertNotEquals(key("first"), surfaceStateKey(SurfaceRoute.CreationGif, RootTab.Photos, null, creationGifSessionId = "first"))
    }
    @Test fun processStateWithoutSourcesFallsBackToLibrary() {
        assertEquals(SurfaceRoute.Root, availableSurfaceRoute(SurfaceRoute.Collage, false, false, false))
        assertNull(surfaceStateKey(SurfaceRoute.Collage, RootTab.Photos, null))
    }
    @Test fun explicitSnapshotDoesNotRequireViewerOrAlbum() {
        assertEquals(SurfaceRoute.Collage, availableSurfaceRoute(SurfaceRoute.Collage, false, false, false, hasCreationCollageSources = true))
    }
}
