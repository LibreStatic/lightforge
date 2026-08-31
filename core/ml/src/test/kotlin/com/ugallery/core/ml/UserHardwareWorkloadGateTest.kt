package com.ugallery.core.ml

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserHardwareWorkloadGateTest {
    @After fun tearDown() = UserHardwareWorkloadGate.resetForTests()

    @Test fun `gate remains active until every overlapping workload releases`() {
        val viewer = UserHardwareWorkloadGate.acquire(UserHardwareWorkload.VideoViewer)
        val export = UserHardwareWorkloadGate.acquire(UserHardwareWorkload.VideoExport)

        assertTrue(viewer.activatedGate)
        assertFalse(export.activatedGate)
        assertFalse(UserHardwareWorkloadGate.release(viewer))
        assertTrue(UserHardwareWorkloadGate.isActive())
        assertTrue(UserHardwareWorkloadGate.release(export))
        assertFalse(UserHardwareWorkloadGate.isActive())
    }

    @Test fun `duplicate release cannot resume analysis early`() {
        val lease = UserHardwareWorkloadGate.acquire(UserHardwareWorkload.PhotoEditor)

        assertTrue(UserHardwareWorkloadGate.release(lease))
        assertFalse(UserHardwareWorkloadGate.release(lease))
    }
}
