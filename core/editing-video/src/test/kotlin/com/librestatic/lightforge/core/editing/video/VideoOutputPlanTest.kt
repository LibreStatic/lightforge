package com.librestatic.lightforge.core.editing.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoOutputPlanTest {
    /** The HikVision CCTV clip from the bug report: a 16:9 scene squeezed into 960x1088. */
    private val hikVision = VideoSourceInfo(
        width = 960,
        height = 1088,
        frameRate = 15f,
        nominalFrameRate = 25f,
        videoBitrate = 979_000,
        audioBitrate = 15_500,
        videoMimeType = VideoOutputPlan.MimeHevc,
        audioMimeType = VideoOutputPlan.MimeAac,
        durationMs = 73_000,
        hasAudio = true,
    )

    private val all = VideoEncoderCapabilities.Unrestricted

    private fun plan(
        output: VideoOutputSettings,
        source: VideoSourceInfo = hikVision,
        capabilities: VideoEncoderCapabilities = all,
        recipe: VideoEditRecipe = VideoEditRecipe(),
    ) = VideoOutputPlan.resolve(source, recipe.copy(output = output), capabilities)

    private fun forced(mode: VideoAspectMode, width: Int = 16, height: Int = 9) =
        VideoOutputSettings(aspect = VideoAspectOverride.Forced(width, height, mode))

    @Test
    fun stretchDesqueezesHikVisionTo1080p() {
        val plan = plan(forced(VideoAspectMode.Stretch))

        // 1088 is 1080 plus macroblock padding: 1934x1088 snaps to the standard 1920x1080.
        assertEquals(1920 to 1080, plan.width to plan.height)
        assertEquals(listOf(VideoFrameResize(1920, 1080, VideoAspectMode.Stretch)), plan.resizes)
        assertEquals(VideoOutputCodec.H264, plan.codec)
        assertFalse(plan.remuxVideo)
    }

    @Test
    fun cropFillsTheRatioAndCutsTheOverflow() {
        val plan = plan(forced(VideoAspectMode.Crop))

        assertEquals(960 to 540, plan.width to plan.height)
        assertEquals(VideoAspectMode.Crop, plan.resizes.single().mode)
    }

    @Test
    fun padFitsTheFrameWithBars() {
        val plan = plan(forced(VideoAspectMode.Pad))

        assertEquals(1934 to 1088, plan.width to plan.height)
        assertEquals(VideoAspectMode.Pad, plan.resizes.single().mode)
    }

    @Test
    fun padRoundsToTheEncoderAlignment() {
        val aligned = all.copy(h264 = VideoEncoderSupport(widthAlignment = 16, heightAlignment = 16))
        val plan = plan(forced(VideoAspectMode.Pad), capabilities = aligned)

        assertEquals(1936 to 1088, plan.width to plan.height)
    }

    @Test
    fun shortSideScalesAfterTheForcedAspect() {
        val plan = plan(forced(VideoAspectMode.Stretch).copy(resolution = VideoOutputResolution.ShortSide(720)))

        assertEquals(1280 to 720, plan.width to plan.height)
        assertEquals(listOf(VideoFrameResize(1280, 720, VideoAspectMode.Stretch)), plan.resizes)
    }

    @Test
    fun shortSideAloneKeepsTheSourceRatio() {
        val plan = plan(VideoOutputSettings(resolution = VideoOutputResolution.ShortSide(720)))

        assertEquals(720 to 816, plan.width to plan.height)
    }

    @Test
    fun shortSideNeverUpscales() {
        val plan = plan(VideoOutputSettings(resolution = VideoOutputResolution.ShortSide(1080)))

        assertEquals(960 to 1088, plan.width to plan.height)
        assertTrue(plan.resizes.isEmpty())
        assertTrue(VideoOutputAdjustment.ShortSideAboveSource in plan.adjustments)
    }

    @Test
    fun customSizeLetterboxesTheContent() {
        val plan = plan(
            forced(VideoAspectMode.Stretch).copy(resolution = VideoOutputResolution.Custom(1280, 960)),
        )

        assertEquals(1280 to 960, plan.width to plan.height)
        assertEquals(
            listOf(
                VideoFrameResize(1920, 1080, VideoAspectMode.Stretch),
                VideoFrameResize(1280, 960, VideoAspectMode.Pad),
            ),
            plan.resizes,
        )
    }

    @Test
    fun geometryCropAndRotationComeBeforeTheAspect() {
        val recipe = VideoEditRecipe(geometry = VideoGeometry(left = 0f, right = 0.5f, rotationDegrees = 90f))
        val plan = plan(VideoOutputSettings(), recipe = recipe)

        assertEquals(1088 to 480, plan.width to plan.height)
        assertTrue(plan.resizes.isEmpty())
    }

    @Test
    fun rotatedSourcesUseTheDisplayedSize() {
        val portrait = hikVision.copy(width = 1920, height = 1080, rotationDegrees = 90)
        val plan = plan(VideoOutputSettings(resolution = VideoOutputResolution.ShortSide(720)), source = portrait)

        assertEquals(720 to 1280, plan.width to plan.height)
    }

    @Test
    fun frameRateCapDropsFramesBelowTheRealRate() {
        val plan = plan(VideoOutputSettings(frameRate = VideoOutputFrameRate.Max(10)))

        assertEquals(10, plan.frameRateCap)
        assertEquals(10f, plan.frameRate)
    }

    @Test
    fun frameRateCapAboveTheRealRateIsANoOp() {
        // The container claims 25 fps but delivers 15: a 25 cap must not drop anything.
        val plan = plan(VideoOutputSettings(frameRate = VideoOutputFrameRate.Max(25)))

        assertNull(plan.frameRateCap)
        assertEquals(15f, plan.frameRate)
        assertTrue(VideoOutputAdjustment.FrameRateCapAboveSource in plan.adjustments)
    }

    @Test
    fun missingAv1EncoderFallsBackToHevc() {
        val plan = plan(
            VideoOutputSettings(codec = VideoOutputCodec.Av1, aspect = forced(VideoAspectMode.Stretch).aspect),
            capabilities = all.copy(av1 = null),
        )

        assertEquals(VideoOutputCodec.Hevc, plan.codec)
        assertEquals(VideoOutputPlan.MimeHevc, plan.videoMimeType)
        assertEquals(listOf(VideoOutputAdjustment.CodecUnavailableFellBackToHevc), plan.adjustments)
    }

    @Test
    fun missingHevcEncoderFallsBackToH264() {
        val plan = plan(
            VideoOutputSettings(codec = VideoOutputCodec.Hevc, aspect = forced(VideoAspectMode.Stretch).aspect),
            capabilities = all.copy(hevc = null),
        )

        assertEquals(VideoOutputCodec.H264, plan.codec)
        assertTrue(VideoOutputAdjustment.CodecUnavailableFellBackToH264 in plan.adjustments)
    }

    @Test
    fun availableAv1IsUsed() {
        val plan = plan(VideoOutputSettings(codec = VideoOutputCodec.Av1, aspect = forced(VideoAspectMode.Crop).aspect))

        assertEquals(VideoOutputCodec.Av1, plan.codec)
        assertEquals(VideoOutputPlan.MimeAv1, plan.videoMimeType)
    }

    @Test
    fun autoKeepsTheLegacyCodecRule() {
        val stretch = forced(VideoAspectMode.Stretch)
        assertEquals(VideoOutputCodec.H264, plan(stretch).codec)
        assertEquals(
            VideoOutputCodec.Hevc,
            plan(stretch, recipe = VideoEditRecipe(outputQuality = VideoOutputQuality.HevcMain10)).codec,
        )
        assertTrue(plan(stretch, recipe = VideoEditRecipe(outputQuality = VideoOutputQuality.HevcMain10)).hevcMain10)
        assertEquals(
            VideoOutputCodec.Hevc,
            plan(stretch, recipe = VideoEditRecipe(dynamicRange = VideoDynamicRange.HdrHlg)).codec,
        )
    }

    @Test
    fun hdrOutputReplacesAnSdrOnlyCodecChoiceWithHevc() {
        val plan = plan(
            VideoOutputSettings(codec = VideoOutputCodec.H264),
            recipe = VideoEditRecipe(dynamicRange = VideoDynamicRange.Hdr10Pq),
        )

        assertEquals(VideoOutputCodec.Hevc, plan.codec)
        assertTrue(VideoOutputAdjustment.HdrRequiresHevc in plan.adjustments)
        assertFalse(plan.hevcMain10)
    }

    @Test
    fun explicitH264ToneMapsAnHdrSource() {
        val hdrSource = hikVision.copy(isHdr = true)
        assertTrue(plan(VideoOutputSettings(codec = VideoOutputCodec.H264), source = hdrSource).toneMapToSdr)
        assertFalse(plan(forced(VideoAspectMode.Crop), source = hdrSource).toneMapToSdr)
    }

    @Test
    fun removeAudioDropsTheTrackAndCopiesTheVideo() {
        val plan = plan(VideoOutputSettings(audio = VideoOutputAudio.Remove))

        assertEquals(VideoAudioPlan.Removed, plan.audio)
        assertTrue(plan.remuxVideo)
        assertTrue(plan.remuxOnly)
        assertEquals(VideoOutputCodec.Hevc, plan.codec)
        assertEquals(VideoOutputPlan.MimeHevc, plan.videoMimeType)
        assertEquals(979_000, plan.videoBitrate)
    }

    @Test
    fun aacBitrateReencodesOnlyTheAudio() {
        val plan = plan(VideoOutputSettings(audio = VideoOutputAudio.Aac(64_000)))

        assertEquals(VideoAudioPlan.Encode(64_000, requested = true), plan.audio)
        assertTrue(plan.remuxVideo)
        assertFalse(plan.remuxAudio)
        assertFalse(plan.remuxOnly)
    }

    @Test
    fun trimOnlyIsALosslessCopy() {
        val plan = plan(VideoOutputSettings(), recipe = VideoEditRecipe(startMillis = 1_000, endMillis = 5_000))

        assertTrue(plan.remuxOnly)
        assertEquals(VideoAudioPlan.Copy, plan.audio)
        assertEquals(960 to 1088, plan.width to plan.height)
    }

    @Test
    fun defaultRecipeAlwaysReencodes() {
        // The share sanitizer exports a default recipe and relies on a full re-encode.
        val plan = plan(VideoOutputSettings())

        assertFalse(plan.remuxVideo)
        assertEquals(VideoOutputCodec.H264, plan.codec)
        assertEquals(VideoAudioPlan.Encode(VideoOutputPlan.DefaultAudioBitrate, requested = false), plan.audio)
    }

    @Test
    fun anyRealEditPreventsTheCopy() {
        val trim = VideoEditRecipe(startMillis = 1_000)
        assertFalse(plan(VideoOutputSettings(), recipe = trim.copy(speed = 2f)).remuxVideo)
        assertFalse(plan(VideoOutputSettings(), recipe = trim.copy(geometry = VideoGeometry(rotationDegrees = 90f))).remuxVideo)
        assertFalse(plan(VideoOutputSettings(codec = VideoOutputCodec.H264), recipe = trim).remuxVideo)
        assertFalse(
            plan(VideoOutputSettings(quality = VideoOutputBitrate.Preset(VideoQualityPreset.Low)), recipe = trim).remuxVideo,
        )
        assertFalse(plan(VideoOutputSettings(frameRate = VideoOutputFrameRate.Max(10)), recipe = trim).remuxVideo)
        assertFalse(plan(VideoOutputSettings(), recipe = trim, source = hikVision.copy(videoMimeType = "video/x-vnd.on2.vp9")).remuxVideo)
        assertFalse(plan(VideoOutputSettings(), recipe = trim, source = hikVision.copy(pixelWidthHeightRatio = 1.5f)).remuxVideo)
        // A same-codec choice and no-op caps keep the copy.
        assertTrue(
            plan(
                VideoOutputSettings(
                    codec = VideoOutputCodec.Hevc,
                    frameRate = VideoOutputFrameRate.Max(30),
                    resolution = VideoOutputResolution.ShortSide(1080),
                ),
                recipe = trim,
            ).remuxVideo,
        )
    }

    @Test
    fun volumeChangeCopiesVideoButEncodesAudio() {
        val plan = plan(VideoOutputSettings(), recipe = VideoEditRecipe(startMillis = 500, originalAudioVolume = 0.5f))

        assertTrue(plan.remuxVideo)
        assertFalse(plan.remuxAudio)
        assertFalse(plan.remuxOnly)
    }

    @Test
    fun originalQualityFollowsTheSourceBitrate() {
        val plan = plan(forced(VideoAspectMode.Stretch))

        // 979 kbps HEVC at 960x1088 -> about 2x the pixels in H.264: ~3 Mbps.
        assertTrue(plan.videoBitrate in 2_500_000..3_500_000)
        val sameCodec = plan(forced(VideoAspectMode.Stretch).copy(codec = VideoOutputCodec.Hevc))
        assertTrue(sameCodec.videoBitrate in 1_800_000..2_200_000)
    }

    @Test
    fun presetsAreOrderedAndNeverExceedTheSource() {
        fun bitrate(preset: VideoQualityPreset) =
            plan(forced(VideoAspectMode.Stretch).copy(quality = VideoOutputBitrate.Preset(preset))).videoBitrate
        val original = bitrate(VideoQualityPreset.Original)
        val high = bitrate(VideoQualityPreset.High)
        val medium = bitrate(VideoQualityPreset.Medium)
        val low = bitrate(VideoQualityPreset.Low)

        assertTrue(high in medium..((original * 1.25).toInt()))
        assertTrue(medium > low)
        // Floor: 0.015 bits per pixel per frame.
        assertTrue(low >= (1920 * 1080 * 15 * 0.015).toInt())
    }

    @Test
    fun presetsUseBitsPerPixelWithoutASourceBitrate() {
        val unknown = hikVision.copy(videoBitrate = null, width = 1920, height = 1080, frameRate = 30f)
        val high = plan(
            VideoOutputSettings(codec = VideoOutputCodec.H264, quality = VideoOutputBitrate.Preset(VideoQualityPreset.High)),
            source = unknown,
        )

        assertEquals((1920 * 1080 * 30 * 0.10).toInt().toDouble(), high.videoBitrate.toDouble(), 1.0)
    }

    @Test
    fun targetBitrateIsExactWithinEncoderLimits() {
        val stretch = forced(VideoAspectMode.Stretch)
        assertEquals(5_000_000, plan(stretch.copy(quality = VideoOutputBitrate.Target(5_000_000))).videoBitrate)
        val capped = all.copy(h264 = VideoEncoderSupport(maxBitrate = 4_000_000))
        assertEquals(
            4_000_000,
            plan(stretch.copy(quality = VideoOutputBitrate.Target(5_000_000)), capabilities = capped).videoBitrate,
        )
    }

    @Test
    fun oversizedOutputIsClampedToTheEncoder() {
        val small = all.copy(h264 = VideoEncoderSupport(maxWidth = 1280, maxHeight = 720))
        val plan = plan(forced(VideoAspectMode.Stretch), capabilities = small)

        assertEquals(1280 to 720, plan.width to plan.height)
        assertTrue(VideoOutputAdjustment.ResolutionClampedToEncoder in plan.adjustments)
    }

    @Test
    fun estimatesTheOutputSize() {
        val copy = plan(VideoOutputSettings(), recipe = VideoEditRecipe(startMillis = 1))
        val bytes = copy.estimatedSizeBytes(73_000)
        // The real file is 8.7 MB.
        assertTrue(bytes in 8_500_000L..9_500_000L)

        val target = plan(
            forced(VideoAspectMode.Stretch).copy(
                quality = VideoOutputBitrate.Target(2_000_000),
                audio = VideoOutputAudio.Remove,
            ),
        )
        assertEquals(2_537_500.0, target.estimatedSizeBytes(10_000).toDouble(), 1.0)
        assertEquals(0L, target.estimatedSizeBytes(0))
    }
}
