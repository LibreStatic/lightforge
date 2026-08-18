package com.ugallery.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditModelsTest {
    private val source = MediaKey("external_primary", 42)

    @Test
    fun historySupportsUndoRedoAndInvalidatesRedoAfterNewCommand() {
        val initial = EditRecipe.forSource(source, 7)
        val history = EditHistory.initial(initial)
            .apply(EditOperation.Rotate(90))
            .apply(EditOperation.Filter("natural"))

        assertEquals(2, history.present.operations.size)
        val undone = history.undo()
        assertEquals(listOf(EditOperation.Rotate(90)), undone.present.operations)
        val redone = undone.redo()
        assertEquals(history.present, redone.present)
        val branched = undone.apply(EditOperation.Flip(horizontal = true))
        assertTrue(branched.future.isEmpty())
        assertEquals(
            listOf(EditOperation.Rotate(90), EditOperation.Flip(true)),
            branched.present.operations,
        )
    }

    @Test
    fun operationCodecRoundTripsEveryOperation() {
        val operations = listOf(
            EditOperation.Crop(10, 20, 900, 950),
            EditOperation.Rotate(-90),
            EditOperation.Flip(horizontal = false),
            EditOperation.Tone(0.1f, 1.2f, 0.8f),
            EditOperation.Filter("mono"),
        )
        operations.forEach { assertEquals(it, EditOperationCodec.decode(EditOperationCodec.encode(it))) }
    }

    @Test
    fun recipeIdIncludesStableMediaStoreIdentityAndGeneration() {
        assertEquals("external_primary:42:7", EditRecipeIds.forSource(source, 7))
        assertTrue(EditRecipe.forSource(source, 7).isIdentity)
    }
}
