package com.librestatic.lightforge.feature.objecteraser

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InpaintPlanTest {

    @Test
    fun passes_nearbyDabs_shareOnePass_andDistantDabsDoNot() {
        val stroke = listOf(region(100, 100, 40), region(130, 100, 40), region(160, 100, 40))
        val far = region(3000, 2000, 40)
        val passes = InpaintPlan.passes(stroke + far, 4000, 3000)

        assertEquals(2, passes.size)
        val strokePass = passes.single { it.dabs.size == 3 }
        assertEquals(PixelRect(100, 100, 200, 140), strokePass.holes)
        assertEquals(1, passes.single { it.dabs.size == 1 }.dabs.size)
    }

    @Test
    fun passes_dropsDabsOutsideTheImage() {
        val passes = InpaintPlan.passes(listOf(region(-100, -100, 40), region(10, 10, 20)), 800, 600)
        assertEquals(1, passes.size)
        assertEquals(1, passes.single().dabs.size)
    }

    @Test
    fun passes_holesStayInsideTheImage_forAnEdgeDab() {
        val pass = InpaintPlan.passes(listOf(region(-10, -10, 40)), 800, 600).single()
        assertEquals(PixelRect(0, 0, 30, 30), pass.holes)
        assertTrue(pass.crop.left >= 0 && pass.crop.top >= 0)
    }

    @Test
    fun cropAround_smallHole_usesModelSize_centeredOnTheHole() {
        val crop = InpaintPlan.cropAround(PixelRect(1000, 1000, 1040, 1040), 4000, 3000)
        assertEquals(PixelRect(764, 764, 1276, 1276), crop)
    }

    @Test
    fun cropAround_largeHole_addsContextOnEachSide() {
        val crop = InpaintPlan.cropAround(PixelRect(1000, 1000, 1600, 1300), 4000, 3000)
        assertEquals(1200, crop.width)
        assertEquals(1200, crop.height)
        assertTrue(crop.left <= 1000 && crop.right >= 1600 && crop.top <= 1000 && crop.bottom >= 1300)
    }

    @Test
    fun cropAround_isShiftedInside_andNarrowerOnlyWhenTheImageIs() {
        val corner = InpaintPlan.cropAround(PixelRect(3980, 2980, 4000, 3000), 4000, 3000)
        assertEquals(PixelRect(3488, 2488, 4000, 3000), corner)

        val small = InpaintPlan.cropAround(PixelRect(10, 10, 30, 30), 300, 200)
        assertEquals(PixelRect(0, 0, 300, 200), small)
    }

    @Test
    fun keepMask_fillsTheScaledDab_andKeepsTheRest() {
        val crop = PixelRect(0, 0, 1024, 1024)
        val dab = BrushDab(512f, 512f, 50f)
        val keep = InpaintPlan.keepMask(InpaintPass(crop, dab.bounds, listOf(dab)))
        val size = InpaintPlan.ModelSize

        assertFalse(keep[256 * size + 256])
        // Radius 25 model pixels plus 2 of growth: 26 is filled, 28 is kept.
        assertFalse(keep[256 * size + 256 + 26])
        assertTrue(keep[256 * size + 256 + 28])
        assertTrue(keep[0])
        val filled = keep.count { !it }
        assertTrue(filled in 2000..2400)
    }

    @Test
    fun coverage_isFullInside_fadesOverTheFeather_andZeroBeyond() {
        val dab = BrushDab(50f, 50f, 10f)
        val area = PixelRect(0, 0, 100, 100)
        val weights = InpaintPlan.coverage(listOf(dab), area, 4f)

        assertEquals(1f, weights[50 * 100 + 50], 0f)
        assertEquals(1f, weights[50 * 100 + 59], 0f)
        val edge = weights[50 * 100 + 61]
        assertTrue(edge > 0f && edge < 1f)
        assertEquals(0f, weights[50 * 100 + 70], 0f)
        assertEquals(0f, weights[0], 0f)
    }

    @Test
    fun coverage_keepsTheStrongestOverlap_andHonorsTheAreaOffset() {
        val dabs = listOf(BrushDab(50f, 50f, 10f), BrushDab(62f, 50f, 10f))
        val area = PixelRect(40, 45, 80, 55)
        val weights = InpaintPlan.coverage(dabs, area, 4f)

        assertEquals(40 * 10, weights.size)
        // Image pixel (56, 50) is inside both dabs.
        assertEquals(1f, weights[5 * 40 + 16], 0f)
        assertTrue(weights.all { it in 0f..1f })
    }

    @Test
    fun encode_writesMaskAndMaskedRgb_inNhwcOrder() {
        val size = InpaintPlan.ModelSize
        val argb = IntArray(size * size) { 0xFFFF8000.toInt() }
        val keep = BooleanArray(size * size) { it != 1 }
        val input = InpaintPlan.encode(argb, keep)

        assertArrayEquals(floatArrayOf(0.5f, 1f, 128 / 127.5f - 1f, -1f), input.copyOfRange(0, 4), 1e-6f)
        assertArrayEquals(floatArrayOf(-0.5f, 0f, 0f, 0f), input.copyOfRange(4, 8), 0f)
    }

    @Test
    fun decode_mapsMinusOneToOne_toOpaqueColors_andClamps() {
        val size = InpaintPlan.ModelSize
        val output = FloatArray(size * size * 3)
        output[0] = -1f; output[1] = 0f; output[2] = 1f
        output[3] = -2f; output[4] = 2f; output[5] = 0.5f
        val pixels = InpaintPlan.decode(output)

        assertEquals(0xFF0080FF.toInt(), pixels[0])
        assertEquals(0xFF00FFBF.toInt(), pixels[1])
    }

    @Test(expected = IllegalArgumentException::class)
    fun decode_rejectsNonFiniteOutput() {
        val output = FloatArray(InpaintPlan.ModelSize * InpaintPlan.ModelSize * 3)
        output[7] = Float.NaN
        InpaintPlan.decode(output)
    }

    private fun region(x: Int, y: Int, size: Int) = ObjectEraser.EraseRegion(x, y, size, size)
}
