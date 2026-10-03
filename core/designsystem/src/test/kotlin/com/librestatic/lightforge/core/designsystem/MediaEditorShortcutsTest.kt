package com.librestatic.lightforge.core.designsystem

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaEditorShortcutsTest {
    private fun resolve(key: Key, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false) =
        MediaEditorShortcuts.resolve(key, ctrl, shift, alt)

    @Test
    fun transportKeysFollowTheJklConvention() {
        assertEquals(MediaEditorShortcut.ShuttleBack, resolve(Key.J))
        assertEquals(MediaEditorShortcut.ShuttleStop, resolve(Key.K))
        assertEquals(MediaEditorShortcut.ShuttleForward, resolve(Key.L))
        assertEquals(MediaEditorShortcut.PlayPause, resolve(Key.Spacebar))
        assertEquals(MediaEditorShortcut.MarkIn, resolve(Key.I))
        assertEquals(MediaEditorShortcut.MarkOut, resolve(Key.O))
        assertEquals(MediaEditorShortcut.Cancel, resolve(Key.Escape))
    }

    @Test
    fun arrowsStepFramesAndShiftJumps() {
        assertEquals(MediaEditorShortcut.StepBack, resolve(Key.DirectionLeft))
        assertEquals(MediaEditorShortcut.StepForward, resolve(Key.DirectionRight))
        assertEquals(MediaEditorShortcut.JumpBack, resolve(Key.DirectionLeft, shift = true))
        assertEquals(MediaEditorShortcut.JumpForward, resolve(Key.DirectionRight, shift = true))
    }

    @Test
    fun historyNeedsControlAndOtherChordsAreIgnored() {
        assertEquals(MediaEditorShortcut.Undo, resolve(Key.Z, ctrl = true))
        assertEquals(MediaEditorShortcut.Redo, resolve(Key.Z, ctrl = true, shift = true))
        assertEquals(MediaEditorShortcut.Redo, resolve(Key.Y, ctrl = true))
        assertNull(resolve(Key.Z))
        assertNull(resolve(Key.J, ctrl = true))
        assertNull(resolve(Key.Spacebar, alt = true))
        assertNull(resolve(Key.A))
    }
}
