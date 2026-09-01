package com.ugallery.core.editing.video

import androidx.media3.common.C
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
}
