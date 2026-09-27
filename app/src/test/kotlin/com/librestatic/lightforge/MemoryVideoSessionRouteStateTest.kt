package com.librestatic.lightforge

import org.junit.Assert.*
import org.junit.Test

class MemoryVideoSessionRouteStateTest {
    @Test fun videoDraftIsBoundToFreshHandoffNotSelectedPhotoIds() {
        val first = surfaceStateKey(SurfaceRoute.MemoryVideo, RootTab.Photos, null, memoryVideoSessionId = "first")
        assertEquals("memory-video:first", first)
        assertNotEquals(first, surfaceStateKey(SurfaceRoute.MemoryVideo, RootTab.Photos, null, memoryVideoSessionId = "second"))
        assertNull(surfaceStateKey(SurfaceRoute.MemoryVideo, RootTab.Photos, null))
    }
}
