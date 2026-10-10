package com.librestatic.lightforge.core.frameinterpolation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrToSdrTest {
    private val hlg = HdrToSdr.forTransfer(HdrTransfer.Hlg)!!
    private val pq = HdrToSdr.forTransfer(HdrTransfer.Pq)!!

    private fun red(argb: Int) = (argb shr 16) and 0xff
    private fun green(argb: Int) = (argb shr 8) and 0xff
    private fun blue(argb: Int) = argb and 0xff

    /** 8-bit video code (full-range R'G'B') in the converter's quarter-step units. */
    private fun code(value: Int) = value * 4

    @Test
    fun sdrNeedsNoConversion() {
        assertNull(HdrToSdr.forTransfer(HdrTransfer.Sdr))
        assertNotNull(HdrToSdr.forTransfer(HdrTransfer.Hlg))
    }

    @Test
    fun hlgEndpoints() {
        assertEquals(0xff000000.toInt(), hlg.map(0, 0, 0, bt2020 = true))
        val white = hlg.map(code(255), code(255), code(255), bt2020 = true)
        assertTrue("peak white ${Integer.toHexString(white)}", red(white) >= 253 && green(white) >= 253 && blue(white) >= 253)
        assertEquals(0xff, white ushr 24)
    }

    @Test
    fun hlgInverseOetfKnownPoints() {
        assertEquals(0f, HdrToSdr.hlgInverseOetf(0f), 1e-6f)
        assertEquals(1f / 12f, HdrToSdr.hlgInverseOetf(0.5f), 1e-4f)
        assertEquals(1f, HdrToSdr.hlgInverseOetf(1f), 1e-3f)
    }

    @Test
    fun pqEotfKnownPoints() {
        assertEquals(0f, HdrToSdr.pqEotfNits(0f), 1e-3f)
        assertEquals(10_000f, HdrToSdr.pqEotfNits(1f), 1f)
        // 58 % of the PQ range is ~203 nit reference white.
        assertEquals(203f, HdrToSdr.pqEotfNits(0.5807f), 3f)
    }

    @Test
    fun hlgGreyRampIsMonotonicAndGrey() {
        var previous = -1
        for (value in 0..255) {
            val out = hlg.map(code(value), code(value), code(value), bt2020 = true)
            assertTrue("grey stays grey at $value", Math.abs(red(out) - green(out)) <= 1 && Math.abs(green(out) - blue(out)) <= 1)
            assertTrue("monotonic at $value", red(out) >= previous)
            previous = red(out)
        }
    }

    @Test
    fun hlgShadowsAreNotLifted() {
        // Treated as plain SDR gamma, HLG signal 0.25 (code 64) would land near 64; the
        // display-referred HLG curve leaves it much darker (~23).
        val out = hlg.map(code(64), code(64), code(64), bt2020 = true)
        assertTrue("shadow ${red(out)}", red(out) < 32)
    }

    @Test
    fun pqReferenceWhiteIsSdrWhite() {
        val code = (0.5807f * 255f).toInt()
        val out = pq.map(code * 4, code * 4, code * 4, bt2020 = true)
        assertTrue("PQ 203 nit ${red(out)}", red(out) in 230..255)
    }

    @Test
    fun gamutConversionSaturatesBt2020Primaries() {
        val pure = hlg.map(code(200), 0, 0, bt2020 = true)
        val plain = hlg.map(code(200), 0, 0, bt2020 = false)
        // A BT.2020 primary lies outside BT.709: red gains saturation, the out-of-gamut green/blue clamp to black.
        assertTrue(red(pure) > red(plain))
        assertEquals(0, green(pure))
        assertEquals(0, blue(pure))
    }

    @Test
    fun srgbOetfEndpointsAndToe() {
        assertEquals(0f, HdrToSdr.srgbOetf(0f), 0f)
        assertEquals(1f, HdrToSdr.srgbOetf(1f), 1e-5f)
        assertEquals(12.92f * 0.002f, HdrToSdr.srgbOetf(0.002f), 1e-6f)
    }

    @Test
    fun rollOffKeepsKneeAndApproachesOne() {
        assertEquals(0.5f, HdrToSdr.rollOff(0.5f), 0f)
        assertTrue(HdrToSdr.rollOff(4f) < 1.0001f)
        assertTrue(HdrToSdr.rollOff(4f) > HdrToSdr.rollOff(1f))
    }
}
