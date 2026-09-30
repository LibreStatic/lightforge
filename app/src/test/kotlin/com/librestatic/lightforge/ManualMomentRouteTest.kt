package com.librestatic.lightforge

import org.junit.Assert.*
import org.junit.Test

class ManualMomentRouteTest {
    @Test fun manualDraftIdsDoNotReuseReviewStateAndDocumentsRetainSelectionOnReturn() {
        fun manual(id: String?) = surfaceStateKey(SurfaceRoute.ManualMoment, RootTab.Photos, null,
            manualMomentSessionId = id)
        assertNull(manual(null))
        assertEquals("manual-moment:first", manual("first"))
        assertNotEquals(manual("first"), manual("second"))
        assertEquals("documents", surfaceStateKey(SurfaceRoute.Documents, RootTab.Photos, null))
        assertEquals("documents", surfaceStateKey(SurfaceRoute.Documents, RootTab.Collections, null))
    }
}
