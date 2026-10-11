package com.librestatic.lightforge.core.model

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

    private val skewed = EditOperation.Perspective(
        listOf(
            NormalizedPoint(0.1f, 0.05f), NormalizedPoint(0.9f, 0.15f),
            NormalizedPoint(0.95f, 0.9f), NormalizedPoint(0.05f, 0.8f),
        ),
    )
    private val mix = EditOperation.ColorMix(
        ColorMixHue.entries.map { hue ->
            if (hue == ColorMixHue.Green) ColorMixBand(0.5f, -0.25f, 0.1f) else ColorMixBand()
        },
    )
    private val look = EditOperation.Look("builtin:warm_film", 0.75f)
    private val customLook = EditOperation.Look("custom:123e4567-e89b-12d3-a456-426614174000", 1f)
    private fun history() = EditHistory.initial(EditRecipe.forSource(source, 7))

    @Test
    fun newOperationsRoundTripWithCanonicalTokens() {
        val adjust = EditOperation.Adjust(
            mapOf(AdjustKey.Warmth to 0.25f, AdjustKey.Brightness to -0.5f, AdjustKey.Vignette to 0f),
        )
        assertEquals(mapOf(AdjustKey.Brightness to -0.5f, AdjustKey.Warmth to 0.25f), adjust.values)
        assertEquals(0.25f, adjust[AdjustKey.Warmth])
        assertEquals(0f, adjust[AdjustKey.Tint])
        assertEquals("adjust,1,brightness=-0.5;warmth=0.25", EditOperationCodec.encode(adjust))
        assertEquals("colormix,1,green=0.5:-0.25:0.1", EditOperationCodec.encode(mix))
        assertEquals("look,1,builtin:warm_film,0.75", EditOperationCodec.encode(look))
        assertEquals(
            "perspective,1,0.1,0.05,0.9,0.15,0.95,0.9,0.05,0.8",
            EditOperationCodec.encode(skewed),
        )
        listOf(
            adjust, EditOperation.Adjust(emptyMap()), mix, EditOperation.ColorMix.Neutral, look, customLook, skewed,
        ).forEach { assertEquals(it, EditOperationCodec.decode(EditOperationCodec.encode(it))) }
        assertEquals("adjust,1,", EditOperationCodec.encode(EditOperation.Adjust(emptyMap())))
        assertTrue(customLook.isCustom)
        assertTrue(!look.isCustom)
        assertEquals(
            EditOperation.Adjust(mapOf(AdjustKey.Tint to 1f, AdjustKey.Contrast to 1f)),
            EditOperation.Adjust(mapOf(AdjustKey.Contrast to 1f, AdjustKey.Tint to 1f)),
        )
    }

    @Test
    fun legacyToneAndFilterTokensStillDecode() {
        assertEquals(EditOperation.Tone(0.1f, 1.2f, 0.8f), EditOperationCodec.decode("tone,0.1,1.2,0.8"))
        assertEquals(EditOperation.Filter("vivid"), EditOperationCodec.decode("filter,vivid"))
        assertEquals("flip,v", EditOperationCodec.encode(EditOperation.Flip(false)))
    }

    @Test
    fun malformedOrOutOfRangeNewTokensAreRejected() {
        listOf(
            "adjust,2,brightness=0.5", "adjust,1,brightness=1.5", "adjust,1,nope=0.5",
            "adjust,1,tint=0.1;tint=0.2", "adjust,1,tint", "adjust,1,tint=NaN",
            "colormix,2,red=0:0:0", "colormix,1,red=0:0", "colormix,1,red=2:0:0", "colormix,1,pink=0:0:0",
            "colormix,1,red=0.1:0:0;red=0.2:0:0",
            "look,2,builtin:a,0.5", "look,1,builtin:a", "look,1,builtin:A,0.5", "look,1,builtin:a,1.5",
            "look,1,custom:ABC,1.0", "look,1,builtin:" + "a".repeat(33) + ",1.0",
            "perspective,2,0,0,1,0,1,1,0,1", "perspective,1,0,0,1,0,1,1,0",
            "perspective,1,0,0,1,0,1,1,0,2",
            "perspective,1,0,0,1,1,1,0,0,1", // self-intersecting
            "perspective,1,0,0,0.5,0.5,1,1,0,1", // collinear corner
        ).forEach { encoded ->
            val result = runCatching { EditOperationCodec.decode(encoded) }
            assertTrue("Accepted $encoded", result.isFailure)
        }
    }

    @Test
    fun adjustSharesTheToneSlotAndLookSharesTheFilterSlot() {
        val adjust = EditOperation.Adjust(mapOf(AdjustKey.Contrast to 0.3f))
        val adjust2 = EditOperation.Adjust(mapOf(AdjustKey.Shadows to 0.4f))
        val state = history()
            .apply(EditOperation.Tone(brightness = 0.2f))
            .apply(EditOperation.Rotate(90))
            .apply(EditOperation.Filter("vivid"))
            .apply(adjust)
            .apply(look)
        assertEquals(
            listOf(adjust, EditOperation.Rotate(90), look),
            state.present.operations,
        )
        assertEquals(
            listOf(adjust2, EditOperation.Rotate(90), look),
            state.apply(adjust2).present.operations,
        )
        assertEquals(listOf(EditOperation.Rotate(90)), state
            .apply(EditOperation.Adjust(emptyMap()))
            .apply(EditOperation.Look("builtin:warm_film", 0f)).present.operations)
        assertEquals(state, state.apply(adjust).apply(adjust))
    }

    @Test
    fun colorMixHasItsOwnSlotAndNeutralRemovesIt() {
        val state = history().apply(mix).apply(EditOperation.Tone(brightness = 0.1f))
        assertEquals(listOf(mix, EditOperation.Tone(brightness = 0.1f)), state.present.operations)
        val other = EditOperation.ColorMix(List(8) { ColorMixBand(saturation = 0.2f) })
        assertEquals(other, state.apply(other).present.operations.first())
        assertEquals(
            listOf(EditOperation.Tone(brightness = 0.1f)),
            state.apply(EditOperation.ColorMix.Neutral).present.operations,
        )
    }

    @Test
    fun replaceColorGradeIsOneUndoableAction() {
        val adjust = EditOperation.Adjust(mapOf(AdjustKey.Saturation to 0.5f))
        val before = history()
            .apply(EditOperation.Rotate(90))
            .apply(EditOperation.Tone(brightness = 0.2f))
            .apply(EditOperation.Filter("mono"))
        val graded = before.replaceColorGrade(adjust, mix, look)
        assertEquals(listOf<EditOperation>(EditOperation.Rotate(90), adjust, mix, look), graded.present.operations)
        assertEquals(before.present, graded.undo().present)
        assertEquals(graded.present, graded.undo().redo().present)
        val cleared = graded.replaceColorGrade(EditOperation.Adjust(emptyMap()), null, null)
        assertEquals(listOf<EditOperation>(EditOperation.Rotate(90)), cleared.present.operations)
        assertEquals(cleared, cleared.replaceColorGrade(null, null, null))
    }

    @Test
    fun perspectiveSitsAfterGeometryAndBeforeStraightenAndCrop() {
        val crop = EditOperation.Crop(100, 100, 900, 900)
        val straighten = EditOperation.Straighten(3f)
        val state = history().apply(crop).apply(straighten).apply(skewed)
        assertEquals(listOf(skewed, crop, straighten), state.present.operations)

        val withGeometry = history().apply(skewed).apply(EditOperation.Rotate(90)).apply(crop)
        assertEquals(EditOperation.Rotate(90), withGeometry.present.operations[0])
        assertTrue(withGeometry.present.operations[1] is EditOperation.Perspective)
        assertEquals(crop.afterGeometry(EditOperation.Rotate(90)), withGeometry.present.operations[2])

        val replaced = state.apply(EditOperation.Perspective(EditOperation.Perspective.Identity))
        assertEquals(listOf(crop, straighten), replaced.present.operations)
        val legacy = EditHistory.initial(
            EditRecipe.forSource(source, 7).copy(
                operations = listOf(crop, EditOperation.Rotate(90)),
            ),
        ).apply(skewed)
        assertEquals(listOf(crop, EditOperation.Rotate(90), skewed), legacy.present.operations)
    }

    @Test
    fun geometryInsertedBeforePerspectiveKeepsRenderedPixelsIdentical() {
        val geometries = listOf(
            EditOperation.Rotate(90), EditOperation.Rotate(180), EditOperation.Rotate(-90),
            EditOperation.Flip(true), EditOperation.Flip(false),
        )
        val samples = listOf(0.3f to 0.4f, 0.5f to 0.5f, 0.6f to 0.3f, 0.4f to 0.7f)
        geometries.forEach { geometry ->
            val operations = history().apply(skewed).apply(geometry).present.operations
            assertEquals(geometry, operations[0])
            val remapped = operations[1] as EditOperation.Perspective
            val original = homography(skewed.corners)
            val inFrame = homography(remapped.corners)
            samples.forEach { (x, y) ->
                // Appended: perspective on the original frame, then the geometry on its output.
                val appended = original.map(x, y).let { (u, v) ->
                    mapThroughGeometry(NormalizedPoint(u, v), geometry)
                }
                val moved = mapThroughGeometry(NormalizedPoint(x, y), geometry)
                val inserted = inFrame.map(moved.x, moved.y)
                assertEquals("$geometry x", appended.x, inserted.first, 1e-4f)
                assertEquals("$geometry y", appended.y, inserted.second, 1e-4f)
            }
        }
    }

    /** Projective map sending the unit-square corners TL, TR, BR, BL onto [quad] inverted: quad -> unit square. */
    private fun homography(quad: List<NormalizedPoint>): Homography {
        val unit = EditOperation.Perspective.Identity
        // Solve the 8 unknowns of H mapping quad[i] -> unit[i].
        val a = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val x = quad[i].x.toDouble(); val y = quad[i].y.toDouble()
            val u = unit[i].x.toDouble(); val v = unit[i].y.toDouble()
            a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
            a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
        }
        for (col in 0 until 8) {
            val pivot = (col until 8).maxByOrNull { kotlin.math.abs(a[it][col]) }!!
            val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
            for (row in 0 until 8) if (row != col) {
                val factor = a[row][col] / a[col][col]
                for (k in col..8) a[row][k] -= factor * a[col][k]
            }
        }
        return Homography(DoubleArray(8) { a[it][8] / a[it][it] })
    }

    private class Homography(private val h: DoubleArray) {
        fun map(x: Float, y: Float): Pair<Float, Float> {
            val w = h[6] * x + h[7] * y + 1.0
            return Pair(
                ((h[0] * x + h[1] * y + h[2]) / w).toFloat(),
                ((h[3] * x + h[4] * y + h[5]) / w).toFloat(),
            )
        }
    }
}
