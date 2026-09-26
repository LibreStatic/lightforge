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

class PdfPickerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo = PdfProjectRepository(context)
    private val queue = PdfExportQueue(context)

    @Test
    fun restoredDeliveryPublishesOnceWhileEditorIsBusy(): Unit = runBlocking {
        val file = File.createTempFile("picker-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(20, 40, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val project =
            repo.import(PdfProject(name = "Picker recreation"), listOf(Uri.fromFile(file)), 0) {
                _,
                _ ->
            }
        file.delete()
        val job = queue.enqueue(project, false)
        withTimeout(20_000) { while (queue.get(job.id)?.phase != PdfExportPhase.Ready) delay(20) }
        val root = DocumentsContract.buildDocumentUri(PdfTestDocumentsProvider.AUTHORITY, "root")
        val uri =
            DocumentsContract.createDocument(
                context.contentResolver,
                root,
                "application/pdf",
                "picker.pdf",
            )!!
        context.grantUriPermission(
            context.packageName,
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        val store = ViewModelStore()
        val nextStore = ViewModelStore()
        val busyName = "Busy editor ${job.id}"
        try {
            val saved = SavedStateHandle(mapOf(PdfPublishPicker.KEY to arrayListOf(job.id)))
            PdfStorageLock.mutex.lock()
            val vm =
                try {
                    val current =
                        withContext(Dispatchers.Main) {
                            PdfStudioViewModel(context.applicationContext as Application, saved)
                                .also {
                                    store.put("pdf", it)
                                    it.newProject(busyName)
                                }
                        }
                    // Hold an actual editor save at the storage lock, not a timing assumption about
                    // IO.
                    withTimeout(10_000) { while (!current.state.value.busy) delay(20) }
                    withContext(Dispatchers.Main) {
                        assertTrue(current.state.value.busy)
                        assertTrue(current.beginPublication(job.id) is PublishStart.AlreadyPending)
                        current.publicationResult(uri)
                        current.publicationResult(uri)
                    }
                    current
                } finally {
                    PdfStorageLock.mutex.unlock()
                }
            withTimeout(10_000) { while (vm.state.value.busy) delay(20) }
            withTimeout(25_000) {
                while (queue.get(job.id)?.phase != PdfExportPhase.Published) {
                    check(queue.get(job.id)?.phase != PdfExportPhase.Failed) {
                        queue.get(job.id)?.error.orEmpty()
                    }
                    delay(30)
                }
            }
            withTimeout(10_000) { while (vm.pendingPublication.value != null) delay(20) }
            val workId = queue.get(job.id)!!.workId
            // A stale saved result after a committed publication acknowledges the same row.
            val reopened =
                withContext(Dispatchers.Main) {
                    store.clear()
                    PdfStudioViewModel(
                            context.applicationContext as Application,
                            SavedStateHandle(
                                mapOf(PdfPublishPicker.KEY to arrayListOf(job.id, uri.toString()))
                            ),
                        )
                        .also { nextStore.put("pdf", it) }
                }
            withTimeout(10_000) { while (reopened.pendingPublication.value != null) delay(20) }
            assertEquals(workId, queue.get(job.id)!!.workId)
            assertEquals(PdfExportPhase.Published, queue.get(job.id)!!.phase)
        } finally {
            withContext(Dispatchers.Main) {
                store.clear()
                nextStore.clear()
            }
            queue.cancel(job.id)
            queue.remove(job.id)
            repo.delete(project.id)
            PdfProjectDatabase.get(context)
                .projects()
                .all()
                .filter { it.name == busyName }
                .forEach { repo.delete(it.id) }
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
        }
    }

    @Test
    fun importFailureNamesSourceAndPreservesProject(): Unit = runBlocking {
        val store = ViewModelStore()
        val good = File.createTempFile("source-good-", ".png", context.cacheDir)
        val bad = File.createTempFile("source-bad-", ".txt", context.cacheDir)
        val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        good.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        bad.writeText("Not an image")
        val vm =
            withContext(Dispatchers.Main) {
                PdfStudioViewModel(context.applicationContext as Application, SavedStateHandle())
                    .also {
                        store.put("pdf", it)
                        it.newProject("Source errors")
                    }
            }
        var project: PdfProject? = null
        try {
            withTimeout(10_000) {
                while (vm.state.value.busy || vm.state.value.project == null) delay(20)
            }
            project = vm.state.value.project!!
            val persistedBeforeImport = repo.load(project.id)
            withContext(Dispatchers.Main) {
                assertTrue(vm.beginImport(false))
                vm.importResult(listOf(Uri.fromFile(good), Uri.fromFile(bad)))
            }
            withTimeout(10_000) { while (vm.state.value.busy) delay(20) }
            assertEquals(2, vm.state.value.sourceError)
            assertEquals(R.string.pdf_failure_format, vm.state.value.message)
            assertEquals(project, vm.state.value.project)
            assertEquals(persistedBeforeImport, repo.load(project.id))
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            project?.let { repo.delete(it.id) }
            good.delete()
            bad.delete()
        }
    }
}
