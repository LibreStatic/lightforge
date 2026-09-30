package com.librestatic.lightforge.feature.pdfstudio

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PdfGalleryIntakeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = PdfProjectDatabase.get(context)
    private val intake = PdfGalleryIntake(context)
    private val repo = PdfProjectRepository(context)

    private fun png(file: File) {
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(20, 100, 150))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private suspend fun vm(store: ViewModelStore) =
        withContext(Dispatchers.Main) {
            PdfStudioViewModel(context.applicationContext as Application, SavedStateHandle()).also {
                store.put("pdf", it)
            }
        }

    private suspend fun completed(id: String, vm: PdfStudioViewModel) =
        withTimeout(20_000) {
            while (
                db.imports().get(id) == null ||
                    db.galleryDeliveries().get(id) != null ||
                    vm.state.value.busy ||
                    vm.state.value.project == null
            ) delay(20)
            vm.state.value.project!!
        }

    @Test
    fun mediaStoreHundredPhotosPaginateAndCommitLayoutWithReceipt(): Unit = runBlocking {
        val source = File.createTempFile("gallery-", ".png", context.cacheDir).also(::png)
        val uri =
            context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "Lightforge-test-${newId()}.png")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                },
            )!!
        val id = newId()
        var project: PdfProject? = null
        try {
            context.contentResolver.openOutputStream(uri)!!.use { out ->
                source.inputStream().use { it.copyTo(out) }
            }
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            intake.stage(id, "One hundred selected photos", List(100) { uri })
            val row = db.galleryDeliveries().get(id)!!
            project = intake.process(row, repo) { _, _ -> }
            assertEquals(listOf(24, 24, 24, 24, 4), project.pages.map { it.images.size })
            assertEquals(1, project.assets.size)
            assertEquals(project.id, db.imports().get(id)!!.projectId)
            assertNull(db.galleryDeliveries().get(id))
            assertTrue(context.contentResolver.persistedUriPermissions.none { it.uri == uri })
            project.pages.forEach { page ->
                assertEquals(PdfGeometry.grid(page, 2, 4.0).images, page.images)
            }
            assertEquals(project.id, repo.importGallery(row) { _, _ -> }.id)
            val output = File(context.cacheDir, "gallery-${newId()}.pdf")
            try {
                IsolatedPdfEngine(context).export(
                    project,
                    project.assets.map { repo.file(it.hash) },
                    output,
                    false,
                ) { _, _ ->
                }
                assertEquals(5, IsolatedPdfEngine(context).inspect(output).size)
            } finally {
                output.delete()
            }
            repo.delete(project.id)
            intake.stage(id, "Replayed navigation", List(100) { uri })
            assertNull(db.galleryDeliveries().get(id))
            assertNull(repo.load(project.id))
        } finally {
            intake.discard(id)
            project?.let { repo.delete(it.id) }
            context.contentResolver.delete(uri, null, null)
            source.delete()
        }
    }

    /**
     * Regression: a selection handed over right as the studio's ViewModel is created raced the
     * init-time recovery pass (which read `pending()` before `stage()` wrote), so the delivery was
     * dropped and the studio stayed on the project list instead of opening the new project.
     */
    @Test
    fun deliveryDuringInitialRecoveryStillOpensProject(): Unit = runBlocking {
        val source = File.createTempFile("gallery-race-", ".png", context.cacheDir).also(::png)
        repeat(5) { attempt ->
            val id = newId()
            val store = ViewModelStore()
            var project: PdfProject? = null
            try {
                val model = vm(store)
                val accepted =
                    withContext(Dispatchers.Main) {
                        model.receiveGallery(id, "Race $attempt", List(3) { Uri.fromFile(source) })
                    }
                assertTrue(accepted)
                project = completed(id, model)
                assertEquals(3, project.pages.sumOf { it.images.size })
            } finally {
                withContext(Dispatchers.Main) { store.clear() }
                intake.discard(id)
                project?.let { repo.delete(it.id) }
            }
        }
        source.delete()
    }

    @Test
    fun blankSavedStateRecoversStagedSelection(): Unit = runBlocking {
        val source = File.createTempFile("gallery-recover-", ".png", context.cacheDir).also(::png)
        val id = newId()
        val store = ViewModelStore()
        var project: PdfProject? = null
        try {
            intake.stage(id, "Recovered selection", List(25) { Uri.fromFile(source) })
            project = completed(id, vm(store))
            assertEquals(listOf(24, 1), project.pages.map { it.images.size })
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            intake.discard(id)
            project?.let { repo.delete(it.id) }
            source.delete()
        }
    }

    @Test
    fun failedSelectionRemainsRetryableWithoutCreatingEmptyProject(): Unit = runBlocking {
        val source =
            File.createTempFile("gallery-failure-", ".png", context.cacheDir).apply {
                writeText("not an image")
            }
        val id = newId()
        val store = ViewModelStore()
        var project: PdfProject? = null
        val before = db.projects().all().map { it.id }.toSet()
        try {
            val vm = vm(store)
            assertTrue(
                withContext(Dispatchers.Main) {
                    vm.receiveGallery(id, "Retry selection", listOf(Uri.fromFile(source)))
                }
            )
            withTimeout(10_000) {
                while (db.galleryDeliveries().get(id)?.error == null || vm.state.value.busy) delay(
                    20
                )
            }
            assertEquals(PdfFailure.UnsupportedFormat.name, db.galleryDeliveries().get(id)!!.failure())
            assertNull(db.imports().get(id))
            assertEquals(before, db.projects().all().map { it.id }.toSet())
            png(source)
            withContext(Dispatchers.Main) { vm.retryGallery(id) }
            project = completed(id, vm)
            assertEquals(1, project.pages.single().images.size)
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            intake.discard(id)
            project?.let { repo.delete(it.id) }
            source.delete()
        }
    }

    @Test
    fun stagingDoesNotWaitForCopyLockAndRejectsChangedPayload(): Unit = runBlocking {
        val id = newId()
        val uri = Uri.parse("content://media/external/images/media/123")
        try {
            PdfStorageLock.mutex.lock()
            try {
                withTimeout(5_000) { intake.stage(id, "Pending", listOf(uri)) }
            } finally {
                PdfStorageLock.mutex.unlock()
            }
            intake.stage(id, "Localized default name", listOf(uri))
            assertEquals("Pending", db.galleryDeliveries().get(id)!!.name)
            try {
                intake.stage(id, "Pending", listOf(Uri.parse("content://media/changed")))
                fail("Mutable delivery accepted")
            } catch (_: IllegalArgumentException) {}
            assertEquals(listOf(uri), db.galleryDeliveries().get(id)!!.sources())
        } finally {
            intake.discard(id)
        }
    }

    @Test
    fun failedOrCancelledDeliveryIsNotAutomaticallyRetried(): Unit = runBlocking {
        val id = newId()
        try {
            intake.stage(id, "Paused", listOf(Uri.parse("content://media/123")))
            intake.failed(id, "Cancelled")
            assertNull(intake.pending())
            intake.retry(id)
            assertEquals(id, intake.pending()!!.id)
            intake.discard(id)
            assertNull(db.galleryDeliveries().get(id))
        } finally {
            intake.discard(id)
        }
    }

    @Test
    fun explicitCancellationPausesThenRetryImportsOnce(): Unit = runBlocking {
        val source = File.createTempFile("gallery-cancel-", ".png", context.cacheDir).also(::png)
        val id = newId()
        val store = ViewModelStore()
        var project: PdfProject? = null
        try {
            val vm = vm(store)
            PdfStorageLock.mutex.lock()
            try {
                withContext(Dispatchers.Main) {
                    check(
                        vm.receiveGallery(id, "Cancelled selection", listOf(Uri.fromFile(source)))
                    )
                }
                withTimeout(5_000) { while (!vm.state.value.busy) delay(10) }
                withContext(Dispatchers.Main) { vm.cancel() }
                withTimeout(5_000) {
                    while (
                        vm.state.value.busy || db.galleryDeliveries().get(id)?.error != "Cancelled"
                    ) delay(10)
                }
                assertNull(db.imports().get(id))
            } finally {
                PdfStorageLock.mutex.unlock()
            }
            withContext(Dispatchers.Main) { vm.retryGallery(id) }
            project = completed(id, vm)
            assertEquals(1, project.pages.single().images.size)
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            intake.discard(id)
            project?.let { repo.delete(it.id) }
            source.delete()
        }
    }

    @Test
    fun multipleDurableBatchesDrainWithoutLostWakeup(): Unit = runBlocking {
        val source = File.createTempFile("gallery-queue-", ".png", context.cacheDir).also(::png)
        val ids = listOf(newId(), newId())
        val store = ViewModelStore()
        try {
            ids.forEachIndexed { n, id ->
                intake.stage(id, "Selection $n", listOf(Uri.fromFile(source)))
            }
            val vm = vm(store)
            withTimeout(20_000) {
                while (
                    ids.any {
                        db.imports().get(it) == null || db.galleryDeliveries().get(it) != null
                    } || vm.state.value.busy
                ) delay(20)
            }
            assertEquals(2, ids.map { db.imports().get(it)!!.projectId }.distinct().size)
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            ids.forEach { id ->
                intake.discard(id)
                db.imports().get(id)?.let { repo.delete(it.projectId) }
            }
            source.delete()
        }
    }
}
