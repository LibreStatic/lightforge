package com.librestatic.lightforge.feature.pdfstudio

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PdfPublicationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo = PdfProjectRepository(context)
    private val queue = PdfExportQueue(context)
    private val flags =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    private fun marker(name: String) = File(context.filesDir, name)

    private suspend fun fixture(): Pair<PdfProject, PdfExportJob> {
        val f = File.createTempFile("publication-", ".png", context.cacheDir)
        val b = Bitmap.createBitmap(20, 40, Bitmap.Config.ARGB_8888)
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        b.recycle()
        val p =
            try {
                repo.import(
                    PdfProject(name = "Publication lifecycle"),
                    listOf(Uri.fromFile(f)),
                    0,
                ) { _, _ ->
                }
            } finally {
                f.delete()
            }
        val job = queue.enqueue(p, false)
        await(job.id, PdfExportPhase.Ready)
        return p to job
    }

    private fun destination(): Uri {
        val root = DocumentsContract.buildDocumentUri(PdfTestDocumentsProvider.AUTHORITY, "root")
        val uri =
            DocumentsContract.createDocument(
                context.contentResolver,
                root,
                "application/pdf",
                "publication-test.pdf",
            )!!
        context.grantUriPermission(
            context.packageName,
            uri,
            flags or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        return uri
    }

    private suspend fun await(id: String, phase: PdfExportPhase) =
        withTimeout(25_000) {
            while (queue.get(id)?.phase != phase) {
                val current = queue.get(id)
                check(current?.phase != PdfExportPhase.Failed || phase == PdfExportPhase.Failed) {
                    current?.error.orEmpty()
                }
                delay(30)
            }
        }

    private suspend fun entered() =
        withTimeout(20_000) { while (!marker("pdf-publication-probe.entered").exists()) delay(30) }

    private suspend fun grantReleased(uri: Uri) =
        withTimeout(10_000) {
            while (
                PdfProjectDatabase.get(context).destinationGrants().get(uri.toString()) != null
            ) delay(20)
        }

    private suspend fun cleanup(p: PdfProject, job: PdfExportJob, uris: List<Uri>) {
        listOf(
                "pdf-publication-probe.pause",
                "pdf-publication-probe.entered",
                "pdf-publication-probe.complete",
                "pdf-publication-probe.truncate",
                "pdf-destination-denied",
                "pdf-destination-read-denied",
                "pdf-destination-delete-denied",
            )
            .forEach { marker(it).delete() }
        queue.cancel(job.id)
        if (queue.get(job.id)?.phase == PdfExportPhase.Cancelling) queue.keepDestination(job.id)
        queue.remove(job.id)
        repo.delete(p.id)
        uris.forEach {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, it) }
            runCatching { context.contentResolver.releasePersistableUriPermission(it, flags) }
        }
    }

    @Test
    fun explicitCancelRemovesOnlyItsPartialDestination(): Unit = runBlocking {
        val (p, job) = fixture()
        val uri = destination()
        marker("pdf-publication-probe.pause").writeText("test")
        try {
            queue.publish(job.id, uri)
            entered()
            assertEquals(PdfExportPhase.Publishing, queue.get(job.id)!!.phase)
            assertTrue(
                context.contentResolver.openInputStream(uri)!!.use { it.readBytes().size } in 1..80
            )
            queue.cancel(job.id)
            assertEquals(PdfExportPhase.Cancelled, queue.get(job.id)!!.phase)
            assertTrue(
                runCatching { context.contentResolver.openInputStream(uri)!!.close() }.isFailure
            )
            grantReleased(uri)
            assertFalse(context.contentResolver.persistedUriPermissions.any { it.uri == uri })
        } finally {
            cleanup(p, job, listOf(uri))
        }
    }

    @Test
    fun cancellationAfterCompleteWritePreservesVerifiedPdf(): Unit = runBlocking {
        val (p, job) = fixture()
        val uri = destination()
        val expected = queue.output(job.id).readBytes()
        marker("pdf-publication-probe.pause").writeText("test")
        marker("pdf-publication-probe.complete").writeText("test")
        try {
            queue.publish(job.id, uri)
            entered()
            queue.cancel(job.id)
            assertEquals(PdfExportPhase.Published, queue.get(job.id)!!.phase)
            assertArrayEquals(
                expected,
                context.contentResolver.openInputStream(uri)!!.use { it.readBytes() },
            )
        } finally {
            cleanup(p, job, listOf(uri))
        }
    }

    @Test
    fun cancellationPreservesExternallyChangedDestination(): Unit = runBlocking {
        val (p, job) = fixture()
        val uri = destination()
        marker("pdf-publication-probe.pause").writeText("test")
        try {
            queue.publish(job.id, uri)
            entered()
            context.contentResolver.openOutputStream(uri, "wt")!!.use {
                it.write("Edited externally".toByteArray())
            }
            queue.cancel(job.id)
            assertEquals(PdfExportPhase.Cancelled, queue.get(job.id)!!.phase)
            assertEquals("DestinationPreserved", queue.get(job.id)!!.error)
            assertEquals(
                "Edited externally",
                context.contentResolver.openInputStream(uri)!!.bufferedReader().use {
                    it.readText()
                },
            )
        } finally {
            cleanup(p, job, listOf(uri))
        }
    }

    @Test
    fun deniedCleanupStaysPendingUntilAccessReturns(): Unit = runBlocking {
        val (p, job) = fixture()
        val uri = destination()
        marker("pdf-publication-probe.pause").writeText("test")
        try {
            queue.publish(job.id, uri)
            entered()
            marker("pdf-destination-delete-denied").writeText("test")
            queue.cancel(job.id)
            assertEquals(PdfExportPhase.Cancelling, queue.get(job.id)!!.phase)
            assertTrue(queue.output(job.id).exists())
            assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == uri })
            marker("pdf-destination-delete-denied").delete()
            queue.reconcile()
            assertEquals(PdfExportPhase.Cancelled, queue.get(job.id)!!.phase)
            assertTrue(
                runCatching { context.contentResolver.openInputStream(uri)!!.close() }.isFailure
            )
        } finally {
            cleanup(p, job, listOf(uri))
        }
    }

    @Test
    fun readbackRejectsTruncatedOutputAndRemovalCleansIt(): Unit = runBlocking {
        val (p, job) = fixture()
        val uri = destination()
        marker("pdf-publication-probe.truncate").writeText("test")
        try {
            queue.publish(job.id, uri)
            await(job.id, PdfExportPhase.Failed)
            assertTrue(queue.output(job.id).isFile)
            assertEquals(
                80,
                context.contentResolver.openInputStream(uri)!!.use { it.readBytes().size },
            )
            queue.remove(job.id)
            assertNull(queue.get(job.id))
            assertFalse(queue.output(job.id).exists())
            assertTrue(
                runCatching { context.contentResolver.openInputStream(uri)!!.close() }.isFailure
            )
            assertFalse(context.contentResolver.persistedUriPermissions.any { it.uri == uri })
        } finally {
            cleanup(p, job, listOf(uri))
        }
    }

    @Test
    fun unrelatedNonEmptyDestinationIsNeverAdopted(): Unit = runBlocking {
        val (p, job) = fixture()
        val uri = destination()
        try {
            context.contentResolver.openOutputStream(uri, "wt")!!.use {
                it.write("Existing document".toByteArray())
            }
            assertTrue(runCatching { queue.publish(job.id, uri) }.isFailure)
            assertEquals(PdfExportPhase.Ready, queue.get(job.id)!!.phase)
            assertNull(queue.get(job.id)!!.destination)
            assertFalse(context.contentResolver.persistedUriPermissions.any { it.uri == uri })
            assertEquals(
                "Existing document",
                context.contentResolver.openInputStream(uri)!!.bufferedReader().use {
                    it.readText()
                },
            )
        } finally {
            cleanup(p, job, listOf(uri))
        }
    }

    @Test
    fun abandonedAcquisitionJournalReleasesUnboundGrant(): Unit = runBlocking {
        val uri = destination()
        val grants = PdfProjectDatabase.get(context).destinationGrants()
        try {
            grants.put(PdfDestinationGrant(uri.toString(), flags))
            context.contentResolver.takePersistableUriPermission(uri, flags)
            queue.reconcile()
            assertNull(grants.get(uri.toString()))
            assertFalse(context.contentResolver.persistedUriPermissions.any { it.uri == uri })
        } finally {
            grants.delete(uri.toString())
            runCatching { context.contentResolver.releasePersistableUriPermission(uri, flags) }
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        }
    }

    @Test
    fun explicitKeepFinishesCancellationWhenDestinationCannotBeRead(): Unit = runBlocking {
        val (p, job) = fixture()
        val uri = destination()
        marker("pdf-publication-probe.pause").writeText("test")
        try {
            queue.publish(job.id, uri)
            entered()
            marker("pdf-destination-read-denied").writeText("test")
            queue.cancel(job.id)
            assertEquals(PdfExportPhase.Cancelling, queue.get(job.id)!!.phase)
            queue.keepDestination(job.id)
            assertEquals(PdfExportPhase.Cancelled, queue.get(job.id)!!.phase)
            assertEquals("DestinationPreserved", queue.get(job.id)!!.error)
            assertFalse(queue.output(job.id).exists())
            grantReleased(uri)
            marker("pdf-destination-read-denied").delete()
            assertTrue(
                context.contentResolver.openInputStream(uri)!!.use { it.readBytes().isNotEmpty() }
            )
        } finally {
            cleanup(p, job, listOf(uri))
        }
    }

    @Test
    fun replacementReleasesOnlyOwnedGrantsAndRepairsCorruptPrivateOutput(): Unit = runBlocking {
        val (p, job) = fixture()
        val old = destination()
        val next = destination()
        // An unrelated feature already owns read access; PDF may release only the write upgrade.
        context.contentResolver.takePersistableUriPermission(
            next,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        try {
            marker("pdf-destination-denied").writeText("test")
            queue.publish(job.id, old)
            await(job.id, PdfExportPhase.Failed)
            marker("pdf-destination-denied").delete()
            queue.output(job.id).writeText("corrupt private output")
            queue.publish(job.id, next)
            await(job.id, PdfExportPhase.Published)
            grantReleased(next)
            assertFalse(context.contentResolver.persistedUriPermissions.any { it.uri == old })
            val permission =
                context.contentResolver.persistedUriPermissions.single { it.uri == next }
            assertTrue(permission.isReadPermission)
            assertFalse(permission.isWritePermission)
            val file = File.createTempFile("published-", ".pdf", context.cacheDir)
            try {
                context.contentResolver.openInputStream(next)!!.use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
                assertEquals(1, IsolatedPdfEngine(context).inspect(file).size)
            } finally {
                file.delete()
            }
        } finally {
            cleanup(p, job, listOf(old, next))
        }
    }
}
