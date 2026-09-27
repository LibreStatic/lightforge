package com.librestatic.lightforge.core.mediastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScopedMediaOperationsTest {
    @Test
    fun displayNameIsTrimmedAndPreservesExtension() {
        assertEquals("holiday.jpg", ScopedMediaOperations.validateDisplayName("  holiday.jpg  "))
    }

    @Test
    fun displayNameRejectsTraversalAndSeparators() {
        listOf("", ".", "..", "folder/photo.jpg", "folder\\photo.jpg", "bad\u0000name.jpg").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                ScopedMediaOperations.validateDisplayName(value)
            }
        }
    }
}
