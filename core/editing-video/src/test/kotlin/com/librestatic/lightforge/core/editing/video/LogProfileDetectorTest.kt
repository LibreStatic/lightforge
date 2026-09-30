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
}
