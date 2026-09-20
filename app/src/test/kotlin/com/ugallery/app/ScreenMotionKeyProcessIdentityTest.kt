package com.ugallery.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** AnimatedContent contributes its content key hash to nested rememberSaveable keys. */
class ScreenMotionKeyProcessIdentityTest {
    @Test fun compoundIdentityUsesStableNamesRatherThanProcessLocalEnumIdentity() {
        assertEquals(-772088831, ScreenMotionKey(SurfaceRoute.Root, RootTab.Photos).hashCode())
        assertEquals(-1321761531, ScreenMotionKey(SurfaceRoute.MemoryVideo, RootTab.Photos,
            "memory-video:00000000-0000-0000-0000-000000000001").hashCode())
    }

    @Test fun everySemanticPartStillDistinguishesAnimationSessions() {
        val first = ScreenMotionKey(SurfaceRoute.MemoryVideo, RootTab.Photos, "memory-video:first")
        assertEquals(first, first.copy())
        assertEquals(first.hashCode(), first.copy().hashCode())
        assertNotEquals(first.hashCode(), first.copy(saveableStateKey = "memory-video:second").hashCode())
        assertNotEquals(first.hashCode(), first.copy(route = SurfaceRoute.Moment).hashCode())
        assertNotEquals(first.hashCode(), first.copy(rootTab = RootTab.Collections).hashCode())
    }
}
