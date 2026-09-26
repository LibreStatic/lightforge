package com.ugallery.feature.viewer

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
}
