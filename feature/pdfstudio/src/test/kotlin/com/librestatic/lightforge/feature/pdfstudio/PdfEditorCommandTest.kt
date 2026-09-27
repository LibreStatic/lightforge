package com.librestatic.lightforge.feature.pdfstudio

import org.junit.Assert.*
import org.junit.Test

class PdfEditorCommandTest {
    private fun chord(key: String, ctrl: Boolean = true, shift: Boolean = false) =
        PdfKeyChord(key, ctrl, shift)

    @Test
    fun undoRedoRequireCtrlAndShiftDistinguishesThem() {
        assertEquals(PdfEditorCommand.Undo, PdfEditorCommands.forChord(chord("Z")))
        assertEquals(PdfEditorCommand.Redo, PdfEditorCommands.forChord(chord("Z", shift = true)))
        assertNull(PdfEditorCommands.forChord(chord("Z", ctrl = false)))
    }

    @Test
    fun duplicateExportFitAndZoomChords() {
        assertEquals(PdfEditorCommand.DuplicateSelection, PdfEditorCommands.forChord(chord("D")))
        assertEquals(PdfEditorCommand.OpenExportSheet, PdfEditorCommands.forChord(chord("E")))
        assertEquals(PdfEditorCommand.FitPage, PdfEditorCommands.forChord(chord("0")))
        assertEquals(PdfEditorCommand.ZoomIn, PdfEditorCommands.forChord(chord("=")))
        assertEquals(PdfEditorCommand.ZoomOut, PdfEditorCommands.forChord(chord("-")))
        assertEquals(PdfEditorCommand.ShowShortcuts, PdfEditorCommands.forChord(chord("/")))
    }

    @Test
    fun unknownChordsAndBareKeysWithoutCtrlAreNotCommands() {
        assertNull(PdfEditorCommands.forChord(chord("A")))
        assertNull(PdfEditorCommands.forChord(chord("D", ctrl = false)))
        assertNull(PdfEditorCommands.forChord(chord("/", ctrl = false)))
    }

    @Test
    fun deleteAndBackspaceMapWithoutAnyModifier() {
        assertEquals(PdfEditorCommand.DeleteSelection, PdfEditorCommands.forDeleteKey("Delete"))
        assertEquals(PdfEditorCommand.DeleteSelection, PdfEditorCommands.forDeleteKey("Backspace"))
        assertNull(PdfEditorCommands.forDeleteKey("D"))
    }
}
