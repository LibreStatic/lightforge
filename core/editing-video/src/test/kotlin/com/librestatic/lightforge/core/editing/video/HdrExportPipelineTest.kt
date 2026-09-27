package com.librestatic.lightforge.core.editing.video

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HdrExportPipelineTest {
    @Test
    fun hlgUsesTenBitBt2020WithoutStaticMetadata() {
        val colorInfo = VideoDynamicRange.HdrHlg.toColorInfo()

        assertEquals(C.COLOR_SPACE_BT2020, colorInfo.colorSpace)
        assertEquals(C.COLOR_TRANSFER_HLG, colorInfo.colorTransfer)
        assertEquals(C.COLOR_RANGE_LIMITED, colorInfo.colorRange)
        assertEquals(10, colorInfo.lumaBitdepth)
        assertEquals(10, colorInfo.chromaBitdepth)
        assertNull(colorInfo.hdrStaticInfo)
    }

    @Test
    fun hdr10UsesPqAndCta861StaticMetadata() {
        val colorInfo = VideoDynamicRange.Hdr10Pq.toColorInfo()

        assertEquals(C.COLOR_SPACE_BT2020, colorInfo.colorSpace)
        assertEquals(C.COLOR_TRANSFER_ST2084, colorInfo.colorTransfer)
        assertEquals(25, colorInfo.hdrStaticInfo?.size)
    }

    @Test
    fun videoFormatsUseTheMedia3SupportedSdrToHdrGraphInput() {
        val source = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build()

        val graphInput = source.asHdrGraphInput()

        assertEquals(ColorInfo.SRGB_BT709_FULL, graphInput.colorInfo)
        assertEquals(MimeTypes.VIDEO_H264, graphInput.sampleMimeType)
    }

    @Test
    fun audioFormatsAreNotChangedByTheHdrGraphAdapter() {
        val source = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .build()

        assertSame(source, source.asHdrGraphInput())
    }

    @Test
    fun hdrEncoderFormatReplacesSdrSourceMetadata() {
        val source = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build()
        val hdr10 = VideoDynamicRange.Hdr10Pq.toColorInfo()

        val output = source.withHdrColorInfo(hdr10)

        assertEquals(hdr10, output.colorInfo)
        assertEquals(C.COLOR_SPACE_BT2020, output.colorInfo?.colorSpace)
        assertEquals(C.COLOR_TRANSFER_ST2084, output.colorInfo?.colorTransfer)
    }
}
