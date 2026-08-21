package com.ugallery.core.editing.video

import java.io.StringReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CubeLutTest {
    @Test
    fun parsesAndInterpolatesIdentityCube() {
        val lut = CubeLutParser.parse(StringReader("""
            TITLE "Identity"
            LUT_3D_SIZE 2
            DOMAIN_MIN 0 0 0
            DOMAIN_MAX 1 1 1
            0 0 0
            1 0 0
            0 1 0
            1 1 0
            0 0 1
            1 0 1
            0 1 1
            1 1 1
        """.trimIndent()))

        assertEquals("Identity", lut.title)
        assertEquals(2, lut.size)
        assertArrayEquals(floatArrayOf(0.25f, 0.5f, 0.75f), lut.sample(0.25f, 0.5f, 0.75f), 0.0001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedCube() {
        CubeLutParser.parse(StringReader("LUT_3D_SIZE 66"))
    }

    @Test
    fun expandsOneDimensionalCubeForRgbSampling() {
        val lut = CubeLutParser.parse(StringReader("""
            LUT_1D_SIZE 2
            0.0 0.0 0.0
            0.8 0.6 0.4
        """.trimIndent()))

        assertArrayEquals(floatArrayOf(0.4f, 0.15f, 0.3f), lut.sample(0.5f, 0.25f, 0.75f), 0.0001f)
    }
}
