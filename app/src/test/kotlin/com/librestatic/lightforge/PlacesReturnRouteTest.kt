package com.librestatic.lightforge

import org.junit.Assert.*
import org.junit.Test

class PlacesReturnRouteTest {
    @Test fun placesOpenedFromSearchReturnsToSearch() {
        val target = placesReturnTarget(SurfaceRoute.Root, RootTab.Search)
        assertEquals(SurfaceRoute.Root, target.route)
        assertEquals(RootTab.Search, target.rootTab)
    }

    @Test fun placesOpenedFromSettingsReturnsToSettings() {
        val target = placesReturnTarget(SurfaceRoute.Settings, RootTab.Photos)
        assertEquals(SurfaceRoute.Settings, target.route)
    }

    @Test fun placesOpenedFromAnyOtherSurfaceFallsBackToSettings() {
        assertEquals(SurfaceRoute.Settings, placesReturnTarget(SurfaceRoute.Album, RootTab.Collections).route)
    }

    @Test fun rootEntryPreservesTheOriginatingTab() {
        assertEquals(RootTab.Collections, placesReturnTarget(SurfaceRoute.Root, RootTab.Collections).rootTab)
    }

    @Test fun placesRouteStillSurvivesProcessDeath() {
        assertEquals(SurfaceRoute.OfflinePlaces, availableSurfaceRoute(SurfaceRoute.OfflinePlaces, false, false, false))
        assertEquals("offline-places", surfaceStateKey(SurfaceRoute.OfflinePlaces, RootTab.Search, null))
    }
}
