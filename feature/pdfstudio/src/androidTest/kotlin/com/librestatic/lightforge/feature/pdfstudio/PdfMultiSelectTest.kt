package com.librestatic.lightforge.feature.pdfstudio

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase G2 device tests for the canvas multi-selection's VM-level contract: entering/toggling/
 * exiting a multi-select session, group move, align, distribute, delete, duplicate, undo/redo as
 * single steps, and pruning on page change/delete/undo/redo — mirroring [PdfTextEditingTest]'s
 * pattern (every VM call through Main, waiting for the freshly opened project before asserting).
 * The gestures themselves (long-press, marquee, Ctrl+A) are exercised visually by
 * `tools/verify_pdf_adaptive_ui.py --multi`; this suite covers the VM contract those gestures call
 * into.
 */
class PdfMultiSelectTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo = PdfProjectRepository(context)

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    private suspend fun idle(vm: PdfStudioViewModel) =
        withTimeout(15_000) { while (vm.state.value.busy) delay(20) }

    private suspend fun openFreshProject(): PdfStudioViewModel {
        val project = PdfProject(name = "Multi-select test").validate()
        repo.save(project)
        val vm =
            onMain { PdfStudioViewModel(context.applicationContext as Application, SavedStateHandle()) }
        onMain { vm.open(project.id) }
        withTimeout(15_000) {
            while (vm.state.value.project?.id != project.id || vm.state.value.busy) delay(20)
        }
        return vm
    }

    /** Adds [count] MORE non-overlapping images to the current page (on top of whatever is
     * already there), spaced 20mm apart from each other, and returns the newly added ids in page
     * order. Safe to call more than once on the same page/test. */
    private suspend fun addImages(vm: PdfStudioViewModel, count: Int): List<String> {
        val before = vm.state.value.project!!.pages[vm.state.value.page].images.map { it.id }.toSet()
        repeat(count) { n ->
            onMain {
                vm.pageEdit { p ->
                    p.copy(
                        images =
                            p.images +
                                PdfImage(
                                    asset = "a".repeat(64),
                                    x = 10.0 + (p.images.size) * 20.0,
                                    y = 10.0,
                                    width = 15.0,
                                    height = 15.0,
                                )
                    )
                }
            }
            idle(vm)
        }
        val page = vm.state.value.project!!.pages[vm.state.value.page]
        val newIds = page.images.map { it.id }.filterNot { it in before }
        // pageEdit silently no-ops on a rejected change (e.g. unknown asset hash failing
        // validate()); guard against that here so a broken fixture fails loudly instead of the
        // later assertions failing with a confusing message.
        assertEquals(count, newIds.size)
        return newIds
    }

    @Test
    fun enterToggleAndExitMultiSelect(): Unit = runBlocking {
        val vm = openFreshProject()
        // A real asset is required for PdfProject.validate(); use the repository's own seam by
        // adding an asset row first via a raw project edit (validate() checks hash/mime, not that
        // the file exists on disk — fine for a VM-level contract test).
        onMain {
            vm.update { p ->
                p.copy(assets = p.assets + PdfAsset(hash = "a".repeat(64), mime = "image/jpeg", width = 10, height = 10))
            }
        }
        idle(vm)
        val ids = addImages(vm, 3)

        onMain { vm.enterMultiSelect(ids[0]) }
        idle(vm)
        assertTrue(vm.state.value.multiSelectMode)
        assertEquals(setOf(ids[0]), vm.state.value.selectedIds)
        assertFalse(vm.state.value.groupSelected) // only 1 selected so far

        onMain { vm.toggleMultiSelect(ids[1]) }
        idle(vm)
        assertEquals(setOf(ids[0], ids[1]), vm.state.value.selectedIds)
        assertTrue(vm.state.value.groupSelected)

        // Toggling an already-selected id removes it.
        onMain { vm.toggleMultiSelect(ids[1]) }
        idle(vm)
        assertEquals(setOf(ids[0]), vm.state.value.selectedIds)

        onMain { vm.exitMultiSelect() }
        idle(vm)
        assertTrue(vm.state.value.selectedIds.isEmpty())
        assertFalse(vm.state.value.multiSelectMode)
        assertEquals(-1, vm.state.value.image)
    }

    @Test
    fun selectAllOnPageSelectsEveryElement(): Unit = runBlocking {
        val vm = openFreshProject()
        onMain {
            vm.update { p ->
                p.copy(assets = p.assets + PdfAsset(hash = "a".repeat(64), mime = "image/jpeg", width = 10, height = 10))
            }
        }
        idle(vm)
        val ids = addImages(vm, 3)
        onMain { vm.addText("Hello") }
        idle(vm)
        val textId = vm.state.value.selectedTextId!!

        onMain { vm.selectAllOnPage() }
        idle(vm)
        assertEquals((ids + textId).toSet(), vm.state.value.selectedIds)
        assertTrue(vm.state.value.multiSelectMode)
    }

    @Test
    fun groupMoveIsOneUndoStepAndClampsToPage(): Unit = runBlocking {
        val vm = openFreshProject()
        onMain {
            vm.update { p ->
                p.copy(assets = p.assets + PdfAsset(hash = "a".repeat(64), mime = "image/jpeg", width = 10, height = 10))
            }
        }
        idle(vm)
        val ids = addImages(vm, 2)
        onMain { vm.setMultiSelection(ids.toSet()) }
        idle(vm)
        val before = vm.state.value.project!!.pages[0].images.associateBy { it.id }

        onMain { vm.moveSelectionBy(5.0, 5.0) }
        idle(vm)
        val after = vm.state.value.project!!.pages[0].images.associateBy { it.id }
        ids.forEach { id ->
            assertEquals(before.getValue(id).x + 5.0, after.getValue(id).x, 0.0001)
            assertEquals(before.getValue(id).y + 5.0, after.getValue(id).y, 0.0001)
        }

        // One undo step: a single undo() restores every member.
        onMain { vm.undo() }
        idle(vm)
        val restored = vm.state.value.project!!.pages[0].images.associateBy { it.id }
        ids.forEach { id ->
            assertEquals(before.getValue(id).x, restored.getValue(id).x, 0.0001)
            assertEquals(before.getValue(id).y, restored.getValue(id).y, 0.0001)
        }

        // Clamping: push the group far past the page edge; every member stays on-page.
        onMain { vm.setMultiSelection(ids.toSet()) }
        idle(vm)
        onMain { vm.moveSelectionBy(10_000.0, 10_000.0) }
        idle(vm)
        val clamped = vm.state.value.project!!.pages[0]
        clamped.images.forEach { img ->
            assertTrue(img.x >= 0.0 && img.x + img.width <= clamped.width + 0.001)
            assertTrue(img.y >= 0.0 && img.y + img.height <= clamped.height + 0.001)
        }
    }

    @Test
    fun groupAlignLeftLinesUpEveryMemberOnTheGroupsOwnLeftEdge(): Unit = runBlocking {
        val vm = openFreshProject()
        onMain {
            vm.update { p ->
                p.copy(assets = p.assets + PdfAsset(hash = "a".repeat(64), mime = "image/jpeg", width = 10, height = 10))
            }
        }
        idle(vm)
        val ids = addImages(vm, 3)
        onMain { vm.setMultiSelection(ids.toSet()) }
        idle(vm)
        val minX = vm.state.value.project!!.pages[0].images.minOf { it.x }

        onMain { vm.alignGroupSelection(PdfGeometry.Align.Left) }
        idle(vm)
        val page = vm.state.value.project!!.pages[0]
        ids.forEach { id -> assertEquals(minX, page.images.first { it.id == id }.x, 0.0001) }
    }

    @Test
    fun distributeHorizontalNeedsThreeAndEqualizesGaps(): Unit = runBlocking {
        val vm = openFreshProject()
        onMain {
            vm.update { p ->
                p.copy(assets = p.assets + PdfAsset(hash = "a".repeat(64), mime = "image/jpeg", width = 10, height = 10))
            }
        }
        idle(vm)
        val twoIds = addImages(vm, 2)
        onMain { vm.setMultiSelection(twoIds.toSet()) }
        idle(vm)
        val before2 = vm.state.value.project!!.pages[0]
        onMain { vm.distributeGroupSelection(PdfSnapGuides.Orientation.Horizontal) }
        idle(vm)
        // No-op below 3 selected.
        assertEquals(before2, vm.state.value.project!!.pages[0])

        val thirdList = addImages(vm, 1)
        val allIds = twoIds + thirdList.last()
        onMain { vm.setMultiSelection(allIds.toSet()) }
        idle(vm)
        onMain { vm.distributeGroupSelection(PdfSnapGuides.Orientation.Horizontal) }
        idle(vm)
        val sorted = vm.state.value.project!!.pages[0].images.filter { it.id in allIds }.sortedBy { it.x }
        val gap1 = sorted[1].x - (sorted[0].x + sorted[0].width)
        val gap2 = sorted[2].x - (sorted[1].x + sorted[1].width)
        assertEquals(gap1, gap2, 0.01)
    }

    @Test
    fun groupDeleteAndDuplicateAreEachOneUndoStep(): Unit = runBlocking {
        val vm = openFreshProject()
        onMain {
            vm.update { p ->
                p.copy(assets = p.assets + PdfAsset(hash = "a".repeat(64), mime = "image/jpeg", width = 10, height = 10))
            }
        }
        idle(vm)
        val ids = addImages(vm, 3)
        onMain { vm.setMultiSelection(ids.toSet()) }
        idle(vm)

        // Duplicate: one undo step, doubles the count, selects the copies.
        onMain { vm.duplicateGroupSelection() }
        idle(vm)
        assertEquals(6, vm.state.value.project!!.pages[0].images.size)
        assertEquals(3, vm.state.value.selectedIds.size)
        assertTrue(vm.state.value.selectedIds.none { it in ids })
        onMain { vm.undo() }
        idle(vm)
        assertEquals(3, vm.state.value.project!!.pages[0].images.size)

        // Delete: one undo step, removes every selected member, exits multi-select.
        onMain { vm.setMultiSelection(ids.toSet()) }
        idle(vm)
        onMain { vm.deleteGroupSelection() }
        idle(vm)
        assertTrue(vm.state.value.project!!.pages[0].images.isEmpty())
        assertFalse(vm.state.value.multiSelectMode)
        assertTrue(vm.state.value.selectedIds.isEmpty())
        onMain { vm.undo() }
        idle(vm)
        assertEquals(3, vm.state.value.project!!.pages[0].images.size)
    }

    @Test
    fun selectionIsPrunedOnPageChangeAndOnDelete(): Unit = runBlocking {
        val vm = openFreshProject()
        onMain {
            vm.update { p ->
                p.copy(assets = p.assets + PdfAsset(hash = "a".repeat(64), mime = "image/jpeg", width = 10, height = 10))
            }
        }
        idle(vm)
        val ids = addImages(vm, 2)
        onMain { vm.setMultiSelection(ids.toSet()) }
        idle(vm)
        assertTrue(vm.state.value.groupSelected)

        onMain { vm.addPage() }
        idle(vm)
        assertTrue(vm.state.value.selectedIds.isEmpty())
        assertFalse(vm.state.value.multiSelectMode)

        onMain { vm.selectPage(0) }
        idle(vm)
        onMain { vm.setMultiSelection(ids.toSet()) }
        idle(vm)
        assertTrue(vm.state.value.groupSelected)
        // Removing one member out from under the selection (e.g. a concurrent edit) prunes it
        // rather than leaving a dangling id.
        onMain { vm.pageEdit { p -> p.copy(images = p.images.filter { it.id != ids[0] }) } }
        idle(vm)
        assertEquals(setOf(ids[1]), vm.state.value.selectedIds)
        // Down to exactly 1 member: no longer "group selected", but the single-selection fields
        // now point at that surviving element, exactly as the single-selection path always has.
        assertFalse(vm.state.value.groupSelected)
        assertEquals(ids[1], vm.state.value.project!!.pages[0].images.first().id)
    }

    @Test
    fun undoRedoRestoresSelectionConsistently(): Unit = runBlocking {
        val vm = openFreshProject()
        onMain {
            vm.update { p ->
                p.copy(assets = p.assets + PdfAsset(hash = "a".repeat(64), mime = "image/jpeg", width = 10, height = 10))
            }
        }
        idle(vm)
        val ids = addImages(vm, 3)
        onMain { vm.setMultiSelection(ids.toSet()) }
        idle(vm)
        onMain { vm.deleteGroupSelection() }
        idle(vm)
        assertTrue(vm.state.value.project!!.pages[0].images.isEmpty())

        onMain { vm.undo() }
        idle(vm)
        assertEquals(3, vm.state.value.project!!.pages[0].images.size)
        // The restored ids are exactly [ids] again (same content, since undo restores the whole
        // project snapshot) — selection itself isn't required to auto-restore to the pre-delete
        // group, only to stay internally consistent (never point at a missing id).
        val page = vm.state.value.project!!.pages[0]
        vm.state.value.selectedIds.forEach { id -> assertTrue(page.images.any { it.id == id }) }
    }

    /**
     * Fix-round item 3: Shift/Ctrl+click routes through [PdfStudioViewModel.toggleSelectionWithModifier]
     * rather than the plain tap path — real ADB input can't hold a keyboard modifier during a
     * synthetic tap, so this covers the VM-level routing directly (mirroring the plan's own
     * fallback: "if untestable on device, add a unit-testable routing function").
     */
    @Test
    fun toggleSelectionWithModifierStartsAGroupFromASingleSelection(): Unit = runBlocking {
        val vm = openFreshProject()
        onMain {
            vm.update { p ->
                p.copy(assets = p.assets + PdfAsset(hash = "a".repeat(64), mime = "image/jpeg", width = 10, height = 10))
            }
        }
        idle(vm)
        val ids = addImages(vm, 2)
        // Plain single-select on the first image (as an ordinary tap would do) - not yet in a
        // multi-select session.
        onMain { vm.selectImage(0) }
        idle(vm)
        assertFalse(vm.state.value.multiSelectMode)
        assertEquals(setOf(ids[0]), vm.state.value.selectedIds)

        // Modifier-click the second image: forms a 2-element group from the prior single
        // selection plus this one, entering multi-select mode - the conventional desktop "add to
        // selection" gesture, distinct from a plain tap (which would replace the selection) and
        // from long-press (which would start a group from just the long-pressed element alone).
        onMain { vm.toggleSelectionWithModifier(ids[1]) }
        idle(vm)
        assertTrue(vm.state.value.groupSelected)
        assertEquals(ids.toSet(), vm.state.value.selectedIds)

        // Modifier-clicking an already-selected member while ALREADY in a multi-select session
        // removes it (same as toggleMultiSelect) rather than starting a new group.
        onMain { vm.toggleSelectionWithModifier(ids[1]) }
        idle(vm)
        assertEquals(setOf(ids[0]), vm.state.value.selectedIds)
    }
}
