package com.ugallery.feature.settings

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class LocalBackupSessionTest {
    private fun temporary(block: (File) -> Unit) {
        val root = Files.createTempDirectory("local-backup-session").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun tenThousandSelectionsPersistOutsideSavedStateAndReopenBySingleUuid() = temporary { root ->
        val id = UUID.randomUUID().toString()
        val values = List(10_000) { "content://fixture/original/$it/" + "x".repeat(200) }
        val session = LocalBackupSession(root, id)
        session.saveSelection(values)
        assertTrue(File(session.directory, "selection.bin").length() > 1024 * 1024)
        assertEquals(values, LocalBackupSession(root, id).readSelection())
        assertEquals(36, id.length)
    }

    @Test
    fun leavingCleansOnlyItsOwnSession() = temporary { root ->
        val first = LocalBackupSession(root, UUID.randomUUID().toString())
        val second = LocalBackupSession(root, UUID.randomUUID().toString())
        File(first.directory, "archive.zip").writeText("first")
        File(second.directory, "archive.zip").writeText("second")
        first.close()
        assertFalse(first.directory.exists())
        assertEquals("second", File(second.directory, "archive.zip").readText())
    }

    @Test
    fun startupSweepPreservesRestoredRecentAndUnrelatedDirectories() = temporary { root ->
        val now = System.currentTimeMillis()
        fun folder(name: String, old: Boolean): File =
            File(root, name).apply {
                mkdir()
                File(this, "cache").writeText("bytes")
                if (old) setLastModified(now - 48L * 60 * 60 * 1000)
            }
        val retained = folder(UUID.randomUUID().toString(), true)
        val stale = folder(UUID.randomUUID().toString(), true)
        val recent = folder(UUID.randomUUID().toString(), false)
        val unrelated = folder("other-feature", true)
        LocalBackupSession.pruneStale(root, retained.name, now)
        assertTrue(retained.exists())
        assertFalse(stale.exists())
        assertTrue(recent.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun invalidSessionIdentityAndOversizedSelectionsAreRejected() = temporary { root ->
        assertThrows(IllegalArgumentException::class.java) { LocalBackupSession(root, "../other") }
        val session = LocalBackupSession(root, UUID.randomUUID().toString())
        assertThrows(IllegalArgumentException::class.java) {
            session.saveSelection(List(10_001) { "content://fixture/$it" })
        }
        assertThrows(IllegalArgumentException::class.java) {
            session.saveSelection(listOf("file:///etc/passwd"))
        }
        assertTrue(session.readSelection().isEmpty())
    }
}
