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

    @Test
    fun restoredRoutesWithoutTheirViewModelContentFallBackToRoot() {
        listOf(
            SurfaceRoute.Viewer,
            SurfaceRoute.PhotoEditor,
            SurfaceRoute.VideoEditor,
        ).forEach { route ->
            assertEquals(
                SurfaceRoute.Root,
                availableSurfaceRoute(
                    requested = route,
                    hasCurrentMedia = false,
                    hasSelectedAlbum = true,
                    hasSelectedHighlight = true,
                ),
            )
        }
        assertEquals(
            SurfaceRoute.Root,
            availableSurfaceRoute(
                requested = SurfaceRoute.Album,
                hasCurrentMedia = true,
                hasSelectedAlbum = false,
                hasSelectedHighlight = true,
            ),
        )
        assertEquals(
            SurfaceRoute.Root,
            availableSurfaceRoute(
                requested = SurfaceRoute.HighlightCollection,
                hasCurrentMedia = true,
                hasSelectedAlbum = true,
                hasSelectedHighlight = false,
            ),
        )
    }

    @Test
    fun routesRemainAvailableWhileTheirRequiredContentExists() {
        SurfaceRoute.entries.forEach { route ->
            assertEquals(
                route,
                availableSurfaceRoute(
                    requested = route,
                    hasCurrentMedia = true,
                    hasSelectedAlbum = true,
                    hasSelectedHighlight = true,
                ),
            )
        }
    }

    @Test
    fun editorRouteWithoutSessionFallsBackToViewerInsteadOfRenderingBlank() {
        assertEquals(
            SurfaceRoute.Viewer,
            availableSurfaceRoute(
                requested = SurfaceRoute.VideoEditor,
                hasCurrentMedia = true,
                hasSelectedAlbum = false,
                hasSelectedHighlight = false,
                hasPhotoEditor = false,
                hasVideoEditor = false,
            ),
        )
    }

    @Test
    fun videoEditorRouteRemainsAvailableWhileTheSessionIsOpening() {
        assertEquals(
            SurfaceRoute.VideoEditor,
            availableSurfaceRoute(
                requested = SurfaceRoute.VideoEditor,
                hasCurrentMedia = true,
                hasSelectedAlbum = false,
                hasSelectedHighlight = false,
                hasPhotoEditor = false,
                hasVideoEditor = true,
            ),
        )
    }
}
