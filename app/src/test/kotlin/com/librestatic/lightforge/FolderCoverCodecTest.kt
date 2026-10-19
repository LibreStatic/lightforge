package com.librestatic.lightforge

import com.librestatic.lightforge.core.model.MediaKey
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderCoverCodecTest {
    @Test
    fun coversRoundTripIncludingVolumesWithColons() {
        val keys = listOf(MediaKey("external_primary", 42), MediaKey("1234-ABCD", 7))
        val stored = keys.withIndex().associate { (i, key) -> "folder$i" to encodeFolderCover(key) }
        assertEquals(mapOf("folder0" to keys[0], "folder1" to keys[1]), decodeFolderCovers(stored))
    }

    @Test
    fun entriesThatDoNotParseAreDropped() {
        val stored = mapOf("a" to "nonsense", "b" to "vol:abc", "c" to ":5", "d" to "vol:-3", "e" to "vol:9")
        assertEquals(mapOf("e" to MediaKey("vol", 9)), decodeFolderCovers(stored))
    }
}
