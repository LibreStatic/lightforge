package com.librestatic.lightforge.core.mediastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ShareTempRegistryTest {
    @Test
    fun `cleanup removes only expired owned share files`() {
        val root = Files.createTempDirectory("lightforge-share").toFile()
        val unrelated = root.resolve("unrelated.txt").apply { writeText("keep") }
        var now = 100_000L
        val registry = ShareTempRegistry(root, { now }, ttlMillis = 1_000)
        val expired = registry.allocate("jpg").apply { writeText("old"); setLastModified(98_000) }
        val current = registry.allocate("jpg").apply { writeText("new"); setLastModified(99_500) }

        assertEquals(1, registry.cleanupExpired())
        assertFalse(expired.exists())
        assertTrue(current.exists())
        assertTrue(unrelated.exists())
        root.deleteRecursively()
    }
}
