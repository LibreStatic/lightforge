package com.librestatic.lightforge.core.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random

class IccProfileTest {
    private fun u32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun u32(v: Int) = u32(v.toLong())
    private fun s15(v: Double) = u32(Math.round(v * 65536.0))

    private fun xyzTag(x: Double, y: Double, z: Double) = "XYZ ".toByteArray() + u32(0) + s15(x) + s15(y) + s15(z)
    private fun chadTag(m: DoubleArray) = "sf32".toByteArray() + u32(0) + m.flatMap { s15(it).toList() }.toByteArray()
    private fun gammaCurv(g: Double) = "curv".toByteArray() + u32(0) + u32(1) +
        byteArrayOf((Math.round(g * 256) shr 8).toByte(), Math.round(g * 256).toByte(), 0, 0)
    private fun sRgbPara() = "para".toByteArray() + u32(0) + byteArrayOf(0, 3, 0, 0) +
        listOf(2.4, 1 / 1.055, 0.055 / 1.055, 1 / 12.92, 0.04045).flatMap { s15(it).toList() }.toByteArray()

    private fun profile(tags: List<Pair<String, ByteArray>>, colorSpace: String = "RGB "): ByteArray {
        val header = ByteArray(128)
        colorSpace.toByteArray().copyInto(header, 16)
        "XYZ ".toByteArray().copyInto(header, 20)
        "acsp".toByteArray().copyInto(header, 36)
        val table = ByteArrayOutputStream()
        val data = ByteArrayOutputStream()
        val base = 128 + 4 + tags.size * 12
        for ((sig, bytes) in tags) {
            table.write(sig.toByteArray())
            table.write(u32(base + data.size()))
            table.write(u32(bytes.size))
            data.write(bytes)
            while (data.size() % 4 != 0) data.write(0)
        }
        return header + u32(tags.size) + table.toByteArray() + data.toByteArray()
    }

    private val bradfordD65ToD50 = doubleArrayOf(
        1.04788, 0.02293, -0.05022,
        0.02959, 0.99048, -0.01707,
        -0.00925, 0.01508, 0.75168,
    )

    private fun p3(trc: ByteArray, withChad: Boolean = true) = profile(
        buildList {
            add("rXYZ" to xyzTag(0.51512, 0.24120, -0.00105))
            add("gXYZ" to xyzTag(0.29198, 0.69225, 0.04189))
            add("bXYZ" to xyzTag(0.15710, 0.06657, 0.78407))
            add("wtpt" to xyzTag(0.96420, 1.0, 0.82491))
            if (withChad) add("chad" to chadTag(bradfordD65ToD50))
            add("rTRC" to trc)
            add("gTRC" to trc)
            add("bTRC" to trc)
        },
    )

    private fun srgb(trc: ByteArray) = profile(
        listOf(
            "rXYZ" to xyzTag(0.43607, 0.22249, 0.01392),
            "gXYZ" to xyzTag(0.38515, 0.71687, 0.09708),
            "bXYZ" to xyzTag(0.14307, 0.06061, 0.71410),
            "wtpt" to xyzTag(0.96420, 1.0, 0.82491),
            "chad" to chadTag(bradfordD65ToD50),
            "rTRC" to trc,
        ),
    )

    private fun nclx(primaries: Int, transfer: Int) =
        HeifProperty.Colour(matrixCoefficients = 6, fullRange = true, colourPrimaries = primaries, transfer = transfer)

    @Test
    fun displayP3WithParaCurveIsRecognised() {
        val space = parseIccRgb(p3(sRgbPara()))!!
        assertTrue(space.isDisplayP3)
        assertFalse(space.isSrgb)
        assertEquals(ResolvedColourSpace.Named.DISPLAY_P3, resolveColourSpace(null, p3(sRgbPara())))
    }

    @Test
    fun displayP3WithoutChadAssumesD65() {
        assertTrue(parseIccRgb(p3(sRgbPara(), withChad = false))!!.isDisplayP3)
    }

    @Test
    fun srgbIsRecognised() {
        val space = parseIccRgb(srgb(sRgbPara()))!!
        assertTrue(space.isSrgb)
        assertFalse(space.isDisplayP3)
        assertEquals(ResolvedColourSpace.Named.SRGB, resolveColourSpace(null, srgb(sRgbPara())))
    }

    @Test
    fun p3PrimariesWithGammaCurveAreCustom() {
        val resolved = resolveColourSpace(null, p3(gammaCurv(2.2)))
        val custom = (resolved as ResolvedColourSpace.Custom).space
        assertEquals(0.680, custom.primaries[0], 0.002)
        assertEquals(0.320, custom.primaries[1], 0.002)
        assertEquals(0.265, custom.primaries[2], 0.002)
        assertEquals(0.690, custom.primaries[3], 0.002)
        assertEquals(0.150, custom.primaries[4], 0.002)
        assertEquals(0.060, custom.primaries[5], 0.002)
        assertEquals(0.3127, custom.whitePoint[0], 0.002)
        assertEquals(0.3290, custom.whitePoint[1], 0.002)
        assertEquals(2.2, custom.transfer.g, 0.01)
    }

    @Test
    fun arbitraryPrimariesAreCustom() {
        val profile = profile(
            listOf(
                "rXYZ" to xyzTag(0.6, 0.3, 0.0),
                "gXYZ" to xyzTag(0.2, 0.7, 0.1),
                "bXYZ" to xyzTag(0.15, 0.05, 0.7),
                "chad" to chadTag(bradfordD65ToD50),
                "rTRC" to gammaCurv(1.8),
            ),
        )
        assertTrue(resolveColourSpace(null, profile) is ResolvedColourSpace.Custom)
    }

    @Test
    fun nclxTakesPrecedenceOverIcc() {
        val icc = srgb(sRgbPara())
        assertEquals(ResolvedColourSpace.Named.DISPLAY_P3, resolveColourSpace(nclx(12, 13), icc))
        assertEquals(ResolvedColourSpace.Named.SRGB, resolveColourSpace(nclx(1, 13), p3(sRgbPara())))
        assertEquals(ResolvedColourSpace.Named.BT2020, resolveColourSpace(nclx(9, 1), icc))
        // HDR BT.2020 stays untagged even when an ICC profile exists.
        assertNull(resolveColourSpace(nclx(9, 16), icc))
        assertNull(resolveColourSpace(nclx(9, 18), icc))
        // Unspecified primaries fall back to the ICC profile.
        assertEquals(ResolvedColourSpace.Named.SRGB, resolveColourSpace(nclx(2, 2), icc))
    }

    @Test
    fun garbageAndTruncatedProfilesYieldNull() {
        assertNull(parseIccRgb(ByteArray(0)))
        assertNull(parseIccRgb(ByteArray(500)))
        assertNull(parseIccRgb(profile(emptyList())))
        assertNull(parseIccRgb(p3(sRgbPara()).also { "GRAY".toByteArray().copyInto(it, 16) }))
        assertNull(resolveColourSpace(null, ByteArray(10) { 7 }))
        val valid = p3(sRgbPara())
        for (length in 0 until valid.size) parseIccRgb(valid.copyOf(length))
        val random = Random(7)
        repeat(3000) {
            val bytes = valid.copyOf()
            repeat(1 + random.nextInt(6)) { bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte() }
            parseIccRgb(bytes)
        }
        assertNotNull(parseIccRgb(valid))
    }
}
