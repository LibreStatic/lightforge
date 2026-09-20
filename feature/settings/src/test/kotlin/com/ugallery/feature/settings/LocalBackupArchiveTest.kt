package com.ugallery.feature.settings

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class LocalBackupArchiveTest {
    private fun temporary(block: (File) -> Unit) {
        val root = Files.createTempDirectory("ugallery-backup-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun source(name: String = "original.jpg", bytes: ByteArray = byteArrayOf(1, 2, 3)) =
        LocalBackupArchive.Source(name, "image/jpeg") { ByteArrayInputStream(bytes) }

    private class Target(
        private val failWrite: Boolean = false,
        private val failCommit: Boolean = false,
    ) : LocalBackupArchive.RestoreTarget {
        val outputs = mutableListOf<ByteArrayOutputStream>()
        var aborted = false
        var committed = false

        override fun open(name: String, mime: String): OutputStream {
            if (failWrite) throw IOException("Injected storage failure")
            return ByteArrayOutputStream().also { outputs += it }
        }

        override fun commit() {
            if (failCommit) throw IOException("Destination hash mismatch")
            committed = true
        }

        override fun abort() {
            aborted = true
            outputs.clear()
        }
    }

    @Test
    fun originalBytesAndDuplicateNamesRoundTripWithoutReassociation() = temporary { root ->
        val archive = File(root, "backup.zip")
        val original = ByteArray(100_001) { (it % 251).toByte() }
        val manifest =
            LocalBackupArchive.create(
                listOf(source("fóto.jpg", original), source("fóto.jpg")),
                archive,
            )
        assertEquals(manifest, BackupManifest.decode(manifest.encode()))
        assertEquals(manifest, LocalBackupArchive.inspect(archive))
        assertEquals(100_004L, manifest.totalBytes)
        assertEquals(64, manifest.entries.first().sha256.length)
        val target = Target()
        LocalBackupArchive.restore(archive, manifest, target)
        assertTrue(target.committed)
        assertFalse(target.aborted)
        assertArrayEquals(original, target.outputs[0].toByteArray())
        assertArrayEquals(byteArrayOf(1, 2, 3), target.outputs[1].toByteArray())
    }

    @Test
    fun emptyFileAndVideoAndDocumentRemainByteExact() = temporary { root ->
        val sources =
            listOf(
                LocalBackupArchive.Source("empty.pdf", "application/pdf") {
                    ByteArrayInputStream(byteArrayOf())
                },
                LocalBackupArchive.Source("clip.mp4", "video/mp4") {
                    ByteArrayInputStream(byteArrayOf(7, 8))
                },
            )
        val archive = File(root, "backup.zip")
        val manifest = LocalBackupArchive.create(sources, archive)
        val target = Target()
        LocalBackupArchive.restore(archive, manifest, target)
        assertEquals(listOf(0L, 2L), manifest.entries.map { it.bytes })
        assertTrue(target.committed)
    }

    @Test
    fun unsupportedVersionAndMalformedEncodingAreRejected() = temporary { root ->
        val manifest = LocalBackupArchive.create(listOf(source()), File(root, "archive"))
        assertThrows(IOException::class.java) {
            BackupManifest.decode(
                manifest
                    .encode()
                    .toString(Charsets.UTF_8)
                    .replace("ORIGINALS\t1", "ORIGINALS\t2")
                    .toByteArray()
            )
        }
        assertThrows(IOException::class.java) {
            BackupManifest.decode(
                manifest
                    .encode()
                    .toString(Charsets.UTF_8)
                    .replace("b3JpZ2luYWwuanBn", "!!")
                    .toByteArray()
            )
        }
    }

    @Test
    fun pathTraversalAndAbsolutePathsNeverReachDestination() = temporary { root ->
        listOf("../escape", "/absolute", "originals/00000001", "originals/00000000/child")
            .forEach { path ->
                val file = File(root, "malicious.zip")
                ZipOutputStream(file.outputStream()).use {
                    it.putNextEntry(ZipEntry(path))
                    it.write(1)
                    it.closeEntry()
                }
                assertThrows(IOException::class.java) { LocalBackupArchive.inspect(file) }
                assertFalse(File(root.parentFile, "escape").exists())
            }
    }

    @Test
    fun invalidNamesAreRejectedBeforeSourceRead() = temporary { root ->
        listOf("../photo.jpg", ".", "..", "bad\\name.jpg", "", "nul\u0000.jpg").forEach { name ->
            assertThrows(IOException::class.java) {
                LocalBackupArchive.create(listOf(source(name)), File(root, "archive"))
            }
        }
    }

    @Test
    fun missingManifestAndUnknownTrailingEntryAreRejected() = temporary { root ->
        val file = File(root, "archive")
        ZipOutputStream(file.outputStream()).use {
            it.putNextEntry(ZipEntry(BackupManifest.path(0)))
            it.write(1)
            it.closeEntry()
        }
        assertThrows(IOException::class.java) { LocalBackupArchive.inspect(file) }
        val manifest = LocalBackupArchive.create(listOf(source()), file)
        ZipOutputStream(file.outputStream()).use {
            it.putNextEntry(ZipEntry(BackupManifest.path(0)))
            it.write(byteArrayOf(1, 2, 3))
            it.closeEntry()
            it.putNextEntry(ZipEntry(BackupManifest.PATH))
            it.write(manifest.encode())
            it.closeEntry()
            it.putNextEntry(ZipEntry("extra"))
            it.write(5)
            it.closeEntry()
        }
        assertThrows(IOException::class.java) { LocalBackupArchive.inspect(file) }
    }

    @Test
    fun digestMismatchBlocksRestoreBeforeAnyDestinationWrites() = temporary { root ->
        val file = File(root, "archive")
        val manifest = LocalBackupArchive.create(listOf(source()), file)
        ZipOutputStream(file.outputStream()).use {
            it.putNextEntry(ZipEntry(BackupManifest.path(0)))
            it.write(byteArrayOf(3, 2, 1))
            it.closeEntry()
            it.putNextEntry(ZipEntry(BackupManifest.PATH))
            it.write(manifest.encode())
            it.closeEntry()
        }
        val target = Target()
        assertThrows(IOException::class.java) { LocalBackupArchive.restore(file, manifest, target) }
        assertTrue(target.outputs.isEmpty())
        assertFalse(target.committed)
    }

    @Test
    fun changedPreviewDoesNotStartRestoring() = temporary { root ->
        val file = File(root, "archive")
        val old = LocalBackupArchive.create(listOf(source()), file)
        LocalBackupArchive.create(listOf(source(bytes = byteArrayOf(9))), file)
        val target = Target()
        assertThrows(IOException::class.java) { LocalBackupArchive.restore(file, old, target) }
        assertTrue(target.outputs.isEmpty())
    }

    @Test
    fun cancellationDeletesIncompleteArchive() = temporary { root ->
        val file = File(root, "archive")
        assertThrows(CancellationException::class.java) {
            LocalBackupArchive.create(listOf(source()), file, { throw CancellationException() })
        }
        assertFalse(file.exists())
    }

    @Test
    fun sourceFailureDeletesIncompleteArchive() = temporary { root ->
        val file = File(root, "archive")
        assertThrows(IOException::class.java) {
            LocalBackupArchive.create(
                listOf(
                    LocalBackupArchive.Source("test.jpg", "image/jpeg") {
                        throw IOException("Revoked")
                    }
                ),
                file,
            )
        }
        assertFalse(file.exists())
    }

    @Test
    fun writeFailureRollsBackOnlyTransaction() = temporary { root ->
        val file = File(root, "archive")
        val manifest = LocalBackupArchive.create(listOf(source()), file)
        val target = Target(failWrite = true)
        assertThrows(IOException::class.java) { LocalBackupArchive.restore(file, manifest, target) }
        assertTrue(target.aborted)
        assertFalse(target.committed)
        assertTrue(file.exists())
    }

    @Test
    fun destinationVerificationFailureRollsBack() = temporary { root ->
        val file = File(root, "archive")
        val manifest = LocalBackupArchive.create(listOf(source()), file)
        val target = Target(failCommit = true)
        assertThrows(IOException::class.java) { LocalBackupArchive.restore(file, manifest, target) }
        assertTrue(target.aborted)
        assertFalse(target.committed)
    }

    @Test
    fun cancellationAfterFirstCopyRemovesPartialRestore() = temporary { root ->
        val file = File(root, "archive")
        val manifest = LocalBackupArchive.create(listOf(source(), source()), file)
        val target = Target()
        var cancel = false
        assertThrows(CancellationException::class.java) {
            LocalBackupArchive.restore(
                file,
                manifest,
                target,
                { if (cancel) throw CancellationException() },
                { cancel = true },
            )
        }
        assertTrue(target.aborted)
        assertTrue(target.outputs.isEmpty())
    }

    @Test
    fun cleanupFailureIsPreservedForUserFeedback() = temporary { root ->
        val file = File(root, "archive")
        val manifest = LocalBackupArchive.create(listOf(source()), file)
        val target =
            object : LocalBackupArchive.RestoreTarget {
                override fun open(name: String, mime: String): OutputStream {
                    throw IOException("Write failed")
                }

                override fun commit() {}

                override fun abort() {
                    throw IOException("Cleanup failed")
                }
            }
        val error =
            assertThrows(IOException::class.java) {
                LocalBackupArchive.restore(file, manifest, target)
            }
        assertEquals("Cleanup failed", error.suppressed.single().message)
    }

    @Test
    fun streamedLimitStopsUnboundedInputWithoutGrowingMemory() {
        var reads = 0
        val input =
            object : InputStream() {
                override fun read(): Int = 1

                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    reads++
                    java.util.Arrays.fill(bytes, offset, offset + length, 1.toByte())
                    return length
                }
            }
        val sink =
            object : OutputStream() {
                override fun write(value: Int) {}

                override fun write(bytes: ByteArray, offset: Int, length: Int) {}
            }
        assertThrows(IOException::class.java) {
            LocalBackupArchive.copyChecked(input, sink, 128 * 1024L, {})
        }
        assertEquals(3, reads)
    }

    @Test
    fun largeOriginalUsesBoundedStreamingBuffers() = temporary { root ->
        val totalBytes = 32L * 1024 * 1024
        var largestRead = 0
        val source =
            LocalBackupArchive.Source("large.mp4", "video/mp4") {
                object : InputStream() {
                    var remaining = totalBytes

                    override fun read(): Int = if (remaining-- > 0) 42 else -1

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (remaining == 0L) return -1
                        largestRead = maxOf(largestRead, length)
                        val count = minOf(length.toLong(), remaining).toInt()
                        java.util.Arrays.fill(buffer, offset, offset + count, 42.toByte())
                        remaining -= count
                        return count
                    }
                }
            }
        val archive = File(root, "large.zip")
        val manifest = LocalBackupArchive.create(listOf(source), archive)
        assertEquals(totalBytes, manifest.totalBytes)
        assertEquals(manifest, LocalBackupArchive.inspect(archive))
        assertTrue(largestRead <= 64 * 1024)
    }

    @Test(timeout = 5000)
    fun maliciousSeparatorCountsAreBoundedBeforeParsingRowsAndFields() {
        val tooManyRows = "UGALLERY-ORIGINALS\t1\n" + "\n".repeat(1_000_000)
        assertThrows(IOException::class.java) { BackupManifest.decode(tooManyRows.toByteArray()) }
        val tooManyFields = "UGALLERY-ORIGINALS\t1\n" + "\t".repeat(1_000_000) + "\n"
        assertThrows(IOException::class.java) { BackupManifest.decode(tooManyFields.toByteArray()) }
    }

    @Test
    fun emptyOrExcessiveSelectionRejected() = temporary { root ->
        assertThrows(IOException::class.java) {
            LocalBackupArchive.create(emptyList(), File(root, "archive"))
        }
        assertThrows(IOException::class.java) {
            LocalBackupArchive.create(
                List(BackupManifest.MAX_ENTRIES + 1) { source() },
                File(root, "archive"),
            )
        }
    }
}
