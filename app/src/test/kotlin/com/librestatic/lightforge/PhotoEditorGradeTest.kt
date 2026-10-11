package com.librestatic.lightforge

import com.librestatic.lightforge.core.model.AdjustKey
import com.librestatic.lightforge.core.model.EditOperation
import com.librestatic.lightforge.core.model.EditRecipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoEditorGradeTest {
    private fun recipe(vararg operations: EditOperation) =
        EditRecipe.ephemeral("test").copy(operations = operations.toList())

    @Test
    fun toneSeedsAdjustFromBrightnessContrastAndSaturation() {
        val adjust = legacyToneToAdjust(EditOperation.Tone(brightness = 0.2f, contrast = 1.3f, saturation = 0.6f))
        assertEquals(0.2f, adjust[AdjustKey.Brightness], 1e-6f)
        assertEquals(0.3f, adjust[AdjustKey.Contrast], 1e-6f)
        assertEquals(-0.4f, adjust[AdjustKey.Saturation], 1e-6f)
    }

    @Test
    fun toneSeedIsClampedToTheAdjustRange() {
        val adjust = legacyToneToAdjust(EditOperation.Tone(brightness = 1f, contrast = 3.5f, saturation = 4f))
        assertEquals(1f, adjust[AdjustKey.Brightness], 0f)
        assertEquals(1f, adjust[AdjustKey.Contrast], 0f)
        assertEquals(1f, adjust[AdjustKey.Saturation], 0f)
        assertTrue(legacyToneToAdjust(EditOperation.Tone()).isNeutral)
    }

    @Test
    fun legacyFiltersShowAsBuiltinLooks() {
        for (name in listOf("natural", "vivid", "mono")) {
            assertEquals(EditOperation.Look("builtin:$name", 1f), legacyFilterToLook(EditOperation.Filter(name)))
        }
        assertNull(legacyFilterToLook(EditOperation.Filter("none")))
    }

    @Test
    fun contentDerivesAdjustAndLookFromLegacyAndNewOperations() {
        val legacy = recipe(EditOperation.Tone(contrast = 1.5f), EditOperation.Filter("vivid"))
        assertEquals(0.5f, photoAdjustFor(legacy)[AdjustKey.Contrast], 1e-6f)
        assertEquals(EditOperation.Look("builtin:vivid", 1f), photoLookFor(legacy))
        val modern = recipe(
            EditOperation.Adjust(mapOf(AdjustKey.Warmth to 0.25f)),
            EditOperation.Look("builtin:film", 0.4f),
        )
        assertEquals(0.25f, photoAdjustFor(modern)[AdjustKey.Warmth], 0f)
        assertEquals(EditOperation.Look("builtin:film", 0.4f), photoLookFor(modern))
        assertTrue(photoColorMixFor(modern).isNeutral)
    }

    @Test
    fun untouchedLegacyToneAndFilterKeepRenderingAsLegacy() {
        val tone = EditOperation.Tone(brightness = 0.1f)
        val filter = EditOperation.Filter("natural")
        val legacy = recipe(tone, filter)
        val untouched = liveGradeOperations(
            legacy, photoAdjustFor(legacy), photoColorMixFor(legacy), photoLookFor(legacy),
        )
        assertEquals(listOf<EditOperation>(tone, filter), untouched)
    }

    @Test
    fun touchedAdjustAndLookReplaceTheLegacyOperations() {
        val legacy = recipe(EditOperation.Tone(brightness = 0.1f), EditOperation.Filter("natural"))
        val adjust = EditOperation.Adjust(mapOf(AdjustKey.Brightness to 0.5f))
        val look = EditOperation.Look("builtin:vivid", 0.5f)
        assertEquals(
            listOf<EditOperation>(adjust, look),
            liveGradeOperations(legacy, adjust, EditOperation.ColorMix.Neutral, look),
        )
        assertTrue(liveGradeOperations(legacy, EditOperation.Adjust(emptyMap()), EditOperation.ColorMix.Neutral, null).isEmpty())
    }

    @Test
    fun colorOperationClassifierCoversEveryColourSlot() {
        assertTrue(isPhotoColorOperation(EditOperation.Look("builtin:warm", 1f)))
        assertTrue(isPhotoColorOperation(EditOperation.ColorMix.Neutral))
        assertTrue(isPhotoColorOperation(EditOperation.Adjust(emptyMap())))
        assertTrue(!isPhotoColorOperation(EditOperation.Flip(horizontal = true)))
        assertTrue(!isPhotoColorOperation(EditOperation.Perspective(EditOperation.Perspective.Identity)))
    }
}
