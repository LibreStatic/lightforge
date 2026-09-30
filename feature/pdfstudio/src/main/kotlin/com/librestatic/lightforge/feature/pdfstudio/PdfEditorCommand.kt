package com.librestatic.lightforge.feature.pdfstudio

/**
 * Every action reachable from more than one input path in the editor — the top bar, the canvas's
 * contextual toolbar/menus, and the keyboard (Phase F2 item C). Routing all three through one
 * enum + one dispatcher means a shortcut and its equivalent button can never silently drift apart.
 */
internal enum class PdfEditorCommand {
    Undo,
    Redo,
    /** Duplicates the selected image, or the current page when no image is selected. */
    DuplicateSelection,
    /** Removes the selected image (undoable); a no-op with nothing selected. */
    DeleteSelection,
    OpenExportSheet,
    FitPage,
    ZoomIn,
    ZoomOut,
    ShowShortcuts,
    /** Ctrl+A (Phase G2): selects every element on the current page as a group. */
    SelectAll,
}

/** A keyboard chord in a form plain enough to unit-test without any Compose/Android KeyEvent
 * dependency; [PdfCanvas]'s `onPreviewKeyEvent` builds one of these per key-down and asks
 * [PdfEditorCommands.forChord] what it means. */
internal data class PdfKeyChord(val key: String, val ctrl: Boolean, val shift: Boolean)

internal object PdfEditorCommands {
    /** Maps a keyboard chord to the command it invokes, or null when the chord isn't one of ours
     * (so the caller falls through to its own handling — arrow-key nudge, typing, etc). Every
     * chord here requires Ctrl, matching the plan (Ctrl+Z/Shift+Z, Ctrl+D, Ctrl+E, Ctrl+0,
     * Ctrl+=/-, Ctrl+/); Delete/Backspace (no modifier) map to [PdfEditorCommand.DeleteSelection]
     * via [forDeleteKey] since they're a different key family (no Ctrl chord at all). */
    fun forChord(chord: PdfKeyChord): PdfEditorCommand? {
        if (!chord.ctrl) return null
        return when (chord.key) {
            "Z" -> if (chord.shift) PdfEditorCommand.Redo else PdfEditorCommand.Undo
            "D" -> PdfEditorCommand.DuplicateSelection
            "E" -> PdfEditorCommand.OpenExportSheet
            "0" -> PdfEditorCommand.FitPage
            "=", "Plus", "NumPadAdd" -> PdfEditorCommand.ZoomIn
            "-", "Minus", "NumPadSubtract" -> PdfEditorCommand.ZoomOut
            "/", "Slash" -> PdfEditorCommand.ShowShortcuts
            "A" -> PdfEditorCommand.SelectAll
            else -> null
        }
    }

    /** Delete/Backspace need no Ctrl and only make sense while something is selected; kept
     * separate from [forChord] instead of folding a "no modifier" branch into it, since every
     * other command in [forChord] specifically requires Ctrl. */
    fun forDeleteKey(key: String): PdfEditorCommand? =
        if (key == "Delete" || key == "Backspace") PdfEditorCommand.DeleteSelection else null
}

/**
 * The one implementation shared by the top bar's Undo/Redo/Export buttons, the shortcuts sheet's
 * rows, and the canvas's keyboard handler. UI-only effects (opening the export sheet or the
 * shortcuts sheet) are supplied as callbacks since [PdfStudioViewModel] itself has no notion of
 * sheet visibility.
 */
internal class PdfEditorCommandDispatcher(
    private val vm: PdfStudioViewModel,
    private val onOpenExportSheet: () -> Unit,
    private val onShowShortcuts: () -> Unit,
) {
    fun dispatch(command: PdfEditorCommand) {
        when (command) {
            PdfEditorCommand.Undo -> vm.undo()
            PdfEditorCommand.Redo -> vm.redo()
            // Both group ops fall back to the single-element path themselves when fewer than 2
            // elements are selected, so routing through them here covers both cases.
            PdfEditorCommand.DuplicateSelection -> vm.duplicateGroupSelection()
            PdfEditorCommand.DeleteSelection -> vm.deleteGroupSelection()
            PdfEditorCommand.OpenExportSheet -> onOpenExportSheet()
            PdfEditorCommand.FitPage -> vm.viewport(1f, 0f, 0f)
            PdfEditorCommand.ZoomIn -> vm.zoomBy(1.25f)
            PdfEditorCommand.ZoomOut -> vm.zoomBy(0.8f)
            PdfEditorCommand.ShowShortcuts -> onShowShortcuts()
            PdfEditorCommand.SelectAll -> vm.selectAllOnPage()
        }
    }
}
