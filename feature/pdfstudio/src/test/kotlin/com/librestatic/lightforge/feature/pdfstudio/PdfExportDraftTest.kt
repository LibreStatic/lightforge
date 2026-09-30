package com.librestatic.lightforge.feature.pdfstudio

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.*
import org.junit.Test

class PdfExportDraftTest {
    @Test
    fun putThenPeekReturnsTheSameDraftWithoutClearingIt() {
        val store = PdfExportDraftStore(SavedStateHandle())
        val draft = PdfExportDraft("project-1", compact = true, PdfExportPagesChoice.Selected, listOf("a", "b"))
        store.put(draft)
        assertEquals(draft, store.peek())
        assertEquals(draft, store.peek()) // peek never clears
    }

    @Test
    fun takeReturnsAndClearsTheDraft() {
        val store = PdfExportDraftStore(SavedStateHandle())
        val draft = PdfExportDraft("project-2", compact = false, PdfExportPagesChoice.All, emptyList())
        store.put(draft)
        assertEquals(draft, store.take())
        assertNull(store.peek())
        assertNull(store.take())
    }

    @Test
    fun discardClearsAPendingDraft() {
        val store = PdfExportDraftStore(SavedStateHandle())
        store.put(PdfExportDraft("project-3", compact = false, PdfExportPagesChoice.Current, listOf("page-1")))
        store.discard()
        assertNull(store.peek())
    }

    @Test
    fun noPendingDraftPeeksAsNull() {
        assertNull(PdfExportDraftStore(SavedStateHandle()).peek())
    }

    @Test
    fun draftSurvivesSavedStateRecreationLikeProcessDeath() {
        val saved = SavedStateHandle()
        val first = PdfExportDraftStore(saved)
        val draft = PdfExportDraft("project-4", compact = true, PdfExportPagesChoice.Selected, listOf("x", "y", "z"))
        first.put(draft)
        val restored =
            PdfExportDraftStore(
                SavedStateHandle(mapOf(PdfExportDraftStore.KEY to saved.get<ArrayList<String>>(PdfExportDraftStore.KEY)))
            )
        assertEquals(draft, restored.take())
    }

    @Test
    fun draftPickerIdsAreDistinguishableFromRealJobIds() {
        val id = draftPickerId()
        assertTrue(isDraftPickerId(id))
        assertFalse(isDraftPickerId(newId()))
    }
}
