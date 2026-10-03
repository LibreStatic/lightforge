package com.librestatic.lightforge.feature.videoeditor

import com.librestatic.lightforge.core.editing.video.VideoAspectMode
import com.librestatic.lightforge.core.editing.video.VideoAspectOverride
import com.librestatic.lightforge.core.editing.video.VideoColorGrade
import com.librestatic.lightforge.core.editing.video.VideoGeometry
import com.librestatic.lightforge.core.editing.video.VideoOutputAudio
import com.librestatic.lightforge.core.editing.video.VideoOutputBitrate
import com.librestatic.lightforge.core.editing.video.VideoOutputCodec
import com.librestatic.lightforge.core.editing.video.VideoOutputFrameRate
import com.librestatic.lightforge.core.editing.video.VideoOutputResolution
import com.librestatic.lightforge.core.editing.video.VideoOutputSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoOutputPlanTest {
    private val cctv = VideoOutputSource(
        width = 960, height = 1088, videoMimeType = "video/hevc", frameRate = 25f,
        totalBitrate = 995_000, audioBitrate = 15_500, hasAudio = true,
    )
    private val plain = VideoEditorContentState(durationMillis = 73_000, trimEndMillis = 73_000, outputSource = cctv)
    private val stretch169 = VideoAspectOverride.Forced(16, 9, VideoAspectMode.Stretch)

    @Test
    fun stretchAndPadGrowTheShortSideCropKeepsTheLargestBox() {
        val base = VideoPixelSize(960, 1088)
        assertEquals(VideoPixelSize(1934, 1088), aspectAdjustedSize(base, stretch169))
        assertEquals(VideoPixelSize(1934, 1088), aspectAdjustedSize(base, stretch169.copy(mode = VideoAspectMode.Pad)))
        assertEquals(VideoPixelSize(960, 540), aspectAdjustedSize(base, stretch169.copy(mode = VideoAspectMode.Crop)))
        assertEquals(base, aspectAdjustedSize(base, VideoAspectOverride.Original))
    }

    @Test
    fun cctvStretchedTo1080pBecomes1920x1080() {
        val state = plain.copy(output = VideoOutputSettings(aspect = stretch169, resolution = VideoOutputResolution.ShortSide(1080)))
        assertEquals(VideoPixelSize(1920, 1080), state.outputEstimate().size)
    }

    @Test
    fun shortSideNeverUpscalesAndSidesAreEven() {
        assertEquals(VideoPixelSize(960, 1088), resolvedOutputSize(VideoPixelSize(960, 1088), VideoOutputResolution.ShortSide(2160)))
        assertEquals(VideoPixelSize(720, 816), resolvedOutputSize(VideoPixelSize(960, 1088), VideoOutputResolution.ShortSide(720)))
        assertEquals(VideoPixelSize(1278, 720), resolvedOutputSize(VideoPixelSize(1279, 721), VideoOutputResolution.Original))
    }

    @Test
    fun editedSizeFollowsCropAndQuarterTurns() {
        val geometry = VideoGeometry(left = 0f, right = 0.5f, rotationDegrees = 90f)
        assertEquals(VideoPixelSize(1088, 480), editedSourceSize(cctv, geometry))
    }

    @Test
    fun untouchedRecipeIsALosslessCopyOfTheSource() {
        val estimate = plain.outputEstimate()
        assertTrue(estimate.isLosslessCopy)
        assertEquals(VideoOutputCodec.Hevc, estimate.codec)
        assertEquals(VideoPixelSize(960, 1088), estimate.size)
        assertEquals(979_500, estimate.videoBitrate)
    }

    @Test
    fun anythingThatTouchesPixelsOrStreamsNeedsAReencode() {
        assertFalse(plain.copy(output = VideoOutputSettings(aspect = stretch169)).outputEstimate().isLosslessCopy)
        assertFalse(plain.copy(output = VideoOutputSettings(audio = VideoOutputAudio.Remove)).outputEstimate().isLosslessCopy)
        assertFalse(plain.copy(output = VideoOutputSettings(codec = VideoOutputCodec.H264)).outputEstimate().isLosslessCopy)
        assertFalse(plain.copy(colorGrade = VideoColorGrade(exposureEv = 1f)).outputEstimate().isLosslessCopy)
        assertFalse(plain.copy(speed = 2f).outputEstimate().isLosslessCopy)
        // Choosing the codec the source already has, or a rate at/above it, still copies.
        assertTrue(plain.copy(output = VideoOutputSettings(codec = VideoOutputCodec.Hevc)).outputEstimate().isLosslessCopy)
        assertTrue(plain.copy(output = VideoOutputSettings(frameRate = VideoOutputFrameRate.Max(30))).outputEstimate().isLosslessCopy)
    }

    @Test
    fun targetBitrateDrivesTheSizeEstimate() {
        val state = plain.copy(
            output = VideoOutputSettings(
                aspect = stretch169,
                quality = VideoOutputBitrate.Target(4_000_000),
                audio = VideoOutputAudio.Aac(128_000),
            ),
        )
        val estimate = state.outputEstimate()
        assertEquals(VideoOutputCodec.H264, estimate.codec)
        assertEquals(4_000_000, estimate.videoBitrate)
        // (4 Mbps + 128 kbps) × 73 s / 8
        assertEquals(37_668_000L, estimate.sizeBytes)
    }

    @Test
    fun frameRateCapNeverExceedsTheSource() {
        val estimate = plain.copy(output = VideoOutputSettings(frameRate = VideoOutputFrameRate.Max(15))).outputEstimate()
        assertEquals(15f, estimate.frameRate)
        assertEquals(25f, plain.copy(output = VideoOutputSettings(frameRate = VideoOutputFrameRate.Max(60))).outputEstimate().frameRate)
    }

    @Test
    fun unknownSourceGivesNoSizeButStillACodec() {
        val estimate = plain.copy(outputSource = null, output = VideoOutputSettings(aspect = stretch169)).outputEstimate()
        assertNull(estimate.size)
        assertNull(estimate.sizeBytes)
        assertEquals(VideoOutputCodec.H264, estimate.codec)
    }

    @Test
    fun previewBoxesPerMode() {
        // 960×1088 source in a 400×300 container, forced 16:9.
        val sourceAspect = 960f / 1088f
        val stretch = videoPreviewBoxes(400f, 300f, sourceAspect, stretch169)
        assertEquals(400f, stretch.videoWidth, 0.01f)
        assertEquals(225f, stretch.videoHeight, 0.01f)
        val pad = videoPreviewBoxes(400f, 300f, sourceAspect, stretch169.copy(mode = VideoAspectMode.Pad))
        assertEquals(400f, pad.frameWidth, 0.01f)
        assertEquals(225f, pad.videoHeight, 0.01f)
        assertEquals(225f * sourceAspect, pad.videoWidth, 0.01f)
        val crop = videoPreviewBoxes(400f, 300f, sourceAspect, stretch169.copy(mode = VideoAspectMode.Crop))
        assertEquals(300f, crop.videoHeight, 0.01f)
        assertEquals(crop.videoWidth, crop.frameWidth, 0.01f)
        assertEquals(crop.videoWidth * 9f / 16f, crop.frameHeight, 0.01f)
    }

    @Test
    fun formatsAndParsing() {
        assertEquals("0.98", formatMbps(979_500).replace(',', '.'))
        assertEquals("12.5", formatMbps(12_500_000).replace(',', '.'))
        assertEquals("25", formatFrameRate(25f))
        assertEquals(2_500_000, parseTargetMbps("2,5"))
        assertNull(parseTargetMbps("0.01"))
        assertNull(parseTargetMbps("abc"))
        assertEquals("HEVC", videoCodecDisplayName("video/hevc"))
        assertEquals("VP9", videoCodecDisplayName("video/x-vnd.on2.vp9"))
    }
}
