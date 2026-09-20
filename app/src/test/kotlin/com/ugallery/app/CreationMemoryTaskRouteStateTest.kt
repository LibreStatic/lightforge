package com.ugallery.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CreationMemoryTaskRouteStateTest {
    @Test fun gifDraftKeyIsStableForRecreationButIndependentForEachNewCreation() {
        fun key(id: String) = surfaceStateKey(SurfaceRoute.CreationGif, RootTab.Photos, null, creationGifSessionId = id)
        assertEquals(key("first"), key("first"))
        assertNotEquals(key("first"), key("second"))
    }
    @Test fun gifWithoutSourcesReturnsToLibraryAfterProcessStateLoss() {
        assertEquals(SurfaceRoute.Root, availableSurfaceRoute(SurfaceRoute.CreationGif, false, false, false))
    }
    @Test fun gifSnapshotDoesNotRequireOpenViewerOrAlbum() {
        assertEquals(SurfaceRoute.CreationGif, availableSurfaceRoute(
            SurfaceRoute.CreationGif, false, false, false, hasCreationGifSources = true))
    }
    @Test fun fullMemoryBrowserIsIndependentAndHasDurableUiStateKey() {
        assertEquals(SurfaceRoute.MemoriesBrowser, availableSurfaceRoute(SurfaceRoute.MemoriesBrowser, false, false, false))
        assertEquals("memories-browser", surfaceStateKey(SurfaceRoute.MemoriesBrowser, RootTab.Collections, null))
    }
    @Test fun taskHistoryIsReachableWithoutLibraryMedia() {
        assertEquals(SurfaceRoute.LocalBackupTasks, availableSurfaceRoute(SurfaceRoute.LocalBackupTasks, false, false, false))
        assertEquals("local-backup-tasks", surfaceStateKey(SurfaceRoute.LocalBackupTasks, RootTab.Collections, null))
    }
}
