package com.ugallery.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SurfaceStateKeyTest {
    @Test
    fun viewerOriginsReceiveStableSaveableStateKeys() {
        assertEquals(
            "root:photos",
            surfaceStateKey(SurfaceRoute.Root, RootTab.Photos, selectedAlbum = null),
        )
        assertEquals(
            "archive",
            surfaceStateKey(SurfaceRoute.Archive, RootTab.Collections, selectedAlbum = null),
        )
        assertEquals(
            "trash",
            surfaceStateKey(SurfaceRoute.Trash, RootTab.Collections, selectedAlbum = null),
        )
        assertEquals(
            "highlight:selfies",
            surfaceStateKey(
                SurfaceRoute.HighlightCollection,
                RootTab.Photos,
                selectedAlbum = null,
                selectedHighlightId = "selfies",
            ),
        )
        assertNull(
            surfaceStateKey(
                SurfaceRoute.HighlightCollection,
                RootTab.Photos,
                selectedAlbum = null,
                selectedHighlightId = null,
            ),
        )
        assertNull(surfaceStateKey(SurfaceRoute.Viewer, RootTab.Photos, selectedAlbum = null))
    }
}
