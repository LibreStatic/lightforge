package com.ugallery.feature.pdfstudio

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression coverage for the review blocker in `resumeDraftExport`: a draft cleared with `take()`
 * *before* `PdfExportQueue.enqueue` durably committed meant process death in between, followed by
 * the unconditional `publishPicker.acknowledge(request)`, silently lost an export the user had
 * already picked a destination for. Both scenarios below start from a `SavedStateHandle` built by
 * hand exactly the way a real recreation would find it (draft + a delivered picker result), the
 * same technique `PdfPickerTest` uses for its own process-death recreation coverage.
 */
class PdfDraftResumeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo = PdfProjectRepository(context)
    private val queue = PdfExportQueue(context)

    private suspend fun fixture(name: String): PdfProject {
        val f = File.createTempFile("pdf-draft-", ".png", context.cacheDir)
        val b = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        b.recycle()
        return try {
            repo.import(PdfProject(name = name), listOf(Uri.fromFile(f)), 0) { _, _ -> }
        } finally {
            f.delete()
        }
    }

    private fun createTestDocument(name: String): Uri {
        val root = DocumentsContract.buildDocumentUri(PdfTestDocumentsProvider.AUTHORITY, "root")
        val uri =
            requireNotNull(
                DocumentsContract.createDocument(context.contentResolver, root, "application/pdf", name)
            )
        context.grantUriPermission(
            context.packageName,
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        return uri
    }

    private fun draftHandle(projectId: String, uri: Uri): SavedStateHandle =
        SavedStateHandle(
            mapOf(
                PdfPublishPicker.KEY to arrayListOf(draftPickerId(), uri.toString()),
                PdfExportDraftStore.KEY to arrayListOf(projectId, "false", "All", ""),
            )
        )

    private suspend fun cleanUp(uri: Uri, project: PdfProject) {
        queue.jobsForDestination(uri).forEach { row ->
            if (row.phase !in setOf(PdfExportPhase.Published, PdfExportPhase.Cancelled)) queue.cancel(row.id)
            queue.remove(row.id)
        }
        repo.delete(project.id)
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
    }

    @Test
    fun draftResumesTheEnqueueExactlyOnceWhenNothingWasCommittedYet(): Unit = runBlocking {
        val project = fixture("Draft not yet committed")
        val uri = createTestDocument("draft-fresh.pdf")
        val store = ViewModelStore()
        try {
            val vm =
                withContext(Dispatchers.Main) {
                    PdfStudioViewModel(context.applicationContext as Application, draftHandle(project.id, uri))
                        .also { store.put("pdf", it) }
                }
            withTimeout(20_000) { while (queue.jobsForDestination(uri).isEmpty()) delay(30) }
            // Give a stray duplicate enqueue a real chance to show up before asserting there is
            // exactly one job bound to this destination.
            delay(300)
            assertEquals(1, queue.jobsForDestination(uri).size)
            withTimeout(10_000) { while (vm.pendingPublication.value != null) delay(20) }
            withTimeout(20_000) {
                while (queue.jobsForDestination(uri).none { it.phase == PdfExportPhase.Published }) {
                    check(queue.jobsForDestination(uri).none { it.phase == PdfExportPhase.Failed }) {
                        queue.jobsForDestination(uri).firstOrNull { it.phase == PdfExportPhase.Failed }?.error.orEmpty()
                    }
                    delay(30)
                }
            }
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            cleanUp(uri, project)
        }
    }

    @Test
    fun draftResumeRecognizesAnAlreadyCommittedEnqueueInsteadOfDuplicatingOrLosingIt(): Unit = runBlocking {
        val project = fixture("Draft already committed")
        val uri = createTestDocument("draft-committed.pdf")
        val store = ViewModelStore()
        try {
            // Simulate: an earlier resumeDraftExport already enqueued durably (this is the row a
            // dead process could no longer see), but never got to discard the draft or acknowledge
            // the picker request - both are still recorded exactly as it would have left them.
            val firstJob = queue.enqueue(project, false, destination = uri)
            val vm =
                withContext(Dispatchers.Main) {
                    PdfStudioViewModel(context.applicationContext as Application, draftHandle(project.id, uri))
                        .also { store.put("pdf", it) }
                }
            withTimeout(10_000) { while (vm.pendingPublication.value != null) delay(20) }
            // No duplicate job for the same destination, and the one job is the original, not a
            // second render of the same project.
            delay(300)
            val bound = queue.jobsForDestination(uri)
            assertEquals(1, bound.size)
            assertEquals(firstJob.id, bound.single().id)
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            cleanUp(uri, project)
        }
    }
}
