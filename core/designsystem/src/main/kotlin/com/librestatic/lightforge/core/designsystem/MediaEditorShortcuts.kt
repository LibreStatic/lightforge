package com.librestatic.lightforge.core.designsystem

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

/** Hardware-keyboard commands shared by the media editors. */
enum class MediaEditorShortcut {
    /** Space. */
    PlayPause,
    /** J: shuttle back (jump back while keeping the play state). */
    ShuttleBack,
    /** K: stop the shuttle (pause). */
    ShuttleStop,
    /** L: shuttle forward (play). */
    ShuttleForward,
    /** I: mark in. */
    MarkIn,
    /** O: mark out. */
    MarkOut,
    /** ←: one frame back. */
    StepBack,
    /** →: one frame forward. */
    StepForward,
    /** Shift+←: one second back. */
    JumpBack,
    /** Shift+→: one second forward. */
    JumpForward,
    /** Ctrl+Z (Cmd+Z). */
    Undo,
    /** Ctrl+Shift+Z or Ctrl+Y (Cmd variants too). */
    Redo,
    /** Esc. */
    Cancel,
}

object MediaEditorShortcuts {
    /** Maps a key press to a command, or null when the editors do not use it. */
    fun resolve(key: Key, ctrl: Boolean, shift: Boolean, alt: Boolean = false): MediaEditorShortcut? {
        if (alt) return null
        if (ctrl) return when (key) {
            Key.Z -> if (shift) MediaEditorShortcut.Redo else MediaEditorShortcut.Undo
            Key.Y -> if (shift) null else MediaEditorShortcut.Redo
            else -> null
        }
        return when (key) {
            Key.Spacebar -> if (shift) null else MediaEditorShortcut.PlayPause
            Key.J -> MediaEditorShortcut.ShuttleBack
            Key.K -> MediaEditorShortcut.ShuttleStop
            Key.L -> MediaEditorShortcut.ShuttleForward
            Key.I -> MediaEditorShortcut.MarkIn
            Key.O -> MediaEditorShortcut.MarkOut
            Key.DirectionLeft -> if (shift) MediaEditorShortcut.JumpBack else MediaEditorShortcut.StepBack
            Key.DirectionRight -> if (shift) MediaEditorShortcut.JumpForward else MediaEditorShortcut.StepForward
            Key.Escape -> MediaEditorShortcut.Cancel
            else -> null
        }
    }
}

/**
 * Handles [MediaEditorShortcut]s for an editor screen. The modifier makes the editor a focus target
 * and takes focus once, so shortcuts work before anything is tapped. Keys reach it only after the
 * focused child had its chance (a text field keeps its letters, a slider its arrows).
 *
 * [onShortcut] returns true when it acted on the command; unhandled keys keep propagating.
 */
@Composable
fun Modifier.mediaEditorShortcuts(onShortcut: (MediaEditorShortcut) -> Boolean): Modifier {
    val focusRequester = remember { FocusRequester() }
    val handler by rememberUpdatedState(onShortcut)
    LaunchedEffect(focusRequester) { runCatching { focusRequester.requestFocus() } }
    return this
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            val command = MediaEditorShortcuts.resolve(
                key = event.key,
                ctrl = event.isCtrlPressed || event.isMetaPressed,
                shift = event.isShiftPressed,
                alt = event.isAltPressed,
            ) ?: return@onKeyEvent false
            handler(command)
        }
        .focusRequester(focusRequester)
        .focusable()
}
