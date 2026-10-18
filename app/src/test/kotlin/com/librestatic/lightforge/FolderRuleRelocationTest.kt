package com.librestatic.lightforge

import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.preferences.FolderSelectionTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class FolderRuleRelocationTest {
    private fun path(p: String, volume: String = "external_primary") = FolderSelectionTarget.Path(volume, p)
    private fun bucket(id: Long) = FolderSelectionTarget.Bucket("external_primary", id)

    @Test fun anExcludedFolderStaysExcludedAfterARename() {
        val rules = mapOf(path("DCIM/Private/") to false, bucket(11) to false, path("Other/") to true)
        val out = relocateFolderRules(rules, "external_primary", "DCIM/Private/", "external_primary", "DCIM/Vault/", mapOf(11L to 22L))
        assertEquals(false, out[path("DCIM/Vault/")])
        assertEquals(false, out[bucket(22)])
        assertEquals(true, out[path("Other/")])
        assertTrue(path("DCIM/Private/") !in out && bucket(11) !in out)
    }

    @Test fun descendantPathRulesAreRewrittenAndSiblingsWithTheSamePrefixAreNot() {
        val rules = mapOf<FolderSelectionTarget, Boolean>(path("A/B/C/") to false, path("A/Bee/") to false)
        val out = relocateFolderRules(rules, "external_primary", "A/B/", "1234-ABCD", "X/B/", emptyMap())
        assertEquals(false, out[path("X/B/C/", "1234-ABCD")])
        assertEquals(false, out[path("A/Bee/")])
        assertEquals(2, out.size)
    }

    @Test fun aBucketWithoutAKnownNewIdKeepsItsRule() {
        val rules = mapOf<FolderSelectionTarget, Boolean>(bucket(5) to true)
        assertEquals(rules, relocateFolderRules(rules, "external_primary", "A/", "external_primary", "B/", emptyMap()))
    }

    @Test fun bucketRelocationMatchesFoldersByTheirPathBelowTheMovedPrefix() {
        val before = mapOf("A/" to 1L, "A/Sub/" to 2L, "Z/" to 9L)
        val after = mapOf("B/" to 10L, "B/Sub/" to 20L)
        assertEquals(mapOf(1L to 10L, 2L to 20L), bucketRelocation(before, "A/", "B/", after))
    }

    @Test fun storedLocationMustMatchTheRememberedFolderAndName() {
        val source = TransferSource(MediaKey("external_primary", 1), MediaKind.Image, "external_primary", "DCIM/Camera/", "IMG_1.jpg")
        assertTrue(sameStoredLocation(source, "DCIM/Camera", "IMG_1.jpg"))
        assertTrue(!sameStoredLocation(source, "DCIM/Camera/", "IMG_2.jpg"))
        assertTrue(!sameStoredLocation(source, "Pictures/", "IMG_1.jpg"))
        assertTrue(!sameStoredLocation(source, null, null))
    }

    @Test fun sameBytesComparesContentNotLength() {
        val a = ByteArray(200_000) { (it % 251).toByte() }
        val b = a.copyOf().also { it[150_000] = 0 }
        assertTrue(sameBytes(ByteArrayInputStream(a), ByteArrayInputStream(a.copyOf())))
        assertTrue(!sameBytes(ByteArrayInputStream(a), ByteArrayInputStream(b)))
        assertTrue(!sameBytes(ByteArrayInputStream(a), ByteArrayInputStream(a.copyOf(199_999))))
    }
}
