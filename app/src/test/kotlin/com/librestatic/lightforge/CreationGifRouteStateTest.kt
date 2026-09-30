package com.librestatic.lightforge

import org.junit.Assert.*
import org.junit.Test

class CreationGifRouteStateTest {
    @Test fun restoredDraftKeepsRouteDuringLoadingAndUnavailableState() {
        assertEquals(SurfaceRoute.CreationGif, availableSurfaceRoute(SurfaceRoute.CreationGif,
            false, false, false, hasCreationGifDraft = true))
        assertEquals(SurfaceRoute.CreationGif, availableSurfaceRoute(SurfaceRoute.CreationGif,
            false, false, false, hasCreationGifSources = true))
        assertEquals(SurfaceRoute.Root, availableSurfaceRoute(SurfaceRoute.CreationGif, false, false, false))
    }
    @Test fun sessionProviderKeyIsStableAndIsolatedFromOtherDrafts() {
        fun key(id: String?) = surfaceStateKey(SurfaceRoute.CreationGif, RootTab.Photos, null, creationGifSessionId = id)
        assertEquals("creation-gif:first", key("first"))
        assertEquals(key("first"), key("first"))
        assertNotEquals(key("first"), key("second"))
        assertNull(key(null))
        assertNotEquals(key("first"), surfaceStateKey(SurfaceRoute.Collage, RootTab.Photos, null, creationCollageSessionId = "first"))
    }
}
