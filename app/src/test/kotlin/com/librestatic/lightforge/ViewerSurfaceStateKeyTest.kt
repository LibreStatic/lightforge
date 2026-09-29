package com.librestatic.lightforge

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerSurfaceStateKeyTest {
    @Test
    fun `viewer key does not change when the validated identity arrives`() {
        // The identity is resolved on IO after the viewer opens; a key flip restarted the open
        // transition and rebuilt the viewer (and its video player) midway.
        val beforeValidation = surfaceStateKey(SurfaceRoute.Viewer, RootTab.Photos, null, viewerIdentity = null)
        val afterValidation = surfaceStateKey(SurfaceRoute.Viewer, RootTab.Photos, null, viewerIdentity = "external_primary:1:Video:2:3:false")
        assertEquals(beforeValidation, afterValidation)
        assertEquals(ViewerSurfaceStateKey, afterValidation)
    }
}
