package com.ugallery.core.model

import org.junit.Assert.*
import org.junit.Test

class TimelineStackTest {
    @Test
    fun stableKeyFollowsGroupIdentityInsteadOfChangingCover() {
        val media =
            TimelineMedia(
                MediaKey("v", 1),
                MediaKind.Image,
                1,
                1,
                10,
                10,
                0,
                stack = TimelineStack("group", "r1", 2),
            )
        assertEquals("stack:group", TimelineEntry.Media(media).stableKey)
        assertEquals(
            TimelineEntry.Media(media).stableKey,
            TimelineEntry.Media(media.copy(key = MediaKey("v", 2))).stableKey,
        )
        assertEquals("media:v:1", TimelineEntry.Media(media.copy(stack = null)).stableKey)
    }

    @Test
    fun rejectsEmptyIdentityOrInvalidCount() {
        for (n in listOf(0, 1, 501)) assertTrue(
            runCatching { TimelineStack("s", "r", n) }.isFailure
        )
        assertTrue(runCatching { TimelineStack("", "r", 2) }.isFailure)
        assertTrue(runCatching { TimelineStack("s", "", 2) }.isFailure)
    }
}
