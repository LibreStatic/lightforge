package com.ugallery.core.editing.video

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoEditRecipeCodecTest {
    @Test
    fun roundTripsPersistedColorRecipe() {
        val grade = VideoColorGrade(
            inputProfile = LogInputProfile.SonySLog3,
            profileWasAutoDetected = true,
            exposureEv = 1.25f,
            temperature = 0.2f,
            tint = -0.1f,
            contrast = 0.3f,
            pivot = 0.45f,
            saturation = 0.15f,
            logWheels = LogWheels(
                shadows = LogWheel(red = 0.1f, level = -0.2f),
                midtones = LogWheel(green = 0.15f),
                highlights = LogWheel(blue = -0.1f, level = 0.25f),
            ),
            hueBands = HueBand.entries.map { HueBandAdjustment(it, 12f, 0.1f, -0.05f) },
            lut = LutReference(customId = 42L, intensity = 0.7f),
        )
        val recipe = VideoEditRecipe(
            startMillis = 500L,
            endMillis = 9_500L,
            speed = 1.5f,
            originalAudioVolume = 0.75f,
            musicVolume = 0.4f,
            colorGrade = grade,
            outputQuality = VideoOutputQuality.HevcMain10,
        )

        assertEquals(recipe, VideoEditRecipeCodec.decode(VideoEditRecipeCodec.encode(recipe)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownRecipeVersion() {
        VideoEditRecipeCodec.decode("version=99")
    }
}
