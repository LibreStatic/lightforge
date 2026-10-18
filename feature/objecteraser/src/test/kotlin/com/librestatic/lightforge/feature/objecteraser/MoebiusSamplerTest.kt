package com.librestatic.lightforge.feature.objecteraser

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MoebiusSamplerTest {
    @Test
    fun alphasCumprod_matchesNumpyReference() {
        val ac = MoebiusSampler.AlphasCumprod
        assertEquals(1000, ac.size)
        // np.cumprod(1 - np.linspace(0.00085 ** 0.5, 0.012 ** 0.5, 1000, dtype=np.float64) ** 2)
        val expected = mapOf(
            0 to 0.99915, 1 to 0.9982960278384514, 100 to 0.8942234775865594,
            500 to 0.2763326838229746, 900 to 0.014151141969672461, 999 to 0.004660098513077234,
        )
        expected.forEach { (index, value) -> assertEquals("index $index", value, ac[index], 1e-9) }
    }

    @Test
    fun timesteps_twentySteps_dropFirstAtDefaultStrength() {
        val ts = MoebiusSampler(steps = 20, strength = 0.99).timesteps
        assertEquals(19, ts.size)
        assertArrayEquals(IntArray(19) { 900 - 50 * it }, ts)
        assertEquals(0, ts.last())
    }

    @Test
    fun timesteps_tenSteps_dropFirstAtDefaultStrength() {
        assertArrayEquals(intArrayOf(800, 700, 600, 500, 400, 300, 200, 100, 0), MoebiusSampler(steps = 10, strength = 0.99).timesteps)
    }

    @Test
    fun timesteps_fullStrength_keepsEveryStep() {
        assertArrayEquals(intArrayOf(750, 500, 250, 0), MoebiusSampler(steps = 4, strength = 1.0).timesteps)
    }

    @Test
    fun step_lastStep_returnsCleanEstimate() {
        val sampler = MoebiusSampler(steps = 10)
        val x = floatArrayOf(0.5f, -1.2f, 0.3f)
        val eps = floatArrayOf(0.1f, 0.4f, -0.7f)
        val alpha = MoebiusSampler.AlphasCumprod[0]
        val out = sampler.step(eps, 0, x)
        for (i in x.indices) {
            val clean = (x[i] - Math.sqrt(1 - alpha) * eps[i]) / Math.sqrt(alpha)
            assertEquals(clean, out[i].toDouble(), 1e-6)
        }
    }

    @Test
    fun step_midSchedule_matchesDdimFormula() {
        val sampler = MoebiusSampler(steps = 10)
        val x = floatArrayOf(0.8f)
        val eps = floatArrayOf(-0.25f)
        val aT = MoebiusSampler.AlphasCumprod[500]
        val aPrev = MoebiusSampler.AlphasCumprod[400]
        val clean = (0.8 - Math.sqrt(1 - aT) * -0.25) / Math.sqrt(aT)
        val expected = Math.sqrt(aPrev) * clean + Math.sqrt(1 - aPrev) * -0.25
        assertEquals(expected, sampler.step(eps, 500, x)[0].toDouble(), 1e-6)
    }

    @Test
    fun noise_sameSeed_isIdentical_differentSeed_differs() {
        val sampler = MoebiusSampler()
        assertArrayEquals(sampler.noise(7), sampler.noise(7), 0f)
        assertFalse(sampler.noise(7).contentEquals(sampler.noise(8)))
        assertEquals(64 * 64 * 4, sampler.noise(7).size)
    }

    @Test
    fun noise_offsetIsSharedByEveryPixelOfAChannel() {
        val plain = MoebiusSampler(noiseOffset = 0.0).noise(3)
        val offset = MoebiusSampler(noiseOffset = 0.5).noise(3)
        for (channel in 0 until 4) {
            val shift = offset[channel] - plain[channel]
            assertNotEquals(0f, shift)
            for (pixel in 1 until 64 * 64) assertEquals(shift, offset[pixel * 4 + channel] - plain[pixel * 4 + channel], 1e-5f)
        }
    }

    @Test
    fun passSeed_isStableAndDistinctPerPass() {
        assertEquals(MoebiusConfig.passSeed(2), MoebiusConfig.passSeed(2))
        assertEquals(10, (0 until 10).map(MoebiusConfig::passSeed).toSet().size)
    }

    @Test
    fun latentMask_samplesEveryEighthPixel() {
        val size = 512
        val keep = BooleanArray(size * size) { true }
        keep[16 * size + 24] = false // latent (2, 3)
        keep[17 * size + 24] = false // not on the sampling grid
        val mask = MoebiusSampler().latentMask(keep)
        assertEquals(64 * 64, mask.size)
        assertEquals(1f, mask[2 * 64 + 3], 0f)
        assertEquals(1f, mask.sum(), 0f)
    }

    @Test
    fun unetInput_concatenatesLatentMaskAndMaskedLatent() {
        val pixels = 64 * 64
        val x = FloatArray(pixels * 4) { 1f + it % 4 }
        val masked = FloatArray(pixels * 4) { -(1f + it % 4) }
        val mask = FloatArray(pixels) { if (it == 5) 1f else 0f }
        val input = MoebiusSampler().unetInput(x, mask, masked)
        assertEquals(pixels * 9, input.size)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 1f, -1f, -2f, -3f, -4f), input.copyOfRange(5 * 9, 6 * 9), 0f)
        assertEquals(0f, input[4], 0f)
    }

    @Test
    fun start_blendsLatentAndNoiseByFirstTimestep() {
        val sampler = MoebiusSampler(steps = 10)
        val a = MoebiusSampler.AlphasCumprod[800]
        val out = sampler.start(FloatArray(4) { 2f }, FloatArray(4) { -1f })
        assertEquals(Math.sqrt(a) * 2 - Math.sqrt(1 - a), out[0].toDouble(), 1e-6)
    }

    @Test
    fun rgbAndArgb_roundTripAndBlankTheHole() {
        val sampler = MoebiusSampler()
        val argb = intArrayOf(0xFFFF8000.toInt(), 0xFF0000FF.toInt())
        val keep = booleanArrayOf(true, false)
        val rgb = sampler.rgbInput(argb, keep, blankHole = false)
        assertEquals(1f, rgb[0], 1e-6f)
        assertEquals(-1f, rgb[2], 1e-6f)
        assertEquals(1f, rgb[5], 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), sampler.rgbInput(argb, keep, blankHole = true).copyOfRange(3, 6), 0f)
        assertArrayEquals(argb, sampler.toArgb(FloatArray(6) { (rgb[it] + 1f) / 2f }))
    }

    @Test
    fun memo_reusesResultOfAnIdenticalPass_andEvictsOldest() {
        val memo = InpaintMemo(capacity = 2)
        val argb = IntArray(16) { it }
        val keep = BooleanArray(16) { it % 2 == 0 }
        var calls = 0
        val first = memo.getOrPut(argb, keep, 1L) { calls++; IntArray(1) }
        assertSame(first, memo.getOrPut(argb.clone(), keep.clone(), 1L) { calls++; IntArray(1) })
        assertEquals(1, calls)
        memo.getOrPut(argb, keep, 2L) { calls++; IntArray(1) }
        memo.getOrPut(argb, keep, 3L) { calls++; IntArray(1) }
        assertEquals(3, calls)
        memo.getOrPut(argb, keep, 1L) { calls++; IntArray(1) }
        assertEquals(4, calls)
    }
}
