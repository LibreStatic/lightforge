package com.librestatic.lightforge

import com.librestatic.lightforge.VideoEditHistory.Snapshot
import com.librestatic.lightforge.VideoEditHistory.Step
import com.librestatic.lightforge.core.editing.video.VideoEditRecipe
import com.librestatic.lightforge.feature.videoeditor.VideoEditorContentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoEditHistoryTest {
    private fun snapshot(speed: Float = 1f) = Snapshot(
        VideoEditRecipe(speed = speed),
        VideoEditorContentState(speed = speed),
    )

    @Test
    fun undoRestoresThePreviousRecipeAndRedoReturnsToTheLaterOne() {
        val history = VideoEditHistory()
        val before = snapshot(speed = 1f)
        val after = snapshot(speed = 2f)
        history.recordRecipeChange(before, "speed", nowMillis = 0)
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)

        assertEquals(Step.Restore(before), history.undo(current = after))
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)

        assertEquals(Step.Restore(after), history.redo(current = before))
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)
    }

    @Test
    fun rapidChangesOfTheSameControlMergeIntoOneStep() {
        val history = VideoEditHistory()
        val start = snapshot(speed = 1f)
        history.recordRecipeChange(start, "grade", nowMillis = 0)
        history.recordRecipeChange(snapshot(speed = 1.1f), "grade", nowMillis = 100)
        history.recordRecipeChange(snapshot(speed = 1.2f), "grade", nowMillis = 200)

        assertEquals(Step.Restore(start), history.undo(current = snapshot(speed = 1.3f)))
        assertNull("The whole drag is one step", history.undo(current = start))
    }

    @Test
    fun differentControlsOrSlowGesturesStaySeparateSteps() {
        val history = VideoEditHistory()
        history.recordRecipeChange(snapshot(speed = 1f), "grade", nowMillis = 0)
        history.recordRecipeChange(snapshot(speed = 1.5f), "geometry", nowMillis = 100)
        history.recordRecipeChange(snapshot(speed = 2f), "geometry", nowMillis = 100 + VideoEditHistory.CoalesceWindowMillis + 1)

        assertTrue(history.undo(snapshot()) is Step.Restore)
        assertTrue(history.undo(snapshot()) is Step.Restore)
        assertTrue(history.undo(snapshot()) is Step.Restore)
        assertNull(history.undo(snapshot()))
    }

    @Test
    fun undoWalksRecipeAndDrawingEditsInChronologicalOrder() {
        val history = VideoEditHistory()
        val first = snapshot(speed = 1f)
        history.recordRecipeChange(first, "speed", nowMillis = 0)
        history.recordAnnotationChange()

        assertEquals(Step.Annotation, history.undo(current = snapshot(speed = 2f)))
        assertEquals(Step.Restore(first), history.undo(current = snapshot(speed = 2f)))
        assertNull(history.undo(current = first))

        assertTrue(history.redo(current = first) is Step.Restore)
        assertEquals(Step.Annotation, history.redo(current = snapshot(speed = 2f)))
        assertNull(history.redo(current = snapshot(speed = 2f)))
    }

    @Test
    fun aNewEditDiscardsTheRedoStack() {
        val history = VideoEditHistory()
        history.recordRecipeChange(snapshot(speed = 1f), "speed", nowMillis = 0)
        history.undo(current = snapshot(speed = 2f))
        assertTrue(history.canRedo)

        history.recordRecipeChange(snapshot(speed = 1f), "grade", nowMillis = 10_000)
        assertFalse(history.canRedo)
    }

    @Test
    fun theHistoryIsBounded() {
        val history = VideoEditHistory(limit = 3)
        repeat(10) { history.recordRecipeChange(snapshot(speed = 1f + it * 0.1f), "k$it", nowMillis = it * 10_000L) }

        var steps = 0
        while (history.undo(snapshot()) != null) steps++
        assertEquals(3, steps)
    }

    @Test
    fun restoredAnnotationStacksAreMirroredInTheOrder() {
        val history = VideoEditHistory()
        history.seedAnnotationOrder(undoCount = 2, redoCount = 1)

        assertTrue(history.canUndo)
        assertTrue(history.canRedo)
        assertEquals(Step.Annotation, history.undo(snapshot()))
        assertEquals(Step.Annotation, history.undo(snapshot()))
        assertNull(history.undo(snapshot()))
    }

    @Test
    fun changeKeyNamesWhatChanged() {
        val base = VideoEditRecipe()
        assertEquals("speed", VideoEditHistory.changeKey(base, base.copy(speed = 2f)))
        assertEquals("trim", VideoEditHistory.changeKey(base, base.copy(startMillis = 500)))
        assertEquals("speed,musicVolume", VideoEditHistory.changeKey(base, base.copy(speed = 2f, musicVolume = 0.3f)))
    }

    @Test
    fun restoringContentKeepsTransientSessionState() {
        val current = VideoEditorContentState(
            currentMillis = 1_000, durationMillis = 10_000, trimStartMillis = 0, trimEndMillis = 10_000,
            speed = 2f, isExporting = true, statusMessage = "busy",
        )
        val saved = VideoEditorContentState(
            durationMillis = 10_000, trimStartMillis = 0, trimEndMillis = 5_000, speed = 1f,
        )
        val restored = with(VideoEditHistory) { current.withRecipeFieldsFrom(saved) }

        assertEquals(1f, restored.speed)
        assertEquals(5_000L, restored.trimEndMillis)
        assertEquals(1_000L, restored.currentMillis)
        assertTrue("Export progress is not part of the recipe", restored.isExporting)
        assertNull(restored.statusMessage)
    }
}
