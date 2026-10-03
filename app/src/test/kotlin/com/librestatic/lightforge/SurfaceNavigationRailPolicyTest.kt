package com.librestatic.lightforge

import org.junit.Assert.assertEquals
import org.junit.Test

class SurfaceNavigationRailPolicyTest {

    @Test
    fun `library and collection screens keep the rail`() {
        listOf(
            SurfaceRoute.Root, SurfaceRoute.Updates, SurfaceRoute.Album, SurfaceRoute.Settings,
            SurfaceRoute.OfflinePlaces, SurfaceRoute.MemoriesBrowser, SurfaceRoute.People,
            SurfaceRoute.PdfStudio, SurfaceRoute.Cleanup, SurfaceRoute.SmartAlbums,
            SurfaceRoute.Documents, SurfaceRoute.Stacks, SurfaceRoute.Trash, SurfaceRoute.Archive,
        ).forEach { assertEquals(it.name, true, surfaceShowsNavigationRail(it)) }
    }

    @Test
    fun `focal tasks take the whole window`() {
        listOf(
            SurfaceRoute.Viewer, SurfaceRoute.PhotoEditor, SurfaceRoute.VideoEditor,
            SurfaceRoute.MotionPhoto, SurfaceRoute.CreationGif, SurfaceRoute.Collage,
            SurfaceRoute.ManualMoment, SurfaceRoute.MemoryVideo, SurfaceRoute.Moment,
            SurfaceRoute.PrivateAlbum, SurfaceRoute.PrivateAlbumPicker,
        ).forEach { assertEquals(it.name, false, surfaceShowsNavigationRail(it)) }
    }
}
