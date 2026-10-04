package com.librestatic.lightforge.feature.videoeditor

import com.librestatic.lightforge.core.editing.video.SlowMotionSegment
import com.librestatic.lightforge.core.editing.video.VideoAspectMode
import com.librestatic.lightforge.core.editing.video.VideoAspectOverride
import com.librestatic.lightforge.core.editing.video.VideoEncoderCapabilities
import com.librestatic.lightforge.core.editing.video.VideoEncoderSupport
import com.librestatic.lightforge.core.editing.video.VideoOutputAdjustment
import com.librestatic.lightforge.core.editing.video.VideoOutputCodec
import com.librestatic.lightforge.core.editing.video.VideoOutputResolution
import com.librestatic.lightforge.core.editing.video.VideoOutputSettings
import com.librestatic.lightforge.core.editing.video.VideoSourceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The UI side of the output plan: the state-to-recipe bridge and the preview frame. */
class VideoOutputPreviewFrameTest {
    private val cctv = VideoSourceInfo(
        width = 960, height = 1088, frameRate = 15f, videoBitrate = 979_000, audioBitrate = 15_500,
        videoMimeType = "video/hevc", audioMimeType = "audio/mp4a-latm", durationMs = 73_000, hasAudio = true,
    )
    private val plain = VideoEditorContentState(durationMillis = 73_000, trimEndMillis = 73_000, outputSource = cctv)
    private val stretch169 = VideoAspectOverride.Forced(16, 9, VideoAspectMode.Stretch)

    @Test
    fun fullLengthTrimIsNotAnEditButAShorterOneIs() {
        assertNull(plain.outputRecipe()?.endMillis)
        assertFalse(requireNotNull(plain.outputRecipe()).hasChanges)
        assertEquals(60_000L, plain.copy(trimEndMillis = 60_000).outputRecipe()?.endMillis)
        // An impossible state yields no recipe (and so no estimate) instead of crashing.
        assertNull(plain.copy(speed = 10f).outputRecipe())
        assertNull(plain.copy(speed = 10f).outputEstimate())
    }

    @Test
    fun estimateIsTheCorePlanForTheState() {
        val estimate = requireNotNull(plain.copy(output = VideoOutputSettings(aspect = stretch169)).outputEstimate())
        assertEquals(1920 to 1080, estimate.plan.width to estimate.plan.height)
        assertEquals(VideoOutputCodec.H264, estimate.plan.codec)
        assertTrue(estimate.sizeBytes > 0)
        // A trim alone is copied losslessly.
        assertTrue(requireNotNull(plain.copy(trimStartMillis = 1_000).outputEstimate()).plan.remuxOnly)
        assertNull(plain.copy(outputSource = null).outputEstimate())
    }

    @Test
    fun estimateUsesTheDeviceEncoders() {
        val noAv1 = VideoEncoderCapabilities(h264 = VideoEncoderSupport(), hevc = VideoEncoderSupport(), av1 = null)
        val state = plain.copy(outputEncoders = noAv1, output = VideoOutputSettings(codec = VideoOutputCodec.Av1))
        val plan = requireNotNull(state.outputEstimate()).plan
        assertEquals(VideoOutputCodec.Hevc, plan.codec)
        assertEquals(listOf(VideoOutputAdjustment.CodecUnavailableFellBackToHevc), plan.adjustments)
    }

    @Test
    fun softwareOnlyEncodersAreFlagged() {
        val softAv1 = VideoEncoderCapabilities(
            h264 = VideoEncoderSupport(),
            hevc = VideoEncoderSupport(),
            av1 = VideoEncoderSupport(hardwareAccelerated = false),
        )
        assertTrue(plain.copy(outputEncoders = softAv1).isSoftwareOnly(VideoOutputCodec.Av1))
        assertFalse(plain.copy(outputEncoders = softAv1).isSoftwareOnly(VideoOutputCodec.Hevc))
        assertFalse(plain.isSoftwareOnly(VideoOutputCodec.Av1))
    }

    @Test
    fun previewBoxesPerMode() {
        // 960×1088 source in a 400×300 container, forced 16:9.
        val sourceAspect = 960f / 1088f
        val stretch = videoPreviewBoxes(400f, 300f, 16f / 9f, 16f / 9f, crop = false)
        assertEquals(400f, stretch.videoWidth, 0.01f)
        assertEquals(225f, stretch.videoHeight, 0.01f)
        val pad = videoPreviewBoxes(400f, 300f, sourceAspect, 16f / 9f, crop = false)
        assertEquals(400f, pad.frameWidth, 0.01f)
        assertEquals(225f, pad.videoHeight, 0.01f)
        assertEquals(225f * sourceAspect, pad.videoWidth, 0.01f)
        val crop = videoPreviewBoxes(400f, 300f, sourceAspect, 16f / 9f, crop = true)
        assertTrue(crop.crop)
        assertEquals(300f, crop.videoHeight, 0.01f)
        assertEquals(crop.videoWidth, crop.frameWidth, 0.01f)
        assertEquals(crop.videoWidth * 9f / 16f, crop.frameHeight, 0.01f)
    }

    @Test
    fun previewFrameFollowsTheResolvedOutput() {
        assertNull(videoOutputPreviewBoxes(plain, null, 400f, 300f))
        val stretch = requireNotNull(videoOutputPreviewBoxes(plain.copy(output = VideoOutputSettings(aspect = stretch169)), null, 400f, 300f))
        assertEquals(400f, stretch.videoWidth, 0.5f)
        assertEquals(225f, stretch.videoHeight, 0.5f)
        // A custom canvas letterboxes the (unchanged) picture.
        val custom = plain.copy(output = VideoOutputSettings(resolution = VideoOutputResolution.Custom(1920, 1080)))
        val boxes = requireNotNull(videoOutputPreviewBoxes(custom, null, 400f, 300f))
        assertEquals(400f, boxes.frameWidth, 0.5f)
        assertEquals(225f, boxes.frameHeight, 0.5f)
        assertEquals(225f * 960f / 1088f, boxes.videoWidth, 0.5f)
        assertFalse(boxes.crop)
        val crop = videoOutputPreviewBoxes(plain.copy(output = VideoOutputSettings(aspect = stretch169.copy(mode = VideoAspectMode.Crop))), null, 400f, 300f)
        assertNotNull(crop)
        assertTrue(crop!!.crop)
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

    @Test
    fun outputLengthAppliesBaseSpeedAndSlowMotionSegments() {
        assertEquals(73_000L, plain.outputLengthMillis())
        assertEquals(36_500L, plain.copy(speed = 2f).outputLengthMillis())
        // 10 s at 0.25x -> 40 s, plus the other 63 s at normal speed.
        val slow = plain.copy(slowMotionSegments = listOf(SlowMotionSegment(startMillis = 10_000, endMillis = 20_000)))
        assertEquals(103_000L, slow.outputLengthMillis())
        // A trim that cuts the clip short is honoured together with the segment.
        assertEquals(70_000L, slow.copy(trimEndMillis = 40_000).outputLengthMillis())
    }
}
