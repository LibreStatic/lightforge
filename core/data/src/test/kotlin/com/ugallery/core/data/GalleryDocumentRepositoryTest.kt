package com.ugallery.core.data

import org.junit.Assert.*
import org.junit.Test

class GalleryDocumentRepositoryTest {
    @Test
    fun userSearchIsLiteralAndBounded() {
        assertEquals("%10\\%\\_%", GalleryDocumentRepository.searchPattern(" 10%_ "))
        assertEquals("%a\\\\b%", GalleryDocumentRepository.searchPattern("a\\b"))
        assertEquals(122, GalleryDocumentRepository.searchPattern("x".repeat(1000)).length)
    }
}
