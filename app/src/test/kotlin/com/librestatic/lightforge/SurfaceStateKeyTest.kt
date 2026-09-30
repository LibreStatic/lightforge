package com.librestatic.lightforge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SurfaceStateKeyTest {
    @Test fun photoEditorKeepsItsRouteDuringAsynchronousRecipeLoading() {
        assertEquals(
            SurfaceRoute.PhotoEditor,
            availableSurfaceRoute(SurfaceRoute.PhotoEditor, true, false, false,
                hasPhotoEditor = false, isPhotoEditorOpening = true),
        )
        assertEquals(
            SurfaceRoute.Viewer,
            availableSurfaceRoute(SurfaceRoute.PhotoEditor, true, false, false,
                hasPhotoEditor = false, isPhotoEditorOpening = false),
        )
        assertEquals(
            SurfaceRoute.Root,
            availableSurfaceRoute(SurfaceRoute.PhotoEditor, false, false, false,
                hasPhotoEditor = false, isPhotoEditorOpening = true),
        )
    }
    @Test fun offlineToolsHaveIndependentRestorableRoutes() {
        assertEquals("own-sync",surfaceStateKey(SurfaceRoute.OwnSync,RootTab.Photos,null))
        assertEquals("offline-places",surfaceStateKey(SurfaceRoute.OfflinePlaces,RootTab.Photos,null))
        listOf(SurfaceRoute.OwnSync,SurfaceRoute.OfflinePlaces).forEach { route ->
            assertEquals(route,availableSurfaceRoute(route,false,false,false))
        }
    }

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
                    hasCreationGifSources = true,
                    hasCreationCollageSources = true,
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
