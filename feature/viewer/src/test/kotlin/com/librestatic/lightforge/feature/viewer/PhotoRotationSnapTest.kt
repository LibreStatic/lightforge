package com.librestatic.lightforge.feature.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoRotationSnapTest {

    @Test
    fun rotationSnapsToTheNearestQuarterTurn() {
        assertEquals(0f, snapRotationDegrees(30f), 0f)
        assertEquals(90f, snapRotationDegrees(50f), 0f)
        assertEquals(-90f, snapRotationDegrees(-100f), 0f)
        assertEquals(180f, snapRotationDegrees(200f), 0f)
    }

    @Test
    fun quarterTurnRefitsWithSwappedSides() {
        // 4000x3000 landscape in a 1080x2400 portrait screen: fit is width-bound (0.27);
        // turned, its 3000 side spans the width (0.36), so it grows by 4/3.
        assertEquals(4f / 3f, quarterTurnFitScale(4000f, 3000f, 1080f, 2400f, 90f), 0.001f)
        assertEquals(4f / 3f, quarterTurnFitScale(4000f, 3000f, 1080f, 2400f, -270f), 0.001f)
        assertEquals(1f, quarterTurnFitScale(4000f, 3000f, 1080f, 2400f, 180f), 0f)
    }
}
