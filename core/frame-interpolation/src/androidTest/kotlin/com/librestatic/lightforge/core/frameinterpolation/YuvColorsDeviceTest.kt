package com.librestatic.lightforge.core.frameinterpolation

import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** [YuvColors] reads [MediaFormat], which needs the framework, so it is tested on a device. */
@RunWith(AndroidJUnit4::class)
class YuvColorsDeviceTest {
    private fun format(transfer: Int?, standard: Int?, range: Int? = null) = MediaFormat().apply {
        transfer?.let { setInteger(MediaFormat.KEY_COLOR_TRANSFER, it) }
        standard?.let { setInteger(MediaFormat.KEY_COLOR_STANDARD, it) }
        range?.let { setInteger(MediaFormat.KEY_COLOR_RANGE, it) }
    }

    @Test
    fun hlgBt2020LimitedRange() {
        val colors = YuvColors.from(format(MediaFormat.COLOR_TRANSFER_HLG, MediaFormat.COLOR_STANDARD_BT2020, MediaFormat.COLOR_RANGE_LIMITED))
        assertEquals(HdrTransfer.Hlg, colors.transfer)
        assertTrue(colors.bt2020)
        assertFalse(colors.fullRange)
        assertEquals(2f * (1f - 0.2627f), colors.crToR, 1e-6f)
    }

    @Test
    fun pqIsRecognised() {
        assertEquals(HdrTransfer.Pq, YuvColors.from(format(MediaFormat.COLOR_TRANSFER_ST2084, MediaFormat.COLOR_STANDARD_BT2020)).transfer)
    }

    @Test
    fun tonemappedOutputFormatIsSdr() {
        val colors = YuvColors.from(
            format(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, MediaFormat.COLOR_STANDARD_BT709, MediaFormat.COLOR_RANGE_LIMITED),
            assumedTransfer = HdrTransfer.Hlg,
        )
        assertEquals(HdrTransfer.Sdr, colors.transfer)
        assertFalse(colors.bt2020)
        assertEquals(2f * (1f - 0.2126f), colors.crToR, 1e-6f)
    }

    @Test
    fun missingTransferFallsBackToAssumed() {
        assertEquals(HdrTransfer.Hlg, YuvColors.from(format(null, MediaFormat.COLOR_STANDARD_BT2020), HdrTransfer.Hlg).transfer)
        assertEquals(HdrTransfer.Sdr, YuvColors.from(format(null, null)).transfer)
    }

    @Test
    fun untaggedStreamsStayBt601Sdr() {
        val colors = YuvColors.from(MediaFormat())
        assertEquals(HdrTransfer.Sdr, colors.transfer)
        assertEquals(2f * (1f - 0.299f), colors.crToR, 1e-6f)
        assertFalse(colors.bt2020)
    }
}
