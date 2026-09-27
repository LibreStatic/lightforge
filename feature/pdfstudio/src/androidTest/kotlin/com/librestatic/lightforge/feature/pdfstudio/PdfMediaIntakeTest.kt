package com.librestatic.lightforge.feature.pdfstudio

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase F item 3 (Media panel insert): [PdfGalleryDelivery.targetProjectId]/[targetPageId]/
 * [placementX]/[placementY] (added in v9, unused before this phase) must let
 * [PdfProjectRepository.importGallery] append durably into the CURRENT project/page instead of
 * creating a new project - same receipts/idempotency/limit-checking as every other import path
 * (see [PdfGalleryIntakeTest] for that path's own coverage, which this must not regress).
 */
class PdfMediaIntakeTest {
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

    @Test
    fun targetedDeliveryAppendsIntoTheOpenProjectsPageInsteadOfANewProject(): Unit = runBlocking {
        val source = File.createTempFile("media-target-", ".png", context.cacheDir).also(::png)
        var project = PdfProject(name = "Target project")
        try {
            repo.save(project)
            val page = project.pages.single()
            val id = newId()
            intake.stage(
                id,
                project.name,
                listOf(Uri.fromFile(source)),
                targetProjectId = project.id,
                targetPageId = page.id,
                placementX = 60.0,
                placementY = 90.0,
            )
            val row = db.galleryDeliveries().get(id)!!
            val updated = intake.process(row, repo) { _, _ -> }
            // Same project, not a fresh one: the whole point of targeting is to never spawn a
            // second project for a Media panel insert.
            assertEquals(project.id, updated.id)
            assertEquals(1, updated.pages.size)
            assertEquals(1, updated.pages.single().images.size)
            val image = updated.pages.single().images.single()
            // The drop's page-space CENTER becomes the inserted image's center (see
            // PdfProjectRepository.importUnlocked's `placement` handling).
            assertEquals(60.0, image.x + image.width / 2, 0.5)
            assertEquals(90.0, image.y + image.height / 2, 0.5)
            assertEquals(project.id, db.imports().get(id)!!.projectId)
            assertNull(db.galleryDeliveries().get(id))
            project = updated

            // Idempotency (no double insert on redelivery/process death): calling importGallery
            // again with the SAME row (as a resumed process would) must short-circuit on the
            // receipt and return the already-committed project unchanged, not append a duplicate.
            val replay = repo.importGallery(row) { _, _ -> }
            assertEquals(project.id, replay.id)
            assertEquals(1, replay.pages.single().images.size)
        } finally {
            project.let { repo.delete(it.id) }
            source.delete()
        }
    }

    @Test
    fun targetedDeliveryRespectsThePerPageImageLimit(): Unit = runBlocking {
        val source = File.createTempFile("media-limit-", ".png", context.cacheDir).also(::png)
        var project = PdfProject(name = "Full page")
        try {
            repo.save(project)
            // Fill the page to the 24-image limit through the ordinary (non-gallery) import path.
            project =
                repo.import(project, List(24) { Uri.fromFile(source) }, 0) { _, _ -> }
            assertEquals(24, project.pages.single().images.size)

            val id = newId()
            intake.stage(
                id,
                project.name,
                listOf(Uri.fromFile(source)),
                targetProjectId = project.id,
                targetPageId = project.pages.single().id,
            )
            val row = db.galleryDeliveries().get(id)!!
            try {
                repo.importGallery(row) { _, _ -> }
                fail("A 25th image on one page must be rejected")
            } catch (e: PdfOperationFailure) {
                // Review fix (device fail): the per-page limit used to surface wrapped as
                // PdfSourceFailure("Source 1"), a generic per-source read failure the issue card
                // rendered as an unhelpful "file couldn't be read" message. It must instead be a
                // clear, dedicated PdfFailure.PageFull the UI can render as "page is full".
                assertEquals(PdfFailure.PageFull, e.failure)
            }
            // All-or-nothing: the page still has exactly the 24 images it started with, and the
            // failure never touched the source file at all (checked BEFORE any copy).
            assertEquals(24, requireNotNull(repo.load(project.id)).pages.single().images.size)
        } finally {
            project.let { repo.delete(it.id) }
            source.delete()
        }
    }

    @Test
    fun mediaPanelInsertViaTheViewModelAppendsIntoTheCurrentPage(): Unit = runBlocking {
        val source = File.createTempFile("media-vm-", ".png", context.cacheDir).also(::png)
        val store = ViewModelStore()
        var projectId: String? = null
        try {
            val model = vm(store)
            withContext(Dispatchers.Main) { model.newProject("VM target") }
            withTimeout(10_000) { while (model.state.value.project == null) delay(20) }
            projectId = model.state.value.project!!.id
            withContext(Dispatchers.Main) { model.insertMedia(Uri.fromFile(source)) }
            withTimeout(10_000) {
                while (
                    model.state.value.busy ||
                        model.state.value.project?.pages?.getOrNull(0)?.images.isNullOrEmpty()
                )
                    delay(20)
            }
            val project = requireNotNull(model.state.value.project)
            assertEquals(projectId, project.id)
            assertEquals(1, project.pages.single().images.size)
            // Review fix (device fail: "tapping a tile does nothing"): this drives the exact
            // same vm.insertMedia -> gallery delivery staging -> resumeGallery's targeted,
            // non-locking path -> importMediaIntoPage chain a real Media panel tap does, with no
            // UI/gesture layer in between, so a failure here points squarely at that chain rather
            // than at touch dispatch.
            assertTrue("insert must be a single undo step", model.state.value.canUndo)
            withContext(Dispatchers.Main) { model.undo() }
            withTimeout(10_000) { while (model.state.value.busy) delay(20) }
            assertEquals(0, requireNotNull(model.state.value.project).pages.single().images.size)
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            projectId?.let { repo.delete(it) }
            source.delete()
        }
    }
}
