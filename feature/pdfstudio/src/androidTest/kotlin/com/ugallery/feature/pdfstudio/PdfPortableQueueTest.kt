package com.ugallery.feature.pdfstudio

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PdfPortableQueueTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo = PdfProjectRepository(context)
    private val queue = PdfExportQueue(context)

    private fun marker(name: String) = File(context.filesDir, name)

    private suspend fun fixture(): PdfProject {
        val file = File.createTempFile("portable-queue-", ".png", context.cacheDir)
        val b = Bitmap.createBitmap(20, 40, Bitmap.Config.ARGB_8888)
        file.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        b.recycle()
        return try {
            repo.import(PdfProject(name = "Queued portable"), listOf(Uri.fromFile(file)), 0) { _, _
                ->
            }
        } finally {
            file.delete()
        }
    }

    private suspend fun await(id: String, phase: PdfExportPhase) =
        withTimeout(30_000) {
            while (queue.get(id)?.phase != phase) {
                val row = queue.get(id)!!
                check(row.phase != PdfExportPhase.Failed || phase == PdfExportPhase.Failed) {
                    row.error.orEmpty()
                }
                delay(30)
            }
        }

    private fun destination(): Uri {
        val root = DocumentsContract.buildDocumentUri(PdfTestDocumentsProvider.AUTHORITY, "root")
        val uri =
            DocumentsContract.createDocument(
                context.contentResolver,
                root,
                "application/zip",
                "project.ugpdfproject",
            )!!
        context.grantUriPermission(
            context.packageName,
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        return uri
    }

    private suspend fun cleanup(p: PdfProject, id: String, uri: Uri? = null) {
        listOf(
                "pdf-portable-probe.pause",
                "pdf-portable-probe.entered",
                "pdf-publication-probe.pause",
                "pdf-publication-probe.entered",
                "pdf-destination-denied",
            )
            .forEach { marker(it).delete() }
        queue.cancel(id)
        if (queue.get(id)?.phase == PdfExportPhase.Cancelling) queue.keepDestination(id)
        queue.remove(id)
        repo.delete(p.id)
        uri?.let { runCatching { DocumentsContract.deleteDocument(context.contentResolver, it) } }
    }

    @Test
    fun deletedProjectStillPublishesAnImportableArchive(): Unit = runBlocking {
        val p = fixture()
        val job = queue.enqueue(p, false, portable = true)
        val uri = destination()
        try {
            await(job.id, PdfExportPhase.Ready)
            assertTrue(queue.get(job.id)!!.portable)
            assertEquals(p.assets.size + 1, queue.get(job.id)!!.completed)
            repo.delete(p.id)
            assertTrue(repo.file(p.assets.single().hash).exists())
            queue.publish(job.id, uri)
            await(job.id, PdfExportPhase.Published)
            val restored = repo.importPortable(uri)
            try {
                assertEquals(p.pages, restored.pages)
                assertEquals(p.assets, restored.assets)
            } finally {
                repo.delete(restored.id)
            }
        } finally {
            cleanup(p, job.id, uri)
        }
    }

    @Test
    fun failedDestinationRetainsArchiveAndRetryRepairsCorruptPrivateOutput(): Unit = runBlocking {
        val p = fixture()
        val job = queue.enqueue(p, false, portable = true)
        val uri = destination()
        try {
            await(job.id, PdfExportPhase.Ready)
            marker("pdf-destination-denied").writeText("test")
            queue.publish(job.id, uri)
            await(job.id, PdfExportPhase.Failed)
            PdfPortableArchive.verify(queue.output(job.id), p)
            queue.output(job.id).writeText("corrupt")
            marker("pdf-destination-denied").delete()
            queue.retry(job.id)
            await(job.id, PdfExportPhase.Published)
            val restored = repo.importPortable(uri)
            try {
                assertEquals(p.pages, restored.pages)
            } finally {
                repo.delete(restored.id)
            }
        } finally {
            cleanup(p, job.id, uri)
        }
    }

    @Test
    fun denseProjectQueuesAndImportsBeyondThePdfBinderManifestLimit(): Unit = runBlocking {
        val p = fixture()
        val dense =
            p.copy(
                pages =
                    List(100) {
                        PdfPage(
                            images =
                                List(24) { p.pages.single().images.single().copy(id = newId()) }
                        )
                    }
            )
        assertTrue(PdfCodec.encode(dense).toByteArray().size > 384 * 1024)
        val job = queue.enqueue(dense, false, portable = true)
        try {
            await(job.id, PdfExportPhase.Ready)
            val restored = repo.importPortable(Uri.fromFile(queue.output(job.id)))
            try {
                assertEquals(100, restored.pages.size)
                assertTrue(restored.pages.all { it.images.size == 24 })
            } finally {
                repo.delete(restored.id)
            }
        } finally {
            cleanup(p, job.id)
        }
    }

    @Test
    fun cancellationDuringPackagingRemovesItsPartialFile(): Unit = runBlocking {
        val p = fixture()
        marker("pdf-portable-probe.pause").writeText("test")
        val job = queue.enqueue(p, false, portable = true)
        try {
            withTimeout(20_000) { while (!marker("pdf-portable-probe.entered").exists()) delay(20) }
            queue.cancel(job.id)
            assertEquals(PdfExportPhase.Cancelled, queue.get(job.id)!!.phase)
            assertFalse(queue.output(job.id).exists())
            assertFalse(queue.partial(job.id, job.workId).exists())
        } finally {
            cleanup(p, job.id)
        }
    }

    @Test
    fun cancellationDuringArchivePublicationCleansOnlyItsPartialDestination(): Unit = runBlocking {
        val p = fixture()
        val job = queue.enqueue(p, false, portable = true)
        val uri = destination()
        try {
            await(job.id, PdfExportPhase.Ready)
            marker("pdf-publication-probe.pause").writeText("test")
            queue.publish(job.id, uri)
            withTimeout(20_000) {
                while (!marker("pdf-publication-probe.entered").exists()) delay(20)
            }
            queue.cancel(job.id)
            assertEquals(PdfExportPhase.Cancelled, queue.get(job.id)!!.phase)
            assertTrue(
                runCatching { context.contentResolver.openInputStream(uri)!!.close() }.isFailure
            )
            assertFalse(context.contentResolver.persistedUriPermissions.any { it.uri == uri })
        } finally {
            cleanup(p, job.id, uri)
        }
    }
}
