package com.librestatic.lightforge.core.editing.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoColorGradeEffectsTest {
    @Test
    fun logWheelMasksTargetTheirExpectedTonalRanges() {
        val shadows = VideoColorGradeEffects.logWheelWeights(0f)
        val midtones = VideoColorGradeEffects.logWheelWeights(0.5f)
        val highlights = VideoColorGradeEffects.logWheelWeights(1f)

        assertArrayEquals(floatArrayOf(1f, 0f, 0f), shadows, 0.000001f)
        assertTrue(midtones[1] > midtones[0] && midtones[1] > midtones[2])
        assertArrayEquals(floatArrayOf(0f, 0f, 1f), highlights, 0.000001f)
    }

    @Test
    fun shadowAndHighlightWheelsChangeTheCorrespondingPreviewTones() {
        val darkInput = floatArrayOf(0.1f, 0.1f, 0.1f)
        val brightInput = floatArrayOf(0.9f, 0.9f, 0.9f)
        val neutral = VideoColorGrade()
        val shadowGrade = neutral.copy(
            logWheels = LogWheels(shadows = LogWheel(level = 0.5f)),
        )
        val highlightGrade = neutral.copy(
            logWheels = LogWheels(highlights = LogWheel(level = -0.5f)),
        )

        val neutralDark = VideoColorGradeEffects.grade(darkInput, neutral, null)
        val neutralBright = VideoColorGradeEffects.grade(brightInput, neutral, null)
        val shadowDark = VideoColorGradeEffects.grade(darkInput, shadowGrade, null)
        val shadowBright = VideoColorGradeEffects.grade(brightInput, shadowGrade, null)
        val highlightDark = VideoColorGradeEffects.grade(darkInput, highlightGrade, null)
        val highlightBright = VideoColorGradeEffects.grade(brightInput, highlightGrade, null)

        assertTrue(shadowDark[0] - neutralDark[0] > 0.1f)
        assertEquals(neutralBright[0], shadowBright[0], 0.0001f)
        assertEquals(neutralDark[0], highlightDark[0], 0.0001f)
        assertTrue(neutralBright[0] - highlightBright[0] > 0.03f)
    }

    @Test
    fun shadowsSliderLiftsDarkTonesWithoutMovingBrightOnes() {
        val dark = floatArrayOf(0.1f, 0.1f, 0.1f)
        val bright = floatArrayOf(0.9f, 0.9f, 0.9f)
        val neutral = VideoColorGrade()
        val lifted = neutral.copy(shadows = 0.8f)

        assertTrue(VideoColorGradeEffects.grade(dark, lifted, null)[0] - VideoColorGradeEffects.grade(dark, neutral, null)[0] > 0.05f)
        assertEquals(
            VideoColorGradeEffects.grade(bright, neutral, null)[0],
            VideoColorGradeEffects.grade(bright, lifted, null)[0],
            0.0001f,
        )
    }

    @Test
    fun highlightsSliderRecoversBrightTonesWithoutMovingDarkOnes() {
        val dark = floatArrayOf(0.05f, 0.05f, 0.05f)
        val bright = floatArrayOf(0.85f, 0.85f, 0.85f)
        val neutral = VideoColorGrade()
        val recovered = neutral.copy(highlights = -0.8f)

        assertTrue(VideoColorGradeEffects.grade(bright, neutral, null)[0] - VideoColorGradeEffects.grade(bright, recovered, null)[0] > 0.05f)
        assertEquals(
            VideoColorGradeEffects.grade(dark, neutral, null)[0],
            VideoColorGradeEffects.grade(dark, recovered, null)[0],
            0.0001f,
        )
    }

    @Test
    fun tonalRangeGainIsNeutralAtZeroAndMonotonicInTheSlider() {
        assertEquals(1f, VideoColorGradeEffects.tonalRangeGain(0.5f, 0f, 0f), 0f)
        assertTrue(
            VideoColorGradeEffects.tonalRangeGain(0.05f, 0.5f, 0f) >
                VideoColorGradeEffects.tonalRangeGain(0.05f, 0.1f, 0f),
        )
    }

    @Test
    fun vibranceBoostsMutedColorsMoreThanVividOnes() {
        val muted = floatArrayOf(0.55f, 0.5f, 0.45f)
        val vivid = floatArrayOf(0.95f, 0.1f, 0.05f)

        fun chroma(rgb: FloatArray) = rgb.max() - rgb.min()
        val mutedGain = chroma(VideoColorGradeEffects.applyVibrance(muted, 1f)) / chroma(muted)
        val vividGain = chroma(VideoColorGradeEffects.applyVibrance(vivid, 1f)) / chroma(vivid)

        assertTrue("Muted colors gain more chroma than vivid ones", mutedGain > vividGain)
        assertArrayEquals(muted, VideoColorGradeEffects.applyVibrance(muted, 0f), 0.00001f)
    }

    @Test
    fun newSlidersAreVisibleInThePreviewCubeAndIgnoredWhenBypassed() {
        val neutral = VideoColorGradeEffects.buildPreviewCube(VideoColorGrade(), size = 5)
        val graded = VideoColorGrade(shadows = 0.6f, highlights = -0.4f, vibrance = 0.7f)

        assertTrue(neutral[1][1][1] != VideoColorGradeEffects.buildPreviewCube(graded, size = 5)[1][1][1])
        assertTrue(neutral[4][4][4] != VideoColorGradeEffects.buildPreviewCube(graded, size = 5)[4][4][4])
        assertEquals(neutral[1][1][1], VideoColorGradeEffects.buildPreviewCube(graded.copy(bypass = true), size = 5)[1][1][1])
    }

    @Test
    fun neutralNewSlidersKeepTheGradeAnIdentity() {
        assertTrue(!VideoColorGrade().hasChanges)
        assertTrue(VideoColorGrade(vibrance = 0.1f).hasChanges)
    }

    @Test
    fun logWheelChangesArePresentInTheRealtimePreviewCube() {
        val neutral = VideoColorGradeEffects.buildPreviewCube(VideoColorGrade(), size = 5)
        val shadows = VideoColorGradeEffects.buildPreviewCube(
            VideoColorGrade(logWheels = LogWheels(shadows = LogWheel(red = 0.5f))),
            size = 5,
        )
        val highlights = VideoColorGradeEffects.buildPreviewCube(
            VideoColorGrade(logWheels = LogWheels(highlights = LogWheel(blue = -0.5f))),
            size = 5,
        )

        assertTrue(neutral[1][1][1] != shadows[1][1][1])
        assertTrue(neutral[4][4][4] != highlights[4][4][4])
    }

    @Test
    fun hueBandAdjustmentsAreOrderIndependent() {
        val input = floatArrayOf(0.2601f, 0.1732f, 0.1055f)
        val adjustments = listOf(
            HueBandAdjustment(HueBand.Red, hueShiftDegrees = 18f, saturation = 0.2f),
            HueBandAdjustment(HueBand.Orange, hueShiftDegrees = -7f, luminance = 0.1f),
        )

        val canonical = VideoColorGradeEffects.applyHueBands(input, adjustments)
        val reversed = VideoColorGradeEffects.applyHueBands(input, adjustments.reversed())

        assertArrayEquals(canonical, reversed, 0.000001f)
    }

    @Test
    fun hueBandAdjustmentsPreserveSubEightBitPrecision() {
        val adjustment = listOf(HueBandAdjustment(HueBand.Red, hueShiftDegrees = 10f))
        val first = VideoColorGradeEffects.applyHueBands(
            floatArrayOf(0.2595894f, 0.17298416f, 0.10523667f),
            adjustment,
        )
        val second = VideoColorGradeEffects.applyHueBands(
            floatArrayOf(0.26055342f, 0.17298416f, 0.10523667f),
            adjustment,
        )

        assertTrue(first.indices.any { kotlin.math.abs(first[it] - second[it]) > 0.0001f })
    }

    @Test(expected = IllegalArgumentException::class)
    fun gradeRejectsAnIncompleteInMemoryHueBandSet() {
        VideoColorGrade(hueBands = listOf(HueBandAdjustment(HueBand.Red)))
    }

    @Test
    fun allSupportedLogCurvesDecodeReferenceEighteenPercentGrey() {
        val referenceSignals = listOf(
            LogInputProfile.AppleLog to 0.48827246f,
            LogInputProfile.SonySLog2 to 0.33953252f,
            LogInputProfile.SonySLog3 to 0.41055718f,
            LogInputProfile.CanonLog2 to 0.39825469f,
            LogInputProfile.CanonLog3 to 0.34338937f,
            LogInputProfile.PanasonicVLog to 0.42331145f,
            LogInputProfile.DjiDLog to 0.39876456f,
            LogInputProfile.FujifilmFLog to 0.45931846f,
            LogInputProfile.FujifilmFLog2 to 0.39100724f,
            LogInputProfile.NikonNLog to 0.36366777f,
            LogInputProfile.BlackmagicFilmGen5 to 0.38356164f,
            LogInputProfile.ArriLogC3 to 0.39100683f,
            LogInputProfile.ArriLogC4 to 0.27839584f,
            LogInputProfile.RedLog3G10 to 0.33333291f,
        )

        referenceSignals.forEach { (profile, encoded) ->
            assertEquals(
                "$profile did not decode reference middle grey",
                0.18f,
                VideoColorGradeEffects.decodeToLinear(encoded, profile),
                0.0002f,
            )
        }
    }

    @Test
    fun allSupportedLogCurvesDecodeReferenceBlack() {
        val referenceSignals = listOf(
            LogInputProfile.AppleLog to 0.15047645f,
            LogInputProfile.SonySLog2 to 0.08825129f,
            LogInputProfile.SonySLog3 to 95f / 1023f,
            LogInputProfile.CanonLog2 to 0.092864125f,
            LogInputProfile.CanonLog3 to 0.12512219f,
            LogInputProfile.PanasonicVLog to 0.125f,
            LogInputProfile.DjiDLog to 0.0929f,
            LogInputProfile.FujifilmFLog to 0.092864f,
            LogInputProfile.FujifilmFLog2 to 0.092864f,
            LogInputProfile.NikonNLog to 0.12437263f,
            LogInputProfile.BlackmagicFilmGen5 to 0.09246575f,
            LogInputProfile.ArriLogC3 to 0.092809f,
            LogInputProfile.ArriLogC4 to 95f / 1023f,
            LogInputProfile.RedLog3G10 to 0.09155149f,
        )

        referenceSignals.forEach { (profile, encoded) ->
            assertEquals(
                "$profile did not decode reference black",
                0f,
                VideoColorGradeEffects.decodeToLinear(encoded, profile),
                0.00002f,
            )
        }
    }

    @Test
    fun piecewiseLogCurvesAreContinuousAtTheirJoins() {
        data class Join(val profile: LogInputProfile, val encoded: Float, val linear: Float)

        val joins = listOf(
            Join(LogInputProfile.AppleLog, 0.2085553f, 0.01f),
            Join(LogInputProfile.SonySLog2, 0.08825129f, 0f),
            Join(LogInputProfile.SonySLog3, 171.2102946929f / 1023f, 0.01125f),
            Join(LogInputProfile.CanonLog2, 0.092864125f, 0f),
            Join(LogInputProfile.CanonLog3, 0.097465473f, 0f),
            Join(LogInputProfile.CanonLog3, 0.15277891f, 0.0126f),
            Join(LogInputProfile.PanasonicVLog, 0.181f, 0.01f),
            Join(LogInputProfile.DjiDLog, 0.14f, 0.0078f),
            Join(LogInputProfile.FujifilmFLog, 0.100537775f, 0.00089f),
            Join(LogInputProfile.FujifilmFLog2, 0.100686684f, 0.000889f),
            // Nikon's published rounded constants leave a small (~0.0003) join mismatch.
            Join(LogInputProfile.NikonNLog, 452f / 1023f, 0.3286f),
            Join(LogInputProfile.BlackmagicFilmGen5, 0.13388379f, 0.005f),
            Join(LogInputProfile.ArriLogC3, 0.149658f, 0.010591f),
            Join(LogInputProfile.ArriLogC4, 0f, 0f),
            Join(LogInputProfile.RedLog3G10, 0f, 0f),
        )
        val epsilon = 0.000001f

        joins.forEach { join ->
            val below = VideoColorGradeEffects.decodeToLinear(join.encoded - epsilon, join.profile)
            val above = VideoColorGradeEffects.decodeToLinear(join.encoded + epsilon, join.profile)
            assertEquals("${join.profile} lower join", join.linear, below, 0.0005f)
            assertEquals("${join.profile} upper join", join.linear, above, 0.0005f)
            assertEquals("${join.profile} discontinuity", below, above, 0.001f)
        }
    }

    @Test
    fun standardTransferRoundTripsWithoutAColorShift() {
        listOf(0f, 0.018f, 0.18f, 0.5f, 1f).forEach { encoded ->
            val actual = VideoColorGradeEffects.grade(
                floatArrayOf(encoded, encoded, encoded),
                VideoColorGrade(),
                customLut = null,
            )

            assertArrayEquals(floatArrayOf(encoded, encoded, encoded), actual, 0.0003f)
        }
    }

    @Test
    fun appleLogAndSLog3MapEighteenPercentGreyToRec709() {
        val expectedRec709 = floatArrayOf(0.409008f, 0.409008f, 0.409008f)

        val apple = VideoColorGradeEffects.grade(
            floatArrayOf(0.488272f, 0.488272f, 0.488272f),
            VideoColorGrade(inputProfile = LogInputProfile.AppleLog),
            customLut = null,
        )
        val slog3 = VideoColorGradeEffects.grade(
            floatArrayOf(0.410557f, 0.410557f, 0.410557f),
            VideoColorGrade(inputProfile = LogInputProfile.SonySLog3),
            customLut = null,
        )

        assertArrayEquals(expectedRec709, apple, 0.0005f)
        assertArrayEquals(expectedRec709, slog3, 0.0005f)
    }

    @Test
    fun appleLogTransferIsContinuousAtTheLinearLogJoin() {
        val grade = VideoColorGrade(inputProfile = LogInputProfile.AppleLog)
        val immediatelyBelow = VideoColorGradeEffects.grade(
            floatArrayOf(0.2085543f, 0.2085543f, 0.2085543f),
            grade,
            customLut = null,
        )
        val immediatelyAbove = VideoColorGradeEffects.grade(
            floatArrayOf(0.2085563f, 0.2085563f, 0.2085563f),
            grade,
            customLut = null,
        )

        assertArrayEquals(floatArrayOf(0.045f, 0.045f, 0.045f), immediatelyBelow, 0.0001f)
        assertArrayEquals(immediatelyBelow, immediatelyAbove, 0.0001f)
    }

    @Test
    fun slog3TransferIsContinuousAtTheLinearLogJoin() {
        val grade = VideoColorGrade(inputProfile = LogInputProfile.SonySLog3)
        val join = 171.2102946929f / 1023f
        val immediatelyBelow = VideoColorGradeEffects.grade(
            floatArrayOf(join - 0.000001f, join - 0.000001f, join - 0.000001f),
            grade,
            customLut = null,
        )
        val immediatelyAbove = VideoColorGradeEffects.grade(
            floatArrayOf(join + 0.000001f, join + 0.000001f, join + 0.000001f),
            grade,
            customLut = null,
        )

        assertArrayEquals(floatArrayOf(0.050625f, 0.050625f, 0.050625f), immediatelyBelow, 0.0001f)
        assertArrayEquals(immediatelyBelow, immediatelyAbove, 0.0001f)
    }

    @Test
    fun customLutIntensityBlendsInLinearLight() {
        val constantLut = CubeLut(
            title = "Constant",
            size = 2,
            values = FloatArray(2 * 2 * 2 * 3) { channel ->
                when (channel % 3) {
                    0 -> 0.8f
                    1 -> 0.4f
                    else -> 0.2f
                }
            },
        )
        val input = floatArrayOf(0.5f, 0.5f, 0.5f)

        val noLut = VideoColorGradeEffects.grade(
            input,
            VideoColorGrade(lut = LutReference(customId = 1L, intensity = 0f)),
            constantLut,
        )
        val fullLut = VideoColorGradeEffects.grade(
            input,
            VideoColorGrade(lut = LutReference(customId = 1L, intensity = 1f)),
            constantLut,
        )

        assertArrayEquals(input, noLut, 0.0003f)
        assertArrayEquals(floatArrayOf(0.895004f, 0.628654f, 0.433674f), fullLut, 0.0005f)
    }

    @Test
    fun bypassBuildsAnIdentityPreviewCube() {
        val cube = VideoColorGradeEffects.buildPreviewCube(
            grade = VideoColorGrade(exposureEv = 5f, bypass = true),
            customLut = null,
            size = 3,
        )

        assertEquals(0xFF7FFF00.toInt(), cube[1][2][0])
    }

    @Test(expected = IllegalArgumentException::class)
    fun exportEffectRejectsAnUnavailableCustomLut() {
        VideoColorGradeEffects.create(
            grade = VideoColorGrade(lut = LutReference(customId = 42L)),
            customLut = null,
            cubeSize = 2,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun previewRejectsAnUnavailableCustomLut() {
        VideoColorGradeEffects.buildPreviewCube(
            grade = VideoColorGrade(lut = LutReference(customId = 42L)),
            customLut = null,
            size = 2,
        )
    }

    @Test
    fun contrastKeepsTheMidToneAndDoesNotShiftOverallBrightness() {
        val grade = VideoColorGrade(contrast = 0.55f)
        val mid = VideoColorGrade().pivot
        val graded = VideoColorGradeEffects.grade(floatArrayOf(mid, mid, mid), grade, null)
        assertEquals(mid, graded[0], 0.01f)
        val dark = VideoColorGradeEffects.grade(floatArrayOf(0.3f, 0.3f, 0.3f), grade, null)
        val bright = VideoColorGradeEffects.grade(floatArrayOf(0.6f, 0.6f, 0.6f), grade, null)
        assertTrue(dark[0] < 0.3f)
        assertTrue(bright[0] > 0.6f)
    }
}
