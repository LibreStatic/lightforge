package com.librestatic.lightforge.core.editing.image.grade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class ColorTransferLutBuilderTest {
    private fun labSamples(random: Random, count: Int, mean: FloatArray, std: FloatArray): IntArray {
        val rgb = FloatArray(3)
        return IntArray(count) {
            fun gauss() = (0 until 12).sumOf { random.nextDouble() }.toFloat() - 6f
            Lab.toSrgb(
                (mean[0] + gauss() * std[0]).coerceIn(0f, 100f),
                mean[1] + gauss() * std[1], mean[2] + gauss() * std[2], rgb,
            )
            (255 shl 24) or ((rgb[0] * 255 + 0.5f).toInt() shl 16) or ((rgb[1] * 255 + 0.5f).toInt() shl 8) or
                (rgb[2] * 255 + 0.5f).toInt()
        }
    }

    @Test
    fun labRoundTrips() {
        val lab = FloatArray(3)
        val rgb = FloatArray(3)
        for (c in listOf(floatArrayOf(0.2f, 0.5f, 0.8f), floatArrayOf(1f, 1f, 1f), floatArrayOf(0f, 0f, 0f))) {
            Lab.fromSrgb(c[0], c[1], c[2], lab)
            Lab.toSrgb(lab[0], lab[1], lab[2], rgb)
            for (i in 0..2) assertEquals(c[i], rgb[i], 1e-3f)
        }
        Lab.fromSrgb(1f, 1f, 1f, lab)
        assertEquals(100f, lab[0], 0.1f)
    }

    @Test
    fun lutMovesNeutralPriorMomentsTowardTheReference() {
        val random = Random(9)
        val reference = labSamples(random, 6000, floatArrayOf(65f, 10f, 15f), floatArrayOf(25f, 16f, 14f))
        val result = ColorTransferLutBuilder.build(reference)
        val prior = labSamples(random, 6000, floatArrayOf(50f, 0f, 0f), floatArrayOf(20f, 12f, 12f))
        val graded = IntArray(prior.size) {
            val p = prior[it]
            val out = result.lut.sample(((p shr 16) and 0xff) / 255f, ((p shr 8) and 0xff) / 255f, (p and 0xff) / 255f)
            (255 shl 24) or ((out[0] * 255 + 0.5f).toInt() shl 16) or ((out[1] * 255 + 0.5f).toInt() shl 8) or
                (out[2] * 255 + 0.5f).toInt()
        }
        val before = ColorTransferLutBuilder.statsOf(prior)
        val after = ColorTransferLutBuilder.statsOf(graded)
        val ref = result.stats
        assertEquals(65f, ref.meanL, 5f)
        assertTrue(abs(after.meanL - ref.meanL) < abs(before.meanL - ref.meanL) / 2)
        assertTrue(abs(after.meanA - ref.meanA) < abs(before.meanA - ref.meanA))
        assertTrue(abs(after.meanB - ref.meanB) < abs(before.meanB - ref.meanB))
        assertTrue(abs(after.stdL - ref.stdL) < abs(before.stdL - ref.stdL))
    }
}
