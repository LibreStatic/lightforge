package com.ugallery.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
            EditOperation.Straighten(2.5f),
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
    fun adjustmentSlotsReplacePreviousValuesAndOriginalRemovesFilter() {
        val history = EditHistory.initial(EditRecipe.forSource(source, 7))
            .apply(EditOperation.Filter("natural"))
            .apply(EditOperation.Filter("vivid"))
            .apply(EditOperation.Tone(brightness = 0.2f))
            .apply(EditOperation.Tone(contrast = 1.4f))
            .apply(EditOperation.Crop(10, 20, 900, 950))
            .apply(EditOperation.Crop(100, 100, 800, 800))
            .apply(EditOperation.Straighten(3f))
            .apply(EditOperation.Straighten(0f))
            .apply(EditOperation.Filter("none"))

        assertEquals(
            listOf(
                EditOperation.Tone(contrast = 1.4f),
                EditOperation.Crop(100, 100, 800, 800),
            ),
            history.present.operations,
        )
    }

    @Test
    fun fullFrameCropIsNotRecordedAndClearsAnEarlierCrop() {
        val initial = EditHistory.initial(EditRecipe.forSource(source, 7))

        assertEquals(initial, initial.apply(EditOperation.Crop(0, 0, 1_000, 1_000)))

        val cropped = initial.apply(EditOperation.Crop(100, 100, 800, 800))
        val reset = cropped.apply(EditOperation.Crop(0, 0, 1_000, 1_000))
        assertEquals(emptyList<EditOperation>(), reset.present.operations)
        assertTrue(reset.present.isIdentity)
    }

    @Test
    fun rotateAndFlipAfterACropKeepTheCropInTheRotatedFrame() {
        val history = EditHistory.initial(EditRecipe.forSource(source, 7))
            .apply(EditOperation.Crop(100, 200, 600, 900))
            .apply(EditOperation.Straighten(5f))
            .apply(EditOperation.Rotate(90))
            .apply(EditOperation.Flip(horizontal = true))

        assertEquals(
            listOf(
                EditOperation.Rotate(90),
                EditOperation.Flip(horizontal = true),
                // Rotate 90: (100,200,600,900) -> (100,100,800,600); flip: -> (200,100,900,600).
                EditOperation.Crop(200, 100, 900, 600),
                EditOperation.Straighten(-5f),
            ),
            history.present.operations,
        )

        val recropped = history.apply(EditOperation.Crop(0, 0, 500, 500))
        assertEquals(EditOperation.Crop(0, 0, 500, 500), recropped.present.operations[2])
        assertEquals(4, recropped.present.operations.size)
    }

    @Test
    fun cropRotationsCompose() {
        val crop = EditOperation.Crop(100, 200, 600, 900)
        assertEquals(EditOperation.Crop(400, 100, 900, 800), crop.afterGeometry(EditOperation.Rotate(180)))
        assertEquals(crop.afterGeometry(EditOperation.Rotate(270)), crop.afterGeometry(EditOperation.Rotate(-90)))
        assertEquals(crop, crop.afterGeometry(EditOperation.Rotate(360)))
        assertEquals(EditOperation.Crop(100, 100, 600, 800), crop.afterGeometry(EditOperation.Flip(horizontal = false)))
    }

    @Test
    fun recropMovesACropFromAnOlderRecipeBehindItsGeometry() {
        val legacy = EditRecipe.forSource(source, 7).copy(
            operations = listOf(EditOperation.Crop(100, 100, 900, 900), EditOperation.Rotate(90), EditOperation.Tone(0.1f)),
        )
        val history = EditHistory.initial(legacy).apply(EditOperation.Crop(0, 0, 500, 500))

        assertEquals(
            listOf(EditOperation.Rotate(90), EditOperation.Crop(0, 0, 500, 500), EditOperation.Tone(0.1f)),
            history.present.operations,
        )
    }

    @Test
    fun applyingAnAlreadySelectedValueDoesNotCreateUndoHistory() {
        val history = EditHistory.initial(EditRecipe.forSource(source, 7))
            .apply(EditOperation.Filter("natural"))

        assertEquals(history, history.apply(EditOperation.Filter("natural")))
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
    fun automaticColorSuggestionReplacesToneAndFilterAsOneUndoStep() {
        val beforeAutomatic = EditHistory.initial(EditRecipe.forSource(source, 7))
            .apply(EditOperation.Rotate(90))
            .apply(EditOperation.Filter("vivid"))
            .apply(EditOperation.Tone(brightness = 0.2f))
        val suggestion = EditOperation.Tone(brightness = -0.05f, contrast = 1.15f, saturation = 1.08f)

        val automatic = beforeAutomatic.replaceColorOperations(suggestion)

        assertEquals(listOf(EditOperation.Rotate(90), suggestion), automatic.present.operations)
        assertEquals(beforeAutomatic.present, automatic.undo().present)
        assertEquals(
            listOf(EditOperation.Rotate(90)),
            automatic.replaceColorOperations(null).present.operations,
        )
    }

    @Test
    fun recipeIdIncludesStableMediaStoreIdentityAndGeneration() {
        assertEquals("external_primary:42:7", EditRecipeIds.forSource(source, 7))
        assertTrue(EditRecipe.forSource(source, 7).isIdentity)
    }

    @Test
    fun externalRecipesHaveNoMediaStoreIdentityAndRemainEditable() {
        val recipe = EditRecipe.ephemeral("grant-42").append(EditOperation.Rotate(90))

        assertNull(recipe.source)
        assertEquals("external:grant-42", recipe.recipeId)
        assertEquals(listOf(EditOperation.Rotate(90)), recipe.operations)
    }
}
