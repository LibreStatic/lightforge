package com.librestatic.lightforge

import com.librestatic.lightforge.core.model.EditHistory
import com.librestatic.lightforge.core.model.EditOperation
import com.librestatic.lightforge.core.model.EditRecipe
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.feature.photoeditor.PhotoEditorContentState
import com.librestatic.lightforge.feature.photoeditor.PhotoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoEraseHistoryTest {
    private val first = listOf(PhotoPoint(0.2f, 0.2f))
    private val second = listOf(PhotoPoint(0.7f, 0.7f))

    private fun session(maxEntries: Int = 100) = PhotoEditorSession(
        source = EditorMediaSource("content://photo", MediaKind.Image),
        history = EditHistory.initial(EditRecipe("recipe", null, 0), maxEntries),
        content = PhotoEditorContentState(),
    )

    private fun PhotoEditorSession.erase(points: List<PhotoPoint>) =
        copy(eraseMarks = eraseMarks + points).withEraseCheckpoint()

    private fun PhotoEditorSession.marksAfter(move: (EditHistory) -> EditHistory) = eraseStepFor(move(history)).marks

    @Test
    fun undoAndRedoWalkThroughEachErase() {
        val erased = session().erase(first).erase(second)
        assertEquals(first + second, erased.marksAfter { it })
        assertEquals(first, erased.marksAfter { it.undo() })
        assertEquals(emptyList<PhotoPoint>(), erased.marksAfter { it.undo().undo() })
        assertEquals(first, erased.copy(history = erased.history.undo().undo()).marksAfter { it.redo() })
        assertTrue(erased.content.canUndo)
    }

    @Test
    fun recipeEditsInheritTheMarksBeforeThem() {
        val erased = session().erase(first)
        val adjusted = erased.copy(history = erased.history.apply(EditOperation.Rotate(90)))
        assertEquals(first, adjusted.marksAfter { it })
        // Undoing the rotation keeps the erase; undoing again removes it.
        assertEquals(first, adjusted.marksAfter { it.undo() })
        assertEquals(emptyList<PhotoPoint>(), adjusted.marksAfter { it.undo().undo() })
    }

    @Test
    fun aNewEditAfterUndoDropsTheRedoneErase() {
        val erased = session().erase(first).erase(second)
        val branched = erased.copy(history = erased.history.undo()).let { it.copy(eraseMarks = first) }.erase(listOf(PhotoPoint(0.5f, 0.5f)))
        assertEquals(first + PhotoPoint(0.5f, 0.5f), branched.marksAfter { it })
        // Steps of the discarded branch are pruned.
        assertEquals(2, branched.eraseSteps.size)
    }

    @Test
    fun clearingIsAStepToo() {
        val erased = session().erase(first)
        val cleared = erased.copy(eraseMarks = emptyList(), eraseGeometry = null).withEraseCheckpoint()
        assertEquals(emptyList<PhotoPoint>(), cleared.marksAfter { it })
        assertEquals(first, cleared.marksAfter { it.undo() })
    }

    @Test
    fun anEraseOlderThanTheCappedHistoryStillApplies() {
        var edited = session(maxEntries = 3).erase(first)
        repeat(5) { edited = edited.copy(history = edited.history.apply(EditOperation.Straighten(it + 1f))) }
        assertEquals(first, edited.marksAfter { it.undo().undo().undo() })
    }
}
