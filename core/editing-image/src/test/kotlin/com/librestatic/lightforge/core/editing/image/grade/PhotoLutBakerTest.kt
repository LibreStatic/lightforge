package com.librestatic.lightforge.core.editing.image.grade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PhotoLutBakerTest {
    private val expected = listOf(
        "vivid", "natural", "warm", "cool", "golden", "film", "fade", "teal_orange", "pastel", "dramatic", "mono", "noir",
    ).map { "builtin:$it" }

    @Test
    fun bakesAllTwelveLooks() {
        assertEquals(expected, PhotoLutBaker.ids)
        expected.forEach {
            val lut = PhotoLutBaker.bake(it)!!
            assertEquals(33, lut.size)
            assertTrue(lut.data.all { v -> v in 0f..1f })
            assertSame(lut, PhotoLutBaker.bake(it))
        }
        assertNull(PhotoLutBaker.bake("builtin:nope"))
    }

    @Test
    fun greyRampsAreMonotoneInLuma() {
        expected.forEach { id ->
            val lut = PhotoLutBaker.bake(id)!!
            var previous = -1f
            for (i in 0..64) {
                val g = i / 64f
                val out = lut.sample(g, g, g)
                val luma = 0.2126f * out[0] + 0.7152f * out[1] + 0.0722f * out[2]
                assertTrue("$id at $g", luma >= previous - 0.01f)
                previous = luma
            }
        }
    }

    @Test
    fun looksAreDistinct() {
        val luts = expected.map { PhotoLutBaker.bake(it)!! }
        for (i in luts.indices) for (j in i + 1 until luts.size) {
            var sum = 0.0
            for (k in luts[i].data.indices step 7) sum += abs(luts[i].data[k] - luts[j].data[k])
            assertTrue("${expected[i]} vs ${expected[j]}", sum / (luts[i].data.size / 7) > 0.005)
        }
    }
}
