package com.librestatic.lightforge.core.editing.image.grade

import com.librestatic.lightforge.core.editing.image.PhotoColorTransform
import com.librestatic.lightforge.core.model.ColorMixBand
import com.librestatic.lightforge.core.model.EditOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class PhotoGradeMathTest {
    private fun px(r: Int, g: Int, b: Int, a: Int = 255) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun ch(p: Int, shift: Int) = (p shr shift) and 0xff
    private fun maxDiff(a: Int, b: Int) =
        maxOf(abs(ch(a, 16) - ch(b, 16)), abs(ch(a, 8) - ch(b, 8)), abs(ch(a, 0) - ch(b, 0)))

    private fun gradeOne(p: Int, params: PhotoGradeParams, base: PhotoLocalBase? = null, x: Int = 3, y: Int = 5) =
        PhotoGradeMath.grade(intArrayOf(p), 1, 1, params, base, x, y, 64, 64)[0]

    private fun constantBase(value: Float) = PhotoLocalBase(4, 4, FloatArray(16) { value })

    private fun greyRamp() = IntArray(256) { px(it, it, it) }

    private fun gradeRamp(params: PhotoGradeParams, base: PhotoLocalBase? = constantBase(0.2f)): IntArray =
        PhotoGradeMath.grade(greyRamp(), 256, 1, params, base, 0, 0, 256, 1)

    @Test
    fun neutralParamsAreIdentityWithinOneLevel() {
        val params = PhotoGradeParams()
        assertTrue(params.isIdentity)
        assertFalse(params.needsLocalBase)
        val random = Random(7)
        val gradient = IntArray(256 * 4) { px(it % 256, (it * 3) % 256, 255 - it % 256) }
        val noise = IntArray(1024) { px(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        for (input in listOf(gradient, noise)) {
            val out = PhotoGradeMath.grade(input, input.size, 1, params, null, 0, 0, input.size, 1)
            input.indices.forEach { assertTrue(maxDiff(input[it], out[it]) <= 1) }
        }
    }

    @Test
    fun toneCurvesAreMonotoneOverInput() {
        val variants = listOf(
            PhotoGradeParams(brightness = 0.8f), PhotoGradeParams(brightness = -0.8f),
            PhotoGradeParams(contrast = 0.9f), PhotoGradeParams(contrast = -0.9f),
            PhotoGradeParams(tone = 0.9f), PhotoGradeParams(tone = -0.9f),
            PhotoGradeParams(whitePoint = 0.9f), PhotoGradeParams(whitePoint = -0.9f),
            PhotoGradeParams(blackPoint = 0.9f), PhotoGradeParams(blackPoint = -0.9f),
            PhotoGradeParams(highlights = 0.9f), PhotoGradeParams(highlights = -0.9f),
            PhotoGradeParams(shadows = 0.9f), PhotoGradeParams(shadows = -0.9f),
        )
        variants.forEach { params ->
            val out = gradeRamp(params)
            for (i in 1 until 256) {
                // One level of slack for the dither.
                assertTrue("non-monotone at $i", ch(out[i], 8) >= ch(out[i - 1], 8) - 1)
            }
        }
    }

    @Test
    fun slidersAreMonotoneForAFixedPixel() {
        fun series(make: (Float) -> PhotoGradeParams): List<Int> =
            listOf(-1f, -0.5f, 0f, 0.5f, 1f).map { ch(gradeOne(px(110, 110, 110), make(it), constantBase(0.2f)), 8) }
        listOf<(Float) -> PhotoGradeParams>(
            { PhotoGradeParams(brightness = it) }, { PhotoGradeParams(whitePoint = it) },
            { PhotoGradeParams(highlights = it) }, { PhotoGradeParams(shadows = it) },
        ).forEach { make ->
            val values = series(make)
            for (i in 1 until values.size) assertTrue(values.toString(), values[i] >= values[i - 1] - 1)
        }
        // Raising the black point only darkens.
        val black = series { PhotoGradeParams(blackPoint = it) }
        for (i in 1 until black.size) assertTrue(black.toString(), black[i] <= black[i - 1] + 1)
    }

    @Test
    fun colorMixBandOnlyAffectsNearbyHues() {
        val mix = List(8) { if (it == 0) ColorMixBand(luminance = -1f) else ColorMixBand() }
        val params = PhotoGradeParams(colorMix = mix)
        val green = px(0, 200, 0) // hue 120
        val aqua = px(0, 200, 200) // hue 180
        val reddish = px(220, 60, 30) // hue ~10
        assertTrue(maxDiff(gradeOne(green, params), green) <= 1)
        assertTrue(maxDiff(gradeOne(aqua, params), aqua) <= 1)
        assertTrue(ch(gradeOne(reddish, params), 16) < 200)
    }

    @Test
    fun colorMixDoesNotLeakBeyondFortyFiveDegrees() {
        val mix = List(8) { if (it == 2) ColorMixBand(saturation = -1f) else ColorMixBand() }
        val params = PhotoGradeParams(colorMix = mix)
        // Yellow band (90 deg): hue 30 (dist 60) is untouched, hue 90 is desaturated.
        val orange = px(255, 128, 0)
        val yellow = px(128, 255, 0) // hue 90
        assertTrue(maxDiff(gradeOne(orange, params), orange) <= 1)
        val out = gradeOne(yellow, params)
        assertTrue(ch(out, 0) > 200)
    }

    @Test
    fun identityLutIsANoOp() {
        val params = PhotoGradeParams(look = PhotoLut.identity(), lookIntensity = 1f)
        val random = Random(3)
        repeat(200) {
            val p = px(random.nextInt(256), random.nextInt(256), random.nextInt(256))
            assertTrue(maxDiff(gradeOne(p, params), p) <= 1)
        }
    }

    @Test
    fun legacyLutMatchesTheColorMatrixMath() {
        val ops = listOf(
            EditOperation.Tone(brightness = 0.05f, contrast = 1.2f, saturation = 1.3f),
            EditOperation.Filter("vivid"),
        )
        val params = PhotoGradeParams.from(ops) { null }
        assertTrue(params.legacyLut != null)
        val random = Random(11)
        repeat(300) {
            val rgb = intArrayOf(random.nextInt(256), random.nextInt(256), random.nextInt(256))
            val expected = floatArrayOf(rgb[0].toFloat(), rgb[1].toFloat(), rgb[2].toFloat())
            ops.forEach { PhotoGradeParams.applyMatrix(PhotoColorTransform.matrixValues(it), expected) }
            val out = gradeOne(px(rgb[0], rgb[1], rgb[2]), params)
            // Trilinear interpolation across a clamped (kinked) function costs a few levels at most.
            assertTrue("$rgb ${out.toString(16)} ${expected.toList()}",
                abs(ch(out, 16) - expected[0]) <= 4 && abs(ch(out, 8) - expected[1]) <= 4 && abs(ch(out, 0) - expected[2]) <= 4)
        }
    }

    @Test
    fun vignetteIsSymmetricAndZeroAtCenter() {
        val size = 101
        val grey = IntArray(size * size) { px(120, 120, 120) }
        val params = PhotoGradeParams(vignette = 0.9f)
        val out = PhotoGradeMath.grade(grey, size, size, params, null, 0, 0, size, size)
        fun at(x: Int, y: Int) = ch(out[y * size + x], 8)
        assertTrue(abs(at(50, 50) - 120) <= 1)
        for ((x, y) in listOf(5 to 7, 20 to 90, 0 to 0, 60 to 3)) {
            val v = at(x, y)
            assertTrue(abs(v - at(size - 1 - x, y)) <= 1)
            assertTrue(abs(v - at(x, size - 1 - y)) <= 1)
            assertTrue(abs(v - at(size - 1 - x, size - 1 - y)) <= 1)
        }
        assertTrue(at(0, 0) < 100)
        val bright = PhotoGradeMath.grade(grey, size, size, PhotoGradeParams(vignette = -0.9f), null, 0, 0, size, size)
        assertTrue(ch(bright[0], 8) > 140)
    }

    @Test
    fun tilesMatchAWholeFrameGrade() {
        val params = PhotoGradeParams(vignette = 0.7f, contrast = 0.3f)
        val width = 40
        val height = 30
        val random = Random(5)
        val frame = IntArray(width * height) { px(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        val whole = PhotoGradeMath.grade(frame, width, height, params, null, 0, 0, width, height)
        val tile = IntArray(10 * 10) { frame[(10 + it / 10) * width + 20 + it % 10] }
        val graded = PhotoGradeMath.grade(tile, 10, 10, params, null, 20, 10, width, height)
        for (i in tile.indices) assertEquals(whole[(10 + i / 10) * width + 20 + i % 10], graded[i])
    }

    @Test
    fun premultipliedAlphaIsPreserved() {
        val half = px(64, 32, 16, a = 128)
        val neutral = gradeOne(half, PhotoGradeParams())
        assertEquals(128, neutral ushr 24)
        assertTrue(maxDiff(neutral, half) <= 1)
        val graded = gradeOne(half, PhotoGradeParams(brightness = 0.5f, saturation = 0.3f, vignette = 0.5f))
        assertEquals(128, graded ushr 24)
        assertTrue(ch(graded, 16) <= 128 && ch(graded, 8) <= 128 && ch(graded, 0) <= 128)
        assertEquals(0, gradeOne(0, PhotoGradeParams(brightness = 0.5f)))
    }

    @Test
    fun hashMatchesTheReferenceBits() {
        // Known uint32 evaluation of the ADR hash at (0, 0) and (1, 2), computed with Long maths.
        fun reference(x: Long, y: Long): Long {
            val m = 0xffffffffL
            var h = ((x * 0x8da6b343L) and m) xor ((y * 0xd8163841L) and m)
            h = h xor (h shr 16)
            h = (h * 0x7feb352dL) and m
            h = h xor (h shr 15)
            h = (h * 0x846ca68bL) and m
            h = h xor (h shr 16)
            return h
        }
        for ((x, y) in listOf(0 to 0, 1 to 2, 1234 to 99999)) {
            assertEquals(reference(x.toLong(), y.toLong()), PhotoGradeMath.hash(x, y).toLong() and 0xffffffffL)
        }
    }

    @Test
    fun missingLookIsReportedAndSkipped() {
        val id = "builtin:vivid"
        val params = PhotoGradeParams.from(listOf(EditOperation.Look(id, 0.5f))) { null }
        assertEquals(id, params.missingLookId)
        assertTrue(params.isIdentity)
        val resolved = PhotoGradeParams.from(listOf(EditOperation.Look(id, 0.5f))) { PhotoLut.identity(5) }
        assertEquals(null, resolved.missingLookId)
        assertFalse(resolved.isIdentity)
    }
}
