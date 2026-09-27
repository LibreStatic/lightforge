package com.librestatic.lightforge.core.ml

import com.librestatic.lightforge.core.ml.LocalAnalysisFeature.Cleanup
import com.librestatic.lightforge.core.ml.LocalAnalysisFeature.Content
import com.librestatic.lightforge.core.ml.LocalAnalysisFeature.People
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAnalysisSwitchesTest {
    @Test
    fun masterOffPausesChildrenAndMasterOnRestoresTheirOwnState() {
        val start = LocalAnalysisSwitches(master = true, remembered = setOf(Content, Cleanup))
        val off = start.withMaster(false)
        assertFalse(off.isActive(Content))
        assertFalse(off.isActive(Cleanup))
        assertEquals(mapOf(Content to false, Cleanup to false), start.changedTo(off))
        val on = off.withMaster(true)
        assertTrue(on.isActive(Content))
        assertTrue(on.isActive(Cleanup))
        assertFalse(on.isActive(People))
    }

    @Test
    fun enablingAChildWhileMasterIsOffTurnsMasterOnWithoutOtherChildren() {
        val next = LocalAnalysisSwitches(master = false, remembered = emptySet()).withFeature(Content, true)
        assertTrue(next.master)
        assertTrue(next.isActive(Content))
        assertFalse(next.isActive(People))
    }

    @Test
    fun enablingAChildRestoresTheOtherRememberedChildren() {
        val next = LocalAnalysisSwitches(master = false, remembered = setOf(Cleanup)).withFeature(Content, true)
        assertTrue(next.isActive(Cleanup))
    }

    @Test
    fun masterOnWithNothingRememberedEnablesEveryFeature() {
        assertEquals(LocalAnalysisSwitches.AllOn, LocalAnalysisSwitches.AllOff.withMaster(true))
    }

    @Test
    fun disablingAChildKeepsMasterOn() {
        val next = LocalAnalysisSwitches.AllOn.withFeature(People, false)
        assertTrue(next.master)
        assertFalse(next.isActive(People))
        assertTrue(next.isActive(Content))
    }
}
