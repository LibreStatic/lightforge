package com.librestatic.lightforge

import org.junit.Assert.assertEquals
import org.junit.Test

class SurfaceTopInsetPolicyTest {

    @Test
    fun `full bleed routes need the controls to apply the top inset`() {
        assertEquals(true, surfaceControlsNeedTopInset(SurfaceRoute.Viewer))
        assertEquals(true, surfaceControlsNeedTopInset(SurfaceRoute.PhotoEditor))
        assertEquals(true, surfaceControlsNeedTopInset(SurfaceRoute.VideoEditor))
        assertEquals(true, surfaceControlsNeedTopInset(SurfaceRoute.PrivateAlbum))
        assertEquals(true, surfaceControlsNeedTopInset(SurfaceRoute.PrivateAlbumPicker))
    }

    @Test
    fun `routes owning their top bar need the controls to apply the top inset`() {
        assertEquals(true, surfaceControlsNeedTopInset(SurfaceRoute.Settings))
        assertEquals(true, surfaceControlsNeedTopInset(SurfaceRoute.Moment))
        assertEquals(true, surfaceControlsNeedTopInset(SurfaceRoute.MemoriesBrowser))
    }

    @Test
    fun `scaffold inset routes leave the top inset to the scaffold`() {
        assertEquals(false, surfaceControlsNeedTopInset(SurfaceRoute.Root))
        assertEquals(false, surfaceControlsNeedTopInset(SurfaceRoute.Album))
        assertEquals(false, surfaceControlsNeedTopInset(SurfaceRoute.Trash))
        assertEquals(false, surfaceControlsNeedTopInset(SurfaceRoute.Archive))
    }

    @Test
    fun `full bleed and own top bar are disjoint`() {
        SurfaceRoute.values().forEach { route ->
            assertEquals(false, surfaceIsFullBleed(route) && surfaceOwnsTopBar(route))
        }
    }
}
