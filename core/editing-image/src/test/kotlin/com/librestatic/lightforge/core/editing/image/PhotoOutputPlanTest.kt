package com.librestatic.lightforge.core.editing.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoOutputPlanTest {
    private val all = PhotoOutputCapabilities(heic = true, avif = true)
    private val max = 24_000_000L

    private fun plan(
        settings: PhotoOutputSettings,
        mime: String? = "image/jpeg",
        width: Int = 4000,
        height: Int = 3000,
        capabilities: PhotoOutputCapabilities = all,
    ) = PhotoOutputPlan.resolve(settings, mime, width, height, capabilities, max)

    @Test
    fun defaultsKeepThePreviousSameAsSourceBehaviour() {
        assertEquals(PhotoEncoding.Jpeg, plan(PhotoOutputSettings()).encoding)
        assertEquals(PhotoEncoding.Png, plan(PhotoOutputSettings(), "image/png").encoding)
        assertEquals(PhotoEncoding.WebpLossless, plan(PhotoOutputSettings(), "image/webp").encoding)
        assertEquals(PhotoEncoding.Jpeg, plan(PhotoOutputSettings(), "image/heic").encoding)
        assertEquals(PhotoEncoding.Jpeg, plan(PhotoOutputSettings(), null).encoding)
        val default = plan(PhotoOutputSettings())
        assertEquals(95, default.quality)
        assertEquals(4000, default.width)
        assertEquals(3000, default.height)
        assertTrue(default.copyMetadata)
        assertTrue(default.adjustments.isEmpty())
    }

    @Test
    fun explicitFormatsMapToTheirEncoder() {
        PhotoOutputFormat.entries.filter { it != PhotoOutputFormat.Original }.forEach { format ->
            assertEquals(PhotoEncoding.of(format), plan(PhotoOutputSettings(format = format)).encoding)
        }
        assertEquals("image/avif", plan(PhotoOutputSettings(format = PhotoOutputFormat.Avif)).mimeType)
        assertEquals("gif", plan(PhotoOutputSettings(format = PhotoOutputFormat.Gif)).extension)
    }

    @Test
    fun unavailableEncoderFallsBackToJpeg() {
        val result = plan(PhotoOutputSettings(format = PhotoOutputFormat.Avif), capabilities = PhotoOutputCapabilities.Basic)
        assertEquals(PhotoEncoding.Jpeg, result.encoding)
        assertEquals(listOf(PhotoOutputAdjustment.FormatUnavailable), result.adjustments)
        assertFalse(PhotoOutputCapabilities.Basic.formats.contains(PhotoOutputFormat.Heic))
    }

    @Test
    fun resizeKeepsAspectAndNeverUpscales() {
        val long = plan(PhotoOutputSettings(resize = PhotoOutputResize.LongSide(2048)))
        assertEquals(2048, long.width)
        assertEquals(1536, long.height)
        val portrait = plan(PhotoOutputSettings(resize = PhotoOutputResize.LongSide(1080)), width = 3000, height = 4000)
        assertEquals(810, portrait.width)
        assertEquals(1080, portrait.height)
        val half = plan(PhotoOutputSettings(resize = PhotoOutputResize.Scale(50)), width = 999, height = 501)
        assertEquals(500, half.width)
        assertEquals(251, half.height)
        val upscale = plan(PhotoOutputSettings(resize = PhotoOutputResize.LongSide(8000)))
        assertEquals(4000, upscale.width)
        assertEquals(listOf(PhotoOutputAdjustment.NoUpscale), upscale.adjustments)
        assertEquals(1 to 1, PhotoOutputPlan.targetSize(PhotoOutputResize.Scale(1), 10, 10))
    }

    @Test
    fun outputsAboveTheBitmapBudgetStreamAsPng() {
        val huge = plan(PhotoOutputSettings(format = PhotoOutputFormat.WebpLossy), width = 12_000, height = 9_000)
        assertTrue(huge.tiled)
        assertEquals(PhotoEncoding.Png, huge.encoding)
        assertEquals(listOf(PhotoOutputAdjustment.TooLargeUsesPng), huge.adjustments)
        assertEquals(12_000, huge.width)
        val resized = plan(
            PhotoOutputSettings(format = PhotoOutputFormat.WebpLossy, resize = PhotoOutputResize.LongSide(2048)),
            width = 12_000,
            height = 9_000,
        )
        assertFalse(resized.tiled)
        assertEquals(4, resized.sample)
        assertEquals(PhotoEncoding.WebpLossy, resized.encoding)
        // A 4096 px output still needs a 6000 x 4500 decode, which is over the budget.
        val stillLarge = plan(
            PhotoOutputSettings(resize = PhotoOutputResize.LongSide(4096)),
            width = 12_000,
            height = 9_000,
        )
        assertTrue(stillLarge.tiled)
        assertEquals(PhotoEncoding.Png, stillLarge.encoding)
    }

    @Test
    fun aSmallCropOfAHugeSourceStillNeedsTheStreamingPath() {
        val crop = PhotoOutputPlan.resolve(
            PhotoOutputSettings(), "image/jpeg", 2000, 1500, all, max, sourceWidth = 16_000, sourceHeight = 12_000,
        )
        assertTrue(crop.tiled)
        assertEquals(2000, crop.width)
    }

    @Test
    fun decodeSampleNeverDropsBelowTheOutput() {
        assertEquals(1, PhotoOutputPlan.decodeSample(1.0))
        assertEquals(1, PhotoOutputPlan.decodeSample(0.51))
        assertEquals(2, PhotoOutputPlan.decodeSample(0.5))
        assertEquals(2, PhotoOutputPlan.decodeSample(0.3))
        assertEquals(4, PhotoOutputPlan.decodeSample(0.25))
    }

    @Test
    fun metadataFollowsTheChoiceAndTheContainer() {
        val stripped = plan(PhotoOutputSettings(metadata = PhotoOutputMetadata.RemoveAll))
        assertFalse(stripped.copyMetadata)
        val noGps = plan(PhotoOutputSettings(metadata = PhotoOutputMetadata.RemoveLocation))
        assertTrue(noGps.copyMetadata)
        assertTrue(noGps.removeLocation)
        val gif = plan(PhotoOutputSettings(format = PhotoOutputFormat.Gif))
        assertFalse(gif.copyMetadata)
        assertEquals(listOf(PhotoOutputAdjustment.MetadataUnsupported), gif.adjustments)
        val gifStripped = plan(PhotoOutputSettings(format = PhotoOutputFormat.Gif, metadata = PhotoOutputMetadata.RemoveAll))
        assertTrue(gifStripped.adjustments.isEmpty())
    }

    @Test
    fun onlyLossyFormatsUseQuality() {
        assertTrue(PhotoOutputFormat.Jpeg.usesQuality)
        assertTrue(PhotoOutputFormat.WebpLossy.usesQuality)
        assertTrue(PhotoOutputFormat.Avif.usesQuality)
        assertFalse(PhotoOutputFormat.Png.usesQuality)
        assertFalse(PhotoOutputFormat.WebpLossless.usesQuality)
        assertFalse(PhotoOutputFormat.Gif.usesQuality)
    }
}
