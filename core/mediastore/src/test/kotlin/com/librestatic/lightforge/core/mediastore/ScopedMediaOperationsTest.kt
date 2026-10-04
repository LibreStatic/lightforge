package com.librestatic.lightforge.core.mediastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.TimeZone

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

    @Test
    fun exifDateTimeUsesTheGivenZone() {
        val millis = 1_759_276_800_000L // 2025-10-01T00:00:00Z
        assertEquals("2025:10:01 00:00:00", ScopedMediaOperations.exifDateTime(millis, TimeZone.getTimeZone("UTC")))
        assertEquals("2025:09:30 21:00:00", ScopedMediaOperations.exifDateTime(millis, TimeZone.getTimeZone("GMT-03:00")))
    }

    @Test
    fun exifOffsetIsSignedHoursAndMinutes() {
        val millis = 1_759_276_800_000L
        assertEquals("+00:00", ScopedMediaOperations.exifOffset(millis, TimeZone.getTimeZone("UTC")))
        assertEquals("-03:00", ScopedMediaOperations.exifOffset(millis, TimeZone.getTimeZone("GMT-03:00")))
        assertEquals("+05:30", ScopedMediaOperations.exifOffset(millis, TimeZone.getTimeZone("GMT+05:30")))
    }
}
