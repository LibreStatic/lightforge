package com.librestatic.lightforge.core.raw

import com.librestatic.lightforge.core.model.RawCameraCrop
import com.librestatic.lightforge.core.model.RawDevelopmentSettings
import com.librestatic.lightforge.core.model.RawFrameSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawMetadataParsingTest {
    private val legacy = "Make|Model|Lens|4000|3000|1023|64|100|0.01|2.0|5.0|Bayer|RGBG"

    @Test
    fun legacyThirteenFieldFormatHasNoCameraCrop() {
        val metadata = parseRawMetadata(legacy)
        assertEquals(4000, metadata.width)
        assertEquals(4000, metadata.fullWidth)
        assertEquals(3000, metadata.fullHeight)
        assertNull(metadata.cameraCrop)
        assertFalse(metadata.hasMeaningfulCameraCrop)
        assertEquals(RawFrameSize(4000, 3000), metadata.dimensions(cameraFraming = false))
    }

    @Test
    fun newFormatReportsFramedSizeFullSizeCropAndFlip() {
        val metadata = parseRawMetadata(legacy.replace("4000|3000", "873|654") + "|4000|3000|1564|1173|873|654|6")
        assertEquals(873, metadata.width)
        assertEquals(4000, metadata.fullWidth)
        assertEquals(RawCameraCrop(1564, 1173, 873, 654), metadata.cameraCrop)
        assertEquals(6, metadata.flip)
        assertTrue(metadata.hasMeaningfulCameraCrop)
        assertEquals(RawFrameSize(873, 654), metadata.dimensions(cameraFraming = true))
        assertEquals(RawFrameSize(4000, 3000), metadata.dimensions(cameraFraming = false))
    }

    @Test
    fun eightPixelMarginsAreNotMeaningful() {
        val metadata = parseRawMetadata(legacy.replace("4000|3000", "3984|2984") + "|4000|3000|8|8|3984|2984|0")
        assertEquals(RawCameraCrop(8, 8, 3984, 2984), metadata.cameraCrop)
        assertFalse(metadata.hasMeaningfulCameraCrop)
    }

    @Test
    fun noCropWithNewFormatYieldsNullCrop() {
        val metadata = parseRawMetadata("$legacy|4000|3000|0|0|4000|3000|0")
        assertNull(metadata.cameraCrop)
        assertFalse(metadata.hasMeaningfulCameraCrop)
    }

    @Test
    fun framingIsTheLastNativeSetting() {
        assertEquals(17, RawDevelopmentSettings().nativeValues().size)
        assertEquals(1f, RawDevelopmentSettings().nativeValues()[16])
        assertEquals(0f, RawDevelopmentSettings(cameraFraming = false).nativeValues()[16])
    }
}
