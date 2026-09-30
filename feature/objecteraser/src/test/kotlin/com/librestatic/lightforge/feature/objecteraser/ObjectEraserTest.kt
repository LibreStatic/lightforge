package com.librestatic.lightforge.feature.objecteraser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectEraserTest {

    @Test
    fun eraseRegion_validRegion_isAccepted() {
        val region = ObjectEraser.EraseRegion(10, 10, 20, 20)
        assertEquals(10, region.x)
        assertEquals(10, region.y)
        assertEquals(20, region.width)
        assertEquals(20, region.height)
    }

    @Test
    fun eraseMethod_fallback_isExplicitlyLabeled() {
        assertEquals(
            "NEIGHBOR_INTERPOLATION_FALLBACK",
            ObjectEraser.EraseMethod.NEIGHBOR_INTERPOLATION_FALLBACK.name
        )
    }

    @Test
    fun eraseMethod_mlInpainting_isDefined() {
        assertEquals(
            "ML_INPAINTING",
            ObjectEraser.EraseMethod.ML_INPAINTING.name
        )
    }
}
