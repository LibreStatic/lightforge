package com.ugallery.feature.privatealbum

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PrivateImportCleanupTest {
    @Test fun removesOnlyOwnedFilesAndAcceptsExactAbsence() {
        val root=Files.createTempDirectory("private-cleanup-").toFile()
        val stage=root.resolve("owned.import-staging").apply { writeText("ciphertext") }
        val final=root.resolve("owned.ugpc").apply { writeText("ciphertext") }
        val neighbor=root.resolve("neighbor.ugpc").apply { writeText("keep") }
        try {
            assertNull(cleanupPrivateImportFiles(listOf(stage,final,root.resolve("absent"))))
            assertFalse(stage.exists());assertFalse(final.exists());assertEquals("keep",neighbor.readText())
        } finally { check(neighbor.delete());check(root.delete()) }
    }
    @Test fun realUnlinkFailureIsReportedAndDoesNotPreventOtherOwnedCleanup() {
        val root=Files.createTempDirectory("private-cleanup-").toFile()
        val blocked=root.resolve("owned.import-staging").apply { mkdir() }
        val child=blocked.resolve("retained").apply { writeText("keep") }
        val final=root.resolve("owned.ugpc").apply { writeText("ciphertext") }
        try {
            val failure=cleanupPrivateImportFiles(listOf(blocked,final))
            assertNotNull(failure);assertTrue(failure!!.cause is java.nio.file.DirectoryNotEmptyException)
            assertFalse(final.exists());assertEquals("keep",child.readText())
        } finally { check(child.delete());check(blocked.delete());check(root.delete()) }
    }
}
