package com.librestatic.lightforge.feature.pdfstudio

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase G4 device test: creating a project from each [PdfTemplate] via the same VM entry point
 * the "New project" sheet uses ([PdfStudioViewModel.newProject]) lands the expected paper size,
 * margin and column count, and that it survives a reload from disk — not just in the live VM
 * state. Same VM-call pattern as [PdfTextEditingTest] (every call on Main, wait for `busy` to
 * clear and the project id to match before reading state).
 */
class PdfTemplateNewProjectTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo = PdfProjectRepository(context)

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    private suspend fun idle(vm: PdfStudioViewModel) =
        withTimeout(15_000) { while (vm.state.value.busy) delay(20) }

    private suspend fun newVm(): PdfStudioViewModel =
        onMain { PdfStudioViewModel(context.applicationContext as Application, SavedStateHandle()) }

    private suspend fun waitForProject(vm: PdfStudioViewModel) =
        withTimeout(15_000) { while (vm.state.value.project == null || vm.state.value.busy) delay(20) }

    @Test
    fun everyTemplateCreatesAndPersistsItsOwnPaperMarginAndGrid(): Unit = runBlocking {
        PdfTemplate.entries.forEach { template ->
            val vm = newVm()
            onMain {
                vm.newProject(
                    name = "New project test ${template.name}",
                    widthMm = template.widthMm(),
                    heightMm = template.heightMm(),
                    landscape = template.landscape,
                    columns = template.columns,
                    gap = template.gap,
                    margin = template.margin,
                )
            }
            waitForProject(vm)

            val live = vm.state.value.project!!
            assertEquals(1, live.pages.size)
            val page = live.pages[0]
            assertEquals(template.widthMm(), page.width, 0.01)
            assertEquals(template.heightMm(), page.height, 0.01)
            assertEquals(template.margin.coerceAtMost(minOf(page.width, page.height) / 4), page.margin, 0.01)
            assertEquals(template.columns, live.columns)
            assertEquals(template.gap, live.gap, 0.01)
            live.validate()

            // Autosave is debounced (~400ms); wait it out, then confirm the disk copy matches the
            // live VM state exactly, so a process death right after creation would still resume
            // with this template's layout.
            delay(700)
            val reloaded = repo.load(live.id)!!
            assertEquals(page.width, reloaded.pages[0].width, 0.01)
            assertEquals(page.height, reloaded.pages[0].height, 0.01)
            assertEquals(page.margin, reloaded.pages[0].margin, 0.01)
            assertEquals(live.columns, reloaded.columns)
            assertEquals(live.gap, reloaded.gap, 0.01)
        }
    }

    @Test
    fun prints10x15TemplateLandsOnA4WithTwoComputedColumnsAndItsPrintSize(): Unit = runBlocking {
        // Feedback item B: redefined from "one 10x15 print filling a 10x15 sheet" to "10x15 cm
        // photos, as many as fit, on A4" — see PdfTemplateTest/PdfPrintLayoutTest for the
        // slot-fitting algorithm's own coverage; this just confirms the VM entry point
        // ("New project" sheet) still lands the template's (now print-size) settings.
        val template = PdfTemplate.Prints10x15
        val vm = newVm()
        onMain {
            vm.newProject(
                name = "Prints test",
                widthMm = template.widthMm(),
                heightMm = template.heightMm(),
                landscape = template.landscape,
                columns = template.columns,
                gap = template.gap,
                margin = template.margin,
                printSize = template.printSize?.id,
                placementMode = template.fit,
            )
        }
        waitForProject(vm)
        val project = vm.state.value.project!!
        val page = project.pages[0]
        assertEquals(PdfPaperPresets.A4, PdfPaperPresets.matching(page.width, page.height))
        assertEquals(2, project.columns)
        assertEquals(PdfPrintSize.Print10x15.id, project.printSize)
        assertEquals(PdfFit.Cover, project.placementMode)
    }
}
