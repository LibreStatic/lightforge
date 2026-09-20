package com.ugallery.core.model

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaKeyTest {
    @Test fun sameIdOnDifferentVolumesIsDifferentMedia() {
        assertNotEquals(MediaKey("external_primary", 42), MediaKey("1234-5678", 42))
    }

    @Test fun timelineMediaExposesViewerIdentityWithoutChangingItsMediaStoreKey() {
        val key = MediaKey("external_primary", 42)
        val media = TimelineMedia(key, MediaKind.Image, 7, 0, 100, 200, 0)

        assertEquals(key, media.mediaKey)
        assertEquals("media:external_primary:42", media.viewerId)
    }
}
