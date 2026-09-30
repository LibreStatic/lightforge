package com.librestatic.lightforge.feature.pdfstudio

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PdfPortableArchiveTest {
    private val bytes = "private source bytes".toByteArray()
    private val hash =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun project() =
        PdfProject(
            name = "Archive",
            assets = listOf(PdfAsset(hash, "image/png", 1, 1)),
            pages =
                listOf(
                    PdfPage(images = listOf(PdfImage(asset = hash, width = 10.0, height = 10.0)))
                ),
        )

    private fun zip(file: File, p: PdfProject, content: ByteArray = bytes, extra: Boolean = false) {
        ZipOutputStream(file.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("manifest.json"))
            out.write(PdfCodec.encode(p).toByteArray())
            out.closeEntry()
            if (p.assets.isNotEmpty()) {
                out.putNextEntry(ZipEntry("assets/$hash"))
                out.write(content)
                out.closeEntry()
            }
            if (extra) {
                out.putNextEntry(ZipEntry("../extra"))
                out.write(1)
                out.closeEntry()
            }
        }
    }

    @Test
    fun exactArchiveAndEmptyProjectVerifyWithoutImporting(): Unit = runBlocking {
        val file = Files.createTempFile("portable-verify", ".zip").toFile()
        try {
            val p = project()
            zip(file, p)
            PdfPortableArchive.verify(file, p)
            val empty = PdfProject(name = "Empty")
            zip(file, empty)
            PdfPortableArchive.verify(file, empty)
        } finally {
            file.delete()
        }
    }

    @Test
    fun corruptSourceAndUnexpectedEntryAreRejected(): Unit = runBlocking {
        val file = Files.createTempFile("portable-corrupt", ".zip").toFile()
        val p = project()
        try {
            zip(file, p, content = byteArrayOf(1))
            assertTrue(runCatching { PdfPortableArchive.verify(file, p) }.isFailure)
            zip(file, p, extra = true)
            assertTrue(runCatching { PdfPortableArchive.verify(file, p) }.isFailure)
        } finally {
            file.delete()
        }
    }

    @Test
    fun densePortableManifestIsNotLimitedByThePdfBinderBudget(): Unit = runBlocking {
        val file = Files.createTempFile("portable-dense", ".zip").toFile()
        val p =
            project()
                .copy(
                    pages =
                        List(100) {
                            PdfPage(
                                images =
                                    List(24) { PdfImage(asset = hash, width = 10.0, height = 10.0) }
                            )
                        }
                )
        try {
            val size = PdfCodec.encode(p).toByteArray().size
            assertTrue(size > 384 * 1024 && size <= PdfPortableArchive.MANIFEST_LIMIT)
            zip(file, p)
            PdfPortableArchive.verify(file, p)
        } finally {
            file.delete()
        }
    }

    @Test
    fun differentManifestCannotImpersonateTheQueuedSnapshot(): Unit = runBlocking {
        val file = Files.createTempFile("portable-snapshot", ".zip").toFile()
        val p = project()
        try {
            zip(file, p.copy(name = "Changed"))
            assertTrue(runCatching { PdfPortableArchive.verify(file, p) }.isFailure)
        } finally {
            file.delete()
        }
    }
}
