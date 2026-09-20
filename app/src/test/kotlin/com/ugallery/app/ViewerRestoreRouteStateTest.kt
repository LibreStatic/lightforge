package com.ugallery.app

import org.junit.Assert.*
import org.junit.Test

class ViewerRestoreRouteStateTest {
    @Test fun sourceSnapshotKeepsViewerAndMotionDuringLoadingAndRecoverableUnavailableState() {
        for (route in listOf(SurfaceRoute.Viewer, SurfaceRoute.MotionPhoto)) {
            assertEquals(route, availableSurfaceRoute(route, false, false, false, hasViewerDraft = true))
            assertEquals(route, availableSurfaceRoute(route, true, false, false))
            assertEquals(SurfaceRoute.Root, availableSurfaceRoute(route, false, false, false))
        }
    }
    @Test fun sourceProviderIsStableAcrossProcessesButDoesNotReuseOtherMediaOrRoutes() {
        fun key(route: SurfaceRoute, identity: String?) = surfaceStateKey(route, RootTab.Photos, null, viewerIdentity = identity)
        assertEquals("motion:external_primary:42:Image:9:7:false", key(SurfaceRoute.MotionPhoto, "external_primary:42:Image:9:7:false"))
        assertEquals(key(SurfaceRoute.MotionPhoto, "same"), key(SurfaceRoute.MotionPhoto, "same"))
        assertNotEquals(key(SurfaceRoute.MotionPhoto, "same"), key(SurfaceRoute.Viewer, "same"))
        assertNotEquals(key(SurfaceRoute.MotionPhoto, "same"), key(SurfaceRoute.MotionPhoto, "replacement"))
        assertNull(key(SurfaceRoute.MotionPhoto, null))
    }
    @Test fun viewerRecoveryDoesNotPretendToRestoreUnimplementedEditorSessions() {
        assertEquals(SurfaceRoute.Root, availableSurfaceRoute(SurfaceRoute.VideoEditor, false, false, false,
            hasVideoEditor = false, hasViewerDraft = true))
        assertEquals(SurfaceRoute.Viewer, availableSurfaceRoute(SurfaceRoute.VideoEditor, true, false, false,
            hasVideoEditor = false, hasViewerDraft = true))
    }
}
