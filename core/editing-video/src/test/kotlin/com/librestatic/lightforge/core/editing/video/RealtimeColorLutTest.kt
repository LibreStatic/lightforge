package com.librestatic.lightforge.core.editing.video

import org.junit.Assert.assertEquals
import org.junit.Test

class RealtimeColorLutTest {
    @Test
    fun cubeIsPackedAsBlueColumnsByRedGreenRows() {
        val cube = Array(2) { red -> Array(2) { green -> IntArray(2) { blue -> red * 100 + green * 10 + blue } } }

        val packed = packCube(cube)

        assertEquals(listOf(0, 1, 10, 11, 100, 101, 110, 111), packed.toList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun raggedCubesAreRejected() {
        packCube(arrayOf(arrayOf(IntArray(2), IntArray(1)), arrayOf(IntArray(2), IntArray(2))))
    }
}
