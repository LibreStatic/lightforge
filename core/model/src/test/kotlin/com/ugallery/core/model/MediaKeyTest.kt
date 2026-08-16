package com.ugallery.core.model

import org.junit.Assert.assertNotEquals
import org.junit.Test

class MediaKeyTest {
    @Test fun sameIdOnDifferentVolumesIsDifferentMedia() {
        assertNotEquals(MediaKey("external_primary", 42), MediaKey("1234-5678", 42))
    }
}

