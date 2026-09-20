package com.ugallery.feature.privatealbum

import org.junit.Assert.*
import org.junit.Test

class PrivateIndexKeyRecordTest {
    private val alias = "ugallery.privatealbum.auth.v1.test-123"
    @Test fun legacyAndAuthenticatedOffsetsAreExact() {
        val legacy = byteArrayOf(85, 71, 73, 75, 1) + ByteArray(60)
        assertEquals(PrivateIndexKeyRecord(null, 5), PrivateIndexKeyRecord.parse(legacy))
        val encoded = PrivateIndexKeyRecord.encode(alias, ByteArray(12) { 3 }, ByteArray(48) { 7 })
        assertEquals(PrivateIndexKeyRecord(alias, 7 + alias.length), PrivateIndexKeyRecord.parse(encoded))
        assertEquals(67 + alias.length, encoded.size)
    }
    @Test fun truncationTrailingDataUnknownVersionAndForeignAliasFailClosed() {
        val encoded = PrivateIndexKeyRecord.encode(alias, ByteArray(12), ByteArray(48))
        for (size in 0 until encoded.size) assertTrue("size=$size", runCatching { PrivateIndexKeyRecord.parse(encoded.copyOf(size)) }.isFailure)
        assertTrue(runCatching { PrivateIndexKeyRecord.parse(encoded + 0) }.isFailure)
        assertTrue(runCatching { PrivateIndexKeyRecord.parse(encoded.clone().apply { this[4] = 3 }) }.isFailure)
        assertTrue(runCatching { PrivateIndexKeyRecord.parse(encoded.clone().apply { this[5] = 127 }) }.isFailure)
        assertTrue(runCatching { PrivateIndexKeyRecord.encode("other", ByteArray(12), ByteArray(48)) }.isFailure)
        assertTrue(runCatching { PrivateIndexKeyRecord.encode(alias + "/", ByteArray(12), ByteArray(48)) }.isFailure)
        assertTrue(runCatching { PrivateIndexKeyRecord.encode(alias + "é", ByteArray(12), ByteArray(48)) }.isFailure)
    }
}
