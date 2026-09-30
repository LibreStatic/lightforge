package com.librestatic.lightforge.feature.pdfstudio

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase G1b device tests for the text-layer UI's VM-level behavior: add, edit content, change
 * style, move, resize, layer, delete, undo/redo, and glyph rejection — each as a single undo step,
 * verified against both live [PdfStudioViewModel] state and the persisted project (so a crash
 * right after any of these would still resume correctly). Canvas gestures/inline editing
 * themselves are exercised visually by `tools/verify_pdf_adaptive_ui.py --text`; this suite covers
 * the VM contract every one of those gestures ultimately calls into.
 */
class PdfTextEditingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo = PdfProjectRepository(context)

    /**
     * Round-2 fix (item A): [PdfStudioViewModel.operation] (which [PdfStudioViewModel.open] and a
     * few others go through) does `viewModelScope.launch { busy = true; ...; busy = false }`.
     * `viewModelScope`'s dispatcher is `Dispatchers.Main.immediate`, which only runs that launch
     * body SYNCHRONOUSLY UP TO ITS FIRST SUSPENSION POINT when the calling coroutine is already on
     * the main thread — otherwise `launch` merely posts to the main looper and returns immediately,
     * without having set `busy = true` yet. Calling VM methods from this test's plain
     * `runBlocking` (a background test thread, not Main) hit exactly that race: `idle()` read
     * `busy == false` an instant *before* the launch had even started, so it returned immediately
     * with the previous (or no) project still in state — hence the reported NPEs and the
     * page-full test's "20 instead of 24" (the async `open()` finishing late then overwrote the
     * texts already added against the stale state). Every VM call in this file now goes through
     * this, matching how the real UI always calls the VM from the main thread anyway.
     */
    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    private suspend fun idle(vm: PdfStudioViewModel) =
        withTimeout(15_000) { while (vm.state.value.busy) delay(20) }

    private suspend fun openFreshProject(): PdfStudioViewModel {
        val project = PdfProject(name = "Text editing test").validate()
        repo.save(project)
        val vm =
            onMain { PdfStudioViewModel(context.applicationContext as Application, SavedStateHandle()) }
        onMain { vm.open(project.id) }
        // Not just "wait for busy to clear" (round-2 fix, item A): right after the call above,
        // busy may still correctly read false for an instant before the launch's first line runs
        // even on Main (a genuine dispatch, not just the immediate fast path, can still happen).
        // Waiting for the PROJECT ID to actually match rules out reading that transient window.
        withTimeout(15_000) {
            while (vm.state.value.project?.id != project.id || vm.state.value.busy) delay(20)
        }
        return vm
    }

    private fun currentText(vm: PdfStudioViewModel): PdfText {
        val s = vm.state.value
        val page = s.project!!.pages[s.page]
        return page.texts.first { it.id == s.selectedTextId }
    }

    @Test
    fun addEditMoveResizeUndoRedoAndPersist(): Unit = runBlocking {
        val vm = openFreshProject()

        // Add: selected immediately, placeholder text, in front (z = 0 on an empty page).
        onMain { vm.addText("Hello") }
        idle(vm)
        assertEquals(1, vm.state.value.project!!.pages[0].texts.size)
        assertNotNull(vm.state.value.selectedTextId)
        assertEquals("Hello", currentText(vm).text)
        val id = vm.state.value.selectedTextId!!
        assertEquals(id, vm.state.value.newTextId)
        onMain { vm.newTextOpened() }
        assertNull(vm.state.value.newTextId)

        // Edit content: one undo step, persists.
        onMain { vm.textEdit(id) { it.copy(text = "Edited content") } }
        idle(vm)
        assertEquals("Edited content", currentText(vm).text)

        // Change style: font/weight/size/align/ink, still selected throughout.
        onMain {
            vm.textEdit(id) {
                it.copy(
                    font = PdfFontFamily.Serif,
                    weight = PdfFontWeight.Bold,
                    sizePt = 24.0,
                    align = PdfTextAlign.End,
                    ink = PdfInk.Blue,
                )
            }
        }
        idle(vm)
        val styled = currentText(vm)
        assertEquals(PdfFontFamily.Serif, styled.font)
        assertEquals(PdfFontWeight.Bold, styled.weight)
        assertEquals(24.0, styled.sizePt, 0.0001)
        assertEquals(PdfTextAlign.End, styled.align)
        assertEquals(PdfInk.Blue, styled.ink)

        // Move: moveTextBy (keyboard-nudge path) and moveTextTo (drag-release path).
        val before = currentText(vm)
        onMain { vm.moveTextBy(5.0, 5.0) }
        idle(vm)
        assertEquals(before.x + 5.0, currentText(vm).x, 0.0001)
        assertEquals(before.y + 5.0, currentText(vm).y, 0.0001)
        onMain { vm.moveTextTo(20.0, 30.0) }
        idle(vm)
        assertEquals(20.0, currentText(vm).x, 0.0001)
        assertEquals(30.0, currentText(vm).y, 0.0001)

        // Resize: box only, never the font size (sizePt untouched by resizeSelectedTextFromCorner).
        val beforeResize = currentText(vm)
        onMain { vm.resizeSelectedTextFromCorner(PdfGeometry.Corner.BottomRight, 10.0, 10.0) }
        idle(vm)
        val resized = currentText(vm)
        assertEquals(beforeResize.width + 10.0, resized.width, 0.0001)
        assertEquals(beforeResize.height + 10.0, resized.height, 0.0001)
        assertEquals(beforeResize.sizePt, resized.sizePt, 0.0001)

        // moveSelected dispatches to the text when a text (not an image) is selected.
        val beforeNudge = currentText(vm)
        onMain { vm.moveSelected(1.0, 0.0) }
        idle(vm)
        assertEquals(beforeNudge.x + 1.0, currentText(vm).x, 0.0001)

        // Undo/redo: each of the above was one step; undo walks all the way back to "no texts".
        var guard = 0
        while (vm.state.value.canUndo && guard++ < 20) {
            onMain { vm.undo() }
            idle(vm)
        }
        assertTrue(vm.state.value.project!!.pages[0].texts.isEmpty())
        var redoGuard = 0
        while (vm.state.value.canRedo && redoGuard++ < 20) {
            onMain { vm.redo() }
            idle(vm)
        }
        assertEquals(1, vm.state.value.project!!.pages[0].texts.size)
        assertEquals("Edited content", vm.state.value.project!!.pages[0].texts.first().text)

        // Persistence: autosave is debounced ~400ms (PdfStudioViewModel.scheduleSave) - wait
        // longer than that before reading the repository back, or this reads a stale (pre-redo)
        // save. (saveState alone isn't a reliable signal here: it can already read Saved from an
        // earlier cycle before this redo's own debounced write has even started.)
        delay(700)
        val reloaded = repo.load(vm.state.value.project!!.id)!!
        assertEquals(1, reloaded.pages[0].texts.size)
        assertEquals("Edited content", reloaded.pages[0].texts.first().text)
    }

    @Test
    fun deleteSelectedRemovesTheTextNotAnImage(): Unit = runBlocking {
        val vm = openFreshProject()
        onMain { vm.addText("To delete") }
        idle(vm)
        assertEquals(1, vm.state.value.project!!.pages[0].texts.size)
        onMain { vm.deleteSelected() }
        idle(vm)
        assertTrue(vm.state.value.project!!.pages[0].texts.isEmpty())
        assertNull(vm.state.value.selectedTextId)
    }

    @Test
    fun duplicateSelectedCopiesTheTextOffsetAndSelectsTheCopy(): Unit = runBlocking {
        val vm = openFreshProject()
        onMain { vm.addText("Original") }
        idle(vm)
        val originalId = vm.state.value.selectedTextId!!
        val original = currentText(vm)
        onMain { vm.duplicateSelected() }
        idle(vm)
        assertEquals(2, vm.state.value.project!!.pages[0].texts.size)
        assertNotEquals(originalId, vm.state.value.selectedTextId)
        val copy = currentText(vm)
        assertEquals("Original", copy.text)
        assertEquals(original.x + 8.0, copy.x, 0.0001)
        assertEquals(original.y + 8.0, copy.y, 0.0001)
    }

    @Test
    fun layerMenuChangesZOrderAndKeepsTheSameTextSelected(): Unit = runBlocking {
        // The tie-normalization math itself is covered by PdfModelsTest's
        // normalizeZ*/withZOnlyChangesTheMatchingElementRegardlessOfKind tests (JVM, pure); this
        // device test exercises the VM-level Layer commands end to end against a real project.
        val vm = openFreshProject()
        onMain { vm.addText("First") }
        idle(vm)
        onMain { vm.addText("On top") }
        idle(vm)
        val topId = vm.state.value.selectedTextId!!
        val firstId = vm.state.value.project!!.pages[0].texts.first { it.id != topId }.id
        val topZBefore = currentText(vm).z
        onMain { vm.sendSelectedBackward() }
        idle(vm)
        // Selection follows the same text id even though its z (and paint order) changed.
        assertEquals(topId, vm.state.value.selectedTextId)
        assertTrue(currentText(vm).z < topZBefore)
        onMain { vm.bringSelectedToFront() }
        idle(vm)
        assertEquals(topId, vm.state.value.selectedTextId)
        val order = PdfLayers.order(vm.state.value.project!!.pages[0])
        assertEquals(topId, PdfLayers.elementId(order.last()))
        assertTrue(order.any { PdfLayers.elementId(it) == firstId })
    }

    @Test
    fun unsupportedGlyphsAreRejectedOnAdd(): Unit = runBlocking {
        val vm = openFreshProject()
        // CJK is outside the bundled Latin/Greek/Cyrillic fonts' cmap (PdfTextSupport) - rejected,
        // not silently exported as empty boxes.
        onMain { vm.addText("漢字") }
        idle(vm)
        assertTrue(vm.state.value.project!!.pages[0].texts.isEmpty())
        assertEquals(PdfFailure.UnsupportedGlyph.message, vm.state.value.message)
    }

    @Test
    fun unsupportedGlyphsAreRejectedOnEditToo(): Unit = runBlocking {
        val vm = openFreshProject()
        onMain { vm.addText("Hello") }
        idle(vm)
        val id = vm.state.value.selectedTextId!!
        // textEdit itself doesn't validate (it's the raw pageEdit primitive); the UI layers
        // (inline editor, inspector content field) call PdfTextSupport.check before committing -
        // this proves the gate itself still rejects it, matching PdfTextSupportTest's JVM coverage
        // but confirming it also runs against a real persisted project via PdfProject.validate.
        assertTrue(PdfTextSupport.check("漢字").isFailure)
        // A commit path that skips validation (like PdfProject.validate itself) must still catch
        // it - update()/pageEdit() run validate() before ever persisting, so an attempted commit
        // of an unsupported string is rejected wholesale rather than partially applied.
        val before = currentText(vm)
        onMain { vm.textEdit(id) { it.copy(text = "漢字") } }
        idle(vm)
        // validate() failed inside update(); the project is unchanged.
        assertEquals(before.text, currentText(vm).text)
    }

    @Test
    fun addTextRespectsThePageFullLimit(): Unit = runBlocking {
        val vm = openFreshProject()
        repeat(24) { n ->
            onMain { vm.addText("t$n") }
            idle(vm)
        }
        assertEquals(24, vm.state.value.project!!.pages[0].texts.size)
        onMain { vm.addText("one too many") }
        idle(vm)
        assertEquals(24, vm.state.value.project!!.pages[0].texts.size)
        assertEquals(PdfFailure.PageFull.message, vm.state.value.message)
    }
}
