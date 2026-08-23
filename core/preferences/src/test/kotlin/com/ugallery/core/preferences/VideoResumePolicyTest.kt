package com.ugallery.core.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoResumePolicyTest {
    @Test fun `restores a useful position`() {
        assertEquals(25_000L, VideoResumePolicy.restoredPosition(true, 25_000L, 60_000L, 60_000L))
    }

    @Test fun `does not restore near the start or end`() {
        assertNull(VideoResumePolicy.restoredPosition(true, 2_999L, 60_000L, 60_000L))
        assertNull(VideoResumePolicy.restoredPosition(true, 55_000L, 60_000L, 60_000L))
    }

    @Test fun `rejects a position after media duration changes`() {
        assertNull(VideoResumePolicy.restoredPosition(true, 20_000L, 60_000L, 63_000L))
    }

    @Test fun `persistence follows the setting and useful range`() {
        assertTrue(VideoResumePolicy.shouldPersist(true, 10_000L, 60_000L))
        assertFalse(VideoResumePolicy.shouldPersist(false, 10_000L, 60_000L))
        assertFalse(VideoResumePolicy.shouldPersist(true, 58_000L, 60_000L))
    }
}
