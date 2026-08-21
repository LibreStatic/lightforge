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
            EditOperation.RawDevelop(
                RawDevelopmentSettings(
                    exposureEv = 1.25f,
                    temperatureKelvin = 5_200,
                    tint = -4f,
                    highlights = -0.4f,
                    shadows = 0.3f,
                    contrast = 0.15f,
                ),
            ),
        )
        operations.forEach { assertEquals(it, EditOperationCodec.decode(EditOperationCodec.encode(it))) }
    }

    @Test
    fun rawDevelopmentReplacesTheSingleRawOperationAndRemainsUndoable() {
        val first = RawDevelopmentSettings(exposureEv = 1f)
        val second = RawDevelopmentSettings(exposureEv = 2f)
        val history = EditHistory.initial(EditRecipe.forSource(source, 7))
            .applyRawDevelopment(first)
            .applyRawDevelopment(second)

        assertEquals(1, history.present.operations.filterIsInstance<EditOperation.RawDevelop>().size)
        assertEquals(second, history.present.operations.filterIsInstance<EditOperation.RawDevelop>().single().settings)
        assertEquals(first, history.undo().present.operations.filterIsInstance<EditOperation.RawDevelop>().single().settings)
    }

    @Test
    fun recipeIdIncludesStableMediaStoreIdentityAndGeneration() {
        assertEquals("external_primary:42:7", EditRecipeIds.forSource(source, 7))
        assertTrue(EditRecipe.forSource(source, 7).isIdentity)
    }
}
