package com.librestatic.lightforge.core.editing.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogProfileDetectorTest {
    @Test
    fun recognizesCommonMetadataAliases() {
        val samples = listOf(
            "transfer=AppleLog" to LogInputProfile.AppleLog,
            "vendor.transfer=s log3" to LogInputProfile.SonySLog3,
            "curve=c-log2" to LogInputProfile.CanonLog2,
            "profile=log c4" to LogInputProfile.ArriLogC4,
            "gamma=redlog3g10" to LogInputProfile.RedLog3G10,
        )

        samples.forEach { (description, expected) ->
            assertEquals(expected, LogProfileDetector.detectDescription(description).profile)
        }
    }

    @Test
    fun choosesTheMoreSpecificFLog2ProfileBeforeFLog() {
        assertEquals(
            LogInputProfile.FujifilmFLog2,
            LogProfileDetector.detectDescription("codec metadata F-LOG2").profile,
        )
    }

    @Test
    fun unknownMetadataStaysStandardWithLowConfidence() {
        val detection = LogProfileDetector.detectDescription("video/avc writer=myvlogger")

        assertEquals(LogInputProfile.Standard, detection.profile)
        assertTrue(detection.confidence < 0.8f)
    }

    @Test
    fun recognizesOpenCineCamTakesByLayoutAndSignature() {
        fun detect(location: String, transfer: Int? = null, range: Int? = 1, fps: Float? = 60f) =
            LogProfileDetector.detectOpenCineLog(location, colorStandard = 6, colorTransfer = transfer, colorRange = range, frameRate = fps)

        val take = "DCIM/OpenCineCam/OCC_TAKE_2071ac23/OCC_2071ac23.mp4"
        assertEquals(LogInputProfile.OpenCineLog2Hlg, detect(take)?.profile)
        assertEquals(LogInputProfile.OpenCineLog2Hlg, detect("OCC_2071ac23.mp4", transfer = 0)?.profile)
        assertEquals(LogInputProfile.OpenCineLog2Hfr, detect(take, fps = 120f)?.profile)
        assertTrue(detect(take)!!.confidence >= 0.8f)
    }

    @Test
    fun ignoresOtherVideosThatOnlyShareOnePartOfTheSignature() {
        val take = "DCIM/OpenCineCam/OCC_TAKE_2071ac23/OCC_2071ac23.mp4"
        // HLG (7), PQ (6) and Rec.709 (3) are real transfers: baked or HDR recordings, not OCLog2.
        listOf(3, 6, 7).forEach { transfer ->
            assertEquals(null, LogProfileDetector.detectOpenCineLog(take, 6, transfer, 1, 60f))
        }
        assertEquals(null, LogProfileDetector.detectOpenCineLog(take, 6, null, 2, 60f))
        assertEquals(null, LogProfileDetector.detectOpenCineLog(take, 1, null, 1, 60f))
        assertEquals(null, LogProfileDetector.detectOpenCineLog("DCIM/Camera/VID_1.mp4", 6, null, 1, 60f))
    }
}
