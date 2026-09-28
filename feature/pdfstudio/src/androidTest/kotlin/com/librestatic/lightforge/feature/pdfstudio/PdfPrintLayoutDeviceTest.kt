package com.librestatic.lightforge.feature.pdfstudio

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/**
 * VM-level device coverage for feedback items A/B (same call pattern as
 * [PdfTemplateNewProjectTest]/`PdfTextEditingTest`: every VM call on Main, wait for `busy` to
 * clear before reading state): a print-size project's photo count per page is computed (not
 * fixed), changing paper repaginates the whole project in one undo step, and the placement mode
 * (Fill/Fit) re-applies to every already-placed photo in one undo step. Finishes by exporting
 * through [IsolatedPdfEngine] and checking the real PDF's page count matches the model.
 */
class PdfPrintLayoutDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo = PdfProjectRepository(context)

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    private suspend fun idle(vm: PdfStudioViewModel) =
        withTimeout(15_000) { while (vm.state.value.busy) delay(20) }

    private suspend fun newVm(): PdfStudioViewModel =
        onMain { PdfStudioViewModel(context.applicationContext as Application, SavedStateHandle()) }

    private fun temp(ext: String) = File.createTempFile("print-layout-test-", ext, context.cacheDir)

    /** A distinct-looking JPEG (varied color/aspect) so five different assets never collide. */
    private fun jpeg(width: Int, height: Int, color: Int): File =
        temp(".jpg").also { file ->
            val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.drawColor(color)
            file.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            b.recycle()
        }

    private fun buildFivePhotoPrintProject(): Pair<PdfProject, List<File>> {
        val files =
            listOf(
                jpeg(4000, 3000, Color.RED),
                jpeg(3000, 4000, Color.GREEN),
                jpeg(4000, 3000, Color.BLUE),
                jpeg(3000, 4000, Color.YELLOW),
                jpeg(4000, 3000, Color.CYAN),
            )
        val assets =
            files.map { f ->
                PdfAsset(PdfProjectRepository.sha256(f), "image/jpeg", 4000, 3000)
            }
        val project =
            PdfTemplate.Prints10x15.buildProject("Print layout test", assets, assets.map { it.hash })
        return project to files
    }

    /** [repo.save] requires every used asset's content already at [repo.file] (the storage a real
     * import/`repo.import` call would have populated) — copies [files] there so a project built
     * directly (not through the streaming importer) can still be saved/opened through the VM. */
    private fun seedAssetStorage(project: PdfProject, files: List<File>) {
        project.assets.zip(files).forEach { (asset, file) -> file.copyTo(repo.file(asset.hash), overwrite = true) }
    }

    @Test
    fun tenBy15OnA4WithFivePhotosMakesThreePagesOfExactSlots(): Unit = runBlocking {
        val (project, _) = buildFivePhotoPrintProject()

        // Feedback item B: the count per page is COMPUTED (2 for 10x15 on A4 with the template's
        // margin/gap), not hardcoded — five photos therefore paginate 2, 2, 1.
        assertEquals(3, project.pages.size)
        assertEquals(listOf(2, 2, 1), project.pages.map { it.images.size })
        project.pages.flatMap { it.images }.forEach { image ->
            assertEquals(PdfFit.Cover, image.fit)
            assertEquals(setOf(100.0, 150.0), setOf(image.width, image.height))
        }
        project.validate()
    }

    @Test
    fun switchingPaperTo10x15RepaginatesToFivePagesInOneUndoStep(): Unit = runBlocking {
        val (project, files) = buildFivePhotoPrintProject()
        seedAssetStorage(project, files)
        repo.save(project)
        val vm = newVm()
        onMain { vm.open(project.id) }
        idle(vm)
        assertEquals(project.id, vm.state.value.project?.id)
        assertEquals(3, vm.state.value.project!!.pages.size)
        assertFalse(vm.state.value.canUndo)

        // Switching the paper to a 10x15 sheet with 0 margin fits exactly 1 slot per page ->
        // 5 photos need 5 pages. One VM call, one undo step.
        onMain {
            vm.applyPrintLayoutSettings(
                printSize = PdfPrintSize.Print10x15,
                placementMode = vm.state.value.project!!.placementMode,
                widthMm = 100.0,
                heightMm = 150.0,
                marginMm = 0.0,
                gapMm = vm.state.value.project!!.gap,
            )
        }
        idle(vm)

        val repaginated = vm.state.value.project!!
        assertEquals(5, repaginated.pages.size)
        repaginated.pages.forEach { page ->
            assertEquals(1, page.images.size)
            assertEquals(100.0, page.width, 0.01)
            assertEquals(150.0, page.height, 0.01)
            assertEquals(100.0, page.images[0].width, 0.01)
            assertEquals(150.0, page.images[0].height, 0.01)
        }
        assertTrue(vm.state.value.canUndo)

        onMain { vm.undo() }
        idle(vm)
        val reverted = vm.state.value.project!!
        assertEquals(3, reverted.pages.size)
        assertEquals(listOf(2, 2, 1), reverted.pages.map { it.images.size })
    }

    @Test
    fun placementModeChangeAppliesToEveryPhotoInOneUndoStep(): Unit = runBlocking {
        val (project, files) = buildFivePhotoPrintProject()
        seedAssetStorage(project, files)
        repo.save(project)
        val vm = newVm()
        onMain { vm.open(project.id) }
        idle(vm)

        // The template's default placement mode is Fill (Cover).
        assertTrue(vm.state.value.project!!.pages.flatMap { it.images }.all { it.fit == PdfFit.Cover })
        assertFalse(vm.state.value.canUndo)

        onMain { vm.applyPlacementMode(PdfFit.Contain, allPages = true) }
        idle(vm)
        val afterFit = vm.state.value.project!!
        assertEquals(PdfFit.Contain, afterFit.placementMode)
        assertTrue(afterFit.pages.flatMap { it.images }.all { it.fit == PdfFit.Contain })
        // Geometry (the slots themselves) is untouched by a placement-mode-only change.
        assertEquals(listOf(2, 2, 1), afterFit.pages.map { it.images.size })
        assertTrue(vm.state.value.canUndo)

        // One undo step reverts every photo back to Fill, not just the last-touched one.
        onMain { vm.undo() }
        idle(vm)
        val reverted = vm.state.value.project!!
        assertEquals(PdfFit.Cover, reverted.placementMode)
        assertTrue(reverted.pages.flatMap { it.images }.all { it.fit == PdfFit.Cover })
        assertFalse(vm.state.value.canUndo)

        onMain { vm.applyPlacementMode(PdfFit.Contain, allPages = true) }
        idle(vm)
        assertTrue(vm.state.value.project!!.pages.flatMap { it.images }.all { it.fit == PdfFit.Contain })
    }

    @Test
    fun exportedPdfPageCountMatchesTheComputedPagination(): Unit = runBlocking {
        PDFBoxResourceLoader.init(context)
        val (project, files) = buildFivePhotoPrintProject()
        val engine = IsolatedPdfEngine(context)
        val output = temp(".pdf")
        try {
            val pages = java.util.concurrent.CopyOnWriteArrayList<Pair<Int, Int>>()
            engine.export(project, files, output, false) { n, total -> pages.add(n to total) }
            withTimeout(10_000) { while (pages.size < 3) delay(20) }
            assertEquals(3, IsolatedPdfEngine(context).inspect(output).size)
        } finally {
            output.delete()
            files.forEach { it.delete() }
        }
    }
}
