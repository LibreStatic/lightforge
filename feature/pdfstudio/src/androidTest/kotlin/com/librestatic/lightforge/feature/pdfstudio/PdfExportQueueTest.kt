package com.librestatic.lightforge.feature.pdfstudio

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PdfExportQueueTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo = PdfProjectRepository(context)
    private val queue = PdfExportQueue(context)

    private suspend fun fixture(): PdfProject {
        val f = File.createTempFile("pdf-queue-", ".png", context.cacheDir)
        val b = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        b.recycle()
        return try {
            repo.import(PdfProject(name = "Queued snapshot"), listOf(Uri.fromFile(f)), 0) { _, _ ->
            }
        } finally {
            f.delete()
        }
    }

    private suspend fun ready(id: String): PdfExportJob =
        withTimeout(45_000) {
            while (true) {
                val row = requireNotNull(queue.get(id))
                if (row.phase == PdfExportPhase.Ready) return@withTimeout row
                check(row.phase != PdfExportPhase.Failed) { row.error.orEmpty() }
                delay(50)
            }
            error("unreachable")
        }

    @Test
    fun realWorkerKeepsImmutableSnapshotAndSourcesAfterProjectDeletion(): Unit = runBlocking {
        val p = fixture()
        var job: PdfExportJob? = null
        try {
            job =
                queue.enqueue(
                    p.copy(pages = listOf(p.pages[0], p.pages[0].copy(id = newId()))),
                    false,
                )
            repo.save(p.copy(name = "Later edit"))
            repo.delete(p.id)
            assertTrue(repo.file(p.assets.single().hash).isFile)
            val done = ready(job.id)
            assertEquals("Queued snapshot", done.projectName)
            assertEquals(2, done.completed)
            assertEquals(2, IsolatedPdfEngine(context).inspect(queue.output(job.id)).size)
            assertEquals("Queued snapshot", PdfCodec.decode(done.manifest).name)
        } finally {
            job?.let {
                queue.cancel(it.id)
                queue.remove(it.id)
            }
            repo.delete(p.id)
        }
    }

    @Test
    fun explicitCancelStaysCancelledAndNeverPublishes(): Unit = runBlocking {
        val pause = File(context.filesDir, "pdf-worker-probe.pause")
        val entered = File(context.filesDir, "pdf-worker-probe.entered")
        entered.delete()
        pause.writeText("test")
        val p = fixture()
        var job: PdfExportJob? = null
        try {
            job = queue.enqueue(p, false)
            withTimeout(20_000) { while (!entered.exists()) delay(50) }
            assertEquals(PdfExportPhase.Running, queue.get(job.id)!!.phase)
            queue.cancel(job.id)
            pause.delete()
            withTimeout(20_000) {
                while (
                    !WorkManager.getInstance(context)
                        .getWorkInfoById(UUID.fromString(job.workId))
                        .get()!!
                        .state
                        .isFinished
                ) delay(50)
            }
            assertEquals(PdfExportPhase.Cancelled, queue.get(job.id)!!.phase)
            assertFalse(queue.output(job.id).exists())
            queue.reconcile()
            assertEquals(PdfExportPhase.Cancelled, queue.get(job.id)!!.phase)
        } finally {
            pause.delete()
            entered.delete()
            job?.let {
                queue.cancel(it.id)
                queue.remove(it.id)
            }
            repo.delete(p.id)
        }
    }

    @Test
    fun reconciliationEnqueuesPersistedButMissingWork(): Unit = runBlocking {
        val p = fixture()
        val now = System.currentTimeMillis()
        val job =
            PdfExportJob(
                newId(),
                newId(),
                p.name,
                PdfCodec.encode(p),
                false,
                PdfExportPhase.Queued.name,
                0,
                1,
                now,
                now,
            )
        try {
            PdfProjectDatabase.get(context).exports().put(job)
            queue.reconcile()
            assertEquals(PdfExportPhase.Ready, ready(job.id).phase)
        } finally {
            queue.cancel(job.id)
            queue.remove(job.id)
            repo.delete(p.id)
        }
    }

    @Test
    fun durablePublicationRetriesAndVerifiesRealDocumentProvider(): Unit = runBlocking {
        val p = fixture()
        var job: PdfExportJob? = null
        var uri: Uri? = null
        val denied = File(context.filesDir, "pdf-destination-denied")
        try {
            job = queue.enqueue(p, false)
            ready(job.id)
            val expected = queue.output(job.id).readBytes()
            val root =
                android.provider.DocumentsContract.buildDocumentUri(
                    PdfTestDocumentsProvider.AUTHORITY,
                    "root",
                )
            uri =
                android.provider.DocumentsContract.createDocument(
                    context.contentResolver,
                    root,
                    "application/pdf",
                    "queue-test.pdf",
                )!!
            context.grantUriPermission(
                context.packageName,
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
            denied.writeText("test")
            queue.publish(job.id, uri)
            withTimeout(20_000) {
                while (queue.get(job.id)!!.phase != PdfExportPhase.Failed) delay(50)
            }
            assertTrue(queue.output(job.id).exists())
            denied.delete()
            queue.retry(job.id)
            withTimeout(20_000) {
                while (queue.get(job.id)!!.phase != PdfExportPhase.Published) delay(50)
            }
            val actual = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            assertArrayEquals(expected, actual)
            assertFalse(queue.output(job.id).exists())
        } finally {
            denied.delete()
            job?.let {
                if (queue.get(it.id)?.phase != PdfExportPhase.Published) queue.cancel(it.id)
                queue.remove(it.id)
            }
            uri?.let {
                android.provider.DocumentsContract.deleteDocument(context.contentResolver, it)
            }
            repo.delete(p.id)
        }
    }

    private fun createTestDocument(name: String): Uri {
        val root =
            android.provider.DocumentsContract.buildDocumentUri(PdfTestDocumentsProvider.AUTHORITY, "root")
        val uri =
            requireNotNull(
                android.provider.DocumentsContract.createDocument(
                    context.contentResolver,
                    root,
                    "application/pdf",
                    name,
                )
            )
        context.grantUriPermission(
            context.packageName,
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        return uri
    }

    @Test
    fun destinationFirstEnqueueSkipsReadyAndPublishesDirectly(): Unit = runBlocking {
        val p = fixture()
        var job: PdfExportJob? = null
        var uri: Uri? = null
        try {
            uri = createTestDocument("destination-first.pdf")
            job = queue.enqueue(p, false, destination = uri)
            assertEquals(uri.toString(), job.destination)
            var sawReady = false
            withTimeout(45_000) {
                while (true) {
                    val row = requireNotNull(queue.get(job.id))
                    if (row.phase == PdfExportPhase.Ready) sawReady = true
                    if (row.phase == PdfExportPhase.Published) break
                    check(row.phase != PdfExportPhase.Failed) { row.error.orEmpty() }
                    delay(50)
                }
            }
            // The whole point of "destination first" is that a job created with a destination
            // never passes through Ready waiting for a Save tap.
            assertFalse(sawReady)
            val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            assertTrue(bytes.isNotEmpty())
            assertFalse(queue.output(job.id).exists())
        } finally {
            job?.let {
                if (queue.get(it.id)?.phase != PdfExportPhase.Published) queue.cancel(it.id)
                queue.remove(it.id)
            }
            uri?.let { android.provider.DocumentsContract.deleteDocument(context.contentResolver, it) }
            repo.delete(p.id)
        }
    }

    @Test
    fun destinationFirstEnqueueRejectsADestinationAnotherLiveJobOwns(): Unit = runBlocking {
        val p = fixture()
        var first: PdfExportJob? = null
        var uri: Uri? = null
        try {
            uri = createTestDocument("shared-destination.pdf")
            first = queue.enqueue(p, false, destination = uri)
            // The first job still owns this destination (not Published/Cancelled yet), so a second
            // enqueue targeting the same URI must be rejected exactly like a second `publish()`
            // call would be, instead of racing the same document from two live jobs.
            try {
                queue.enqueue(p, false, destination = uri)
                fail("Expected the destination to be reported as already in use")
            } catch (e: IllegalArgumentException) {
                assertEquals("DestinationInUse", e.message)
            }
        } finally {
            first?.let {
                if (queue.get(it.id)?.phase != PdfExportPhase.Published) queue.cancel(it.id)
                queue.remove(it.id)
            }
            uri?.let { android.provider.DocumentsContract.deleteDocument(context.contentResolver, it) }
            repo.delete(p.id)
        }
    }
}
