package com.librestatic.lightforge.feature.settings

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalBackupOrganizationArchiveTest {
    private fun temporary(block: suspend (File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("backup-organization-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun sources() =
        listOf("first.jpg", "second.jpg").map { name ->
            LocalBackupArchive.Source(name, "image/jpeg", "content://fixture/$name") {
                ByteArrayInputStream(byteArrayOf(1, 2, 3))
            }
        }

    private class Port : LocalBackupOrganizationPort {
        var exported = emptyList<LocalBackupSourceRef>()
        var sidecar = LocalBackupSidecar(byteArrayOf(5, 6, 7))
        var allowed = true
        var begun = 0
        var aborted = false
        var committed = false
        var partial = false
        var cancelStage = false
        var failCommit = false
        var checkLifetime = false
        var options: LocalRestoreOrganizationOptions? = null
        val outputs = mutableListOf<ByteArray>()

        override suspend fun export(sources: List<LocalBackupSourceRef>): LocalBackupSidecar {
            exported = sources
            return sidecar
        }

        override suspend fun review(sidecar: ByteArray, manifest: BackupManifest) =
            LocalBackupOrganizationReview(
                listOf(LocalBackupOrganizationCount(LocalBackupOrganizationKind.Albums, 1)),
                manifest.entries.size,
                0,
                0,
                1,
                allowed,
            )

        override suspend fun beginRestore(
            sidecar: ByteArray,
            manifest: BackupManifest,
            options: LocalRestoreOrganizationOptions,
        ): LocalRestoreGallerySession {
            begun++
            this.options = options
            return object : LocalRestoreGallerySession {
                var retained: InputStream? = null

                override suspend fun stage(entry: BackupManifest.Entry, input: InputStream) {
                    retained = input
                    if (partial) input.read() else outputs += input.readBytes()
                    if (cancelStage) throw CancellationException("Injected cancellation")
                }

                override suspend fun commit(): LocalRestoreGalleryResult {
                    if (failCommit) throw IOException("Injected metadata transaction failure")
                    if (checkLifetime) assertThrows(IOException::class.java) { retained!!.read() }
                    committed = true
                    return LocalRestoreGalleryResult(outputs.size, 1)
                }

                override suspend fun abort() {
                    aborted = true
                    outputs.clear()
                }
            }
        }
    }

    @Test
    fun explicitMemorySchemaThreeSurvivesArchiveEnvelopeAndRestore() = temporary { root ->
        val port = Port().apply { sidecar = LocalBackupSidecar(byteArrayOf(5, 6, 7), schemaVersion = 3) }
        val archive = File(root, "manual-memory.zip")
        val manifest = LocalBackupArchive.createOrganized(sources(), archive, port)
        assertEquals(2, manifest.version) // Container remains version2, independently of payload3.
        assertEquals(3, manifest.organization!!.schemaVersion)
        assertEquals(manifest, LocalBackupArchive.inspect(archive))
        assertArrayEquals(port.sidecar.bytes, LocalBackupArchive.readOrganization(archive, manifest))
        assertEquals(2, LocalBackupArchive.restoreGallery(archive, manifest, port).files)
        assertTrue(port.committed)
        assertFalse(port.aborted)
    }

    @Test
    fun versionTwoOrganizationDescriptorRoundTripsWithoutChangingLegacyArchives() = temporary { root ->
        val port = Port().apply { sidecar = LocalBackupSidecar(byteArrayOf(5, 6, 7), schemaVersion = 2) }
        val archive = File(root, "photo-recipes.zip")
        val manifest = LocalBackupArchive.createOrganized(sources(), archive, port)
        assertEquals(2, manifest.organization!!.schemaVersion)
        assertEquals(manifest, BackupManifest.decode(manifest.encode()))
        val legacy = manifest.copy(organization = manifest.organization!!.copy(schemaVersion = 1))
        assertEquals(legacy, BackupManifest.decode(legacy.encode()))
    }

    @Test
    fun v1StillRoundTripsWithoutOrganizationOrUuidRequirement() = temporary { root ->
        val file = File(root, "v1.zip")
        val manifest = LocalBackupArchive.create(sources(), file)
        assertEquals(1, manifest.version)
        assertNull(manifest.organization)
        assertEquals(manifest, LocalBackupArchive.inspect(file))
        assertEquals(manifest.entries[0].path, manifest.entries[0].sourceId)
    }

    @Test
    fun v2PreservesIdenticalFilesAsDistinctPortableSourcesAndBinarySidecar() = temporary { root ->
        val file = File(root, "v2.zip")
        val port = Port()
        val manifest = LocalBackupArchive.createOrganized(sources(), file, port)
        assertEquals(2, manifest.version)
        assertEquals(manifest, LocalBackupArchive.inspect(file))
        assertEquals(2, manifest.entries.map { it.sourceId }.distinct().size)
        assertEquals(1, manifest.entries.map { it.sha256 }.distinct().size)
        assertEquals(manifest.entries, port.exported.map { it.entry })
        assertArrayEquals(port.sidecar.bytes, LocalBackupArchive.readOrganization(file, manifest))
        assertFalse(manifest.encode().toString(Charsets.UTF_8).contains("content://"))
        val result = LocalBackupArchive.restoreGallery(file, manifest, port)
        assertEquals(2, result.files)
        assertTrue(port.committed)
        assertFalse(port.aborted)
        assertEquals(2, port.outputs.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), port.outputs[0])
    }

    @Test
    fun defaultRestoreNeverEnablesGlobalRulesAndExplicitOptInIsPassed() = temporary { root ->
        val file = File(root, "v2.zip")
        val port = Port()
        val manifest = LocalBackupArchive.createOrganized(sources(), file, port)
        LocalBackupArchive.restoreGallery(file, manifest, port)
        assertEquals(false, port.options!!.importGlobalRules)
        LocalBackupArchive.restoreGallery(
            file,
            manifest,
            port,
            LocalRestoreOrganizationOptions(true),
        )
        assertEquals(true, port.options!!.importGlobalRules)
    }

    @Test
    fun tamperedSidecarBlocksReviewAndRestoreBeforeAnyNewDestination() = temporary { root ->
        val file = File(root, "v2.zip")
        val port = Port()
        val manifest = LocalBackupArchive.createOrganized(sources(), file, port)
        val original = file.readBytes()
        ZipInputStream(ByteArrayInputStream(original)).use { input ->
            ZipOutputStream(file.outputStream()).use { output ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    output.putNextEntry(ZipEntry(entry.name))
                    val bytes = input.readBytes()
                    output.write(
                        if (entry.name == BackupManifest.ORGANIZATION_PATH) byteArrayOf(9, 9, 9)
                        else bytes
                    )
                    output.closeEntry()
                    input.closeEntry()
                }
            }
        }
        assertThrows(IOException::class.java) { LocalBackupArchive.inspect(file) }
        try {
            LocalBackupArchive.restoreGallery(file, manifest, port)
            fail("Expected integrity failure")
        } catch (_: IOException) {}
        assertEquals(0, port.begun)
    }

    @Test
    fun duplicateSourceIdsAndUnsupportedSidecarVersionsAreRejected() = temporary { root ->
        val file = File(root, "v2.zip")
        val manifest = LocalBackupArchive.createOrganized(sources(), file, Port())
        val duplicate =
            manifest.copy(
                entries =
                    manifest.entries.map { it.copy(sourceId = manifest.entries.first().sourceId) }
            )
        assertThrows(IOException::class.java) { BackupManifest.decode(duplicate.encode()) }
        val unknown = manifest.copy(organization = manifest.organization!!.copy(schemaVersion = 9))
        assertThrows(IOException::class.java) { BackupManifest.decode(unknown.encode()) }
    }

    @Test
    fun invalidExportPayloadDeletesIncompleteArchive() = temporary { root ->
        val file = File(root, "v2.zip")
        val port = Port()
        port.sidecar = LocalBackupSidecar(byteArrayOf())
        try {
            LocalBackupArchive.createOrganized(sources(), file, port)
            fail("Expected payload failure")
        } catch (_: IOException) {}
        assertFalse(file.exists())
    }

    @Test
    fun partialConsumerTriggersRollbackAndNeverCommits() = temporary { root ->
        val file = File(root, "v2.zip")
        val port = Port()
        val manifest = LocalBackupArchive.createOrganized(sources(), file, port)
        port.partial = true
        try {
            LocalBackupArchive.restoreGallery(file, manifest, port)
            fail("Expected partial consumer failure")
        } catch (_: IOException) {}
        assertTrue(port.aborted)
        assertFalse(port.committed)
    }

    @Test
    fun cancelledGalleryStageRollsBackCopiesAndPreservesBackup() = temporary { root ->
        val file = File(root, "v2.zip")
        val port = Port()
        val manifest = LocalBackupArchive.createOrganized(sources(), file, port)
        port.cancelStage = true
        try {
            LocalBackupArchive.restoreGallery(file, manifest, port)
            fail("Expected cancellation")
        } catch (_: CancellationException) {}
        assertTrue(port.aborted)
        assertTrue(port.outputs.isEmpty())
        assertEquals(manifest, LocalBackupArchive.inspect(file))
    }

    @Test
    fun failedMetadataCommitRollsBackCopies() = temporary { root ->
        val file = File(root, "v2.zip")
        val port = Port()
        val manifest = LocalBackupArchive.createOrganized(sources(), file, port)
        port.failCommit = true
        try {
            LocalBackupArchive.restoreGallery(file, manifest, port)
            fail("Expected commit failure")
        } catch (_: IOException) {}
        assertTrue(port.aborted)
        assertFalse(port.committed)
        assertTrue(port.outputs.isEmpty())
    }

    @Test
    fun unresolvedReviewCanBlockGalleryWithoutCreatingRows() = temporary { root ->
        val file = File(root, "v2.zip")
        val port = Port()
        val manifest = LocalBackupArchive.createOrganized(sources(), file, port)
        port.allowed = false
        try {
            LocalBackupArchive.restoreGallery(file, manifest, port)
            fail("Expected blocked review")
        } catch (_: IOException) {}
        assertEquals(0, port.begun)
    }

    @Test
    fun entryStreamLifetimeEndsBeforeCommit() = temporary { root ->
        val file = File(root, "v2.zip")
        val port = Port()
        val manifest = LocalBackupArchive.createOrganized(sources(), file, port)
        port.checkLifetime = true
        LocalBackupArchive.restoreGallery(file, manifest, port)
        assertTrue(port.committed)
    }
}
