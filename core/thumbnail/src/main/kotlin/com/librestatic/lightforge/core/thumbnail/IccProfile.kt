package com.librestatic.lightforge.core.thumbnail

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/**
 * Pure-JVM reading of matrix/TRC RGB ICC profiles (what phones embed, e.g. Display P3), producing
 * chromaticities and transfer parameters that map onto `android.graphics.ColorSpace.Rgb`. Anything
 * unsupported or malformed yields null; nothing here throws.
 */

/** Transfer function in Android `TransferParameters` form: `(a*x+b)^g + e` for x >= d, else `c*x+f`. */
internal class IccTransfer(
    val a: Double, val b: Double, val c: Double, val d: Double,
    val e: Double, val f: Double, val g: Double,
) {
    fun eval(x: Double): Double =
        if (x >= d) (a * x + b).coerceAtLeast(0.0).pow(g) + e else c * x + f

    /** True when the curve matches the sRGB EOTF at sampled points (covers para and sampled curv tables). */
    fun isSrgb(): Boolean = (0..SAMPLES).all { i ->
        val x = i.toDouble() / SAMPLES
        abs(eval(x) - SRGB.eval(x)) < TRC_TOLERANCE
    }

    companion object {
        private const val SAMPLES = 32
        private const val TRC_TOLERANCE = 0.005
        val SRGB = IccTransfer(1.0 / 1.055, 0.055 / 1.055, 1.0 / 12.92, 0.04045, 0.0, 0.0, 2.4)
        fun gamma(g: Double) = IccTransfer(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, g)
    }
}

/** [primaries] is rx, ry, gx, gy, bx, by; [whitePoint] is x, y; both CIE xy of the original (non-D50) space. */
internal class IccRgbSpace(
    val primaries: DoubleArray,
    val whitePoint: DoubleArray,
    val transfer: IccTransfer,
) {
    fun matches(refPrimaries: DoubleArray, refWhite: DoubleArray): Boolean =
        primaries.indices.all { abs(primaries[it] - refPrimaries[it]) <= XY_TOLERANCE } &&
            whitePoint.indices.all { abs(whitePoint[it] - refWhite[it]) <= XY_TOLERANCE }

    val isDisplayP3: Boolean get() = matches(P3_PRIMARIES, D65_XY) && transfer.isSrgb()
    val isSrgb: Boolean get() = matches(SRGB_PRIMARIES, D65_XY) && transfer.isSrgb()

    companion object {
        const val XY_TOLERANCE = 0.002
        val D65_XY = doubleArrayOf(0.3127, 0.3290)
        val SRGB_PRIMARIES = doubleArrayOf(0.640, 0.330, 0.300, 0.600, 0.150, 0.060)
        val P3_PRIMARIES = doubleArrayOf(0.680, 0.320, 0.265, 0.690, 0.150, 0.060)
    }
}

/** Colour space chosen for a decoded HEIF image; the Android mapping lives in `HeifColorSpace.kt`. */
internal sealed interface ResolvedColourSpace {
    enum class Named : ResolvedColourSpace { SRGB, DISPLAY_P3, BT2020 }
    class Custom(val space: IccRgbSpace) : ResolvedColourSpace
}

/**
 * nclx wins when it names known primaries, otherwise the ICC profile. Returns null (leave untagged)
 * when nothing is usable.
 */
internal fun resolveColourSpace(nclx: HeifProperty.Colour?, icc: ByteArray?): ResolvedColourSpace? {
    if (nclx != null) {
        val hdr = nclx.transfer == TRANSFER_PQ || nclx.transfer == TRANSFER_HLG
        when (nclx.colourPrimaries) {
            1 -> if (!hdr) return ResolvedColourSpace.Named.SRGB
            12 -> if (!hdr) return ResolvedColourSpace.Named.DISPLAY_P3
            // BT.2020 with PQ/HLG is HDR and needs tone mapping that a plain ColorSpace tag cannot express,
            // so it stays untagged; only SDR transfers are tagged as BT2020.
            9 -> return if (hdr) null else ResolvedColourSpace.Named.BT2020
        }
    }
    val space = icc?.let(::parseIccRgb) ?: return null
    return when {
        space.isDisplayP3 -> ResolvedColourSpace.Named.DISPLAY_P3
        space.isSrgb -> ResolvedColourSpace.Named.SRGB
        else -> ResolvedColourSpace.Custom(space)
    }
}

private const val TRANSFER_PQ = 16
private const val TRANSFER_HLG = 18

private val D50_XYZ = doubleArrayOf(0.9642, 1.0, 0.8249)
private val D65_XYZ = doubleArrayOf(0.95047, 1.0, 1.08883)

private val BRADFORD = doubleArrayOf(
    0.8951, 0.2664, -0.1614,
    -0.7502, 1.7135, 0.0367,
    0.0389, -0.0685, 1.0296,
)

/** Row-major 3x3 Bradford adaptation matrix taking XYZ under [src] white to XYZ under [dst] white. */
private fun bradford(src: DoubleArray, dst: DoubleArray): DoubleArray {
    val s = mul(BRADFORD, src)
    val d = mul(BRADFORD, dst)
    val scale = doubleArrayOf(d[0] / s[0], 0.0, 0.0, 0.0, d[1] / s[1], 0.0, 0.0, 0.0, d[2] / s[2])
    return mul3(invert(BRADFORD) ?: error("singular"), mul3(scale, BRADFORD))
}

private fun mul(m: DoubleArray, v: DoubleArray) = DoubleArray(3) { r ->
    m[r * 3] * v[0] + m[r * 3 + 1] * v[1] + m[r * 3 + 2] * v[2]
}

private fun mul3(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
    val r = i / 3
    val c = i % 3
    a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
}

private fun invert(m: DoubleArray): DoubleArray? {
    val a = m[0]; val b = m[1]; val c = m[2]
    val d = m[3]; val e = m[4]; val f = m[5]
    val g = m[6]; val h = m[7]; val i = m[8]
    val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
    if (abs(det) < 1e-9 || det.isNaN()) return null
    return doubleArrayOf(
        (e * i - f * h), (c * h - b * i), (b * f - c * e),
        (f * g - d * i), (a * i - c * g), (c * d - a * f),
        (d * h - e * g), (b * g - a * h), (a * e - b * d),
    ).map { it / det }.toDoubleArray()
}

private fun xy(xyz: DoubleArray): DoubleArray? {
    val sum = xyz[0] + xyz[1] + xyz[2]
    if (sum <= 0.0 || sum.isNaN()) return null
    return doubleArrayOf(xyz[0] / sum, xyz[1] / sum)
}

/** Parses a matrix/TRC RGB ICC profile; null for anything malformed, unsupported or not RGB. Never throws. */
internal fun parseIccRgb(bytes: ByteArray): IccRgbSpace? = try {
    parseIccRgbOrThrow(bytes)
} catch (_: RuntimeException) {
    null
}

private fun parseIccRgbOrThrow(bytes: ByteArray): IccRgbSpace? {
    if (bytes.size < 132) return null
    fun u32(at: Int): Long {
        require(at >= 0 && at + 4 <= bytes.size)
        return ((bytes[at].toLong() and 0xFF) shl 24) or ((bytes[at + 1].toLong() and 0xFF) shl 16) or
            ((bytes[at + 2].toLong() and 0xFF) shl 8) or (bytes[at + 3].toLong() and 0xFF)
    }
    fun u16(at: Int): Int {
        require(at >= 0 && at + 2 <= bytes.size)
        return ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
    }
    fun sig(at: Int): String {
        require(at >= 0 && at + 4 <= bytes.size)
        return String(bytes, at, 4, Charsets.ISO_8859_1)
    }
    fun s15(at: Int): Double = u32(at).toInt() / 65536.0

    if (sig(36) != "acsp" || sig(16) != "RGB ") return null
    val tagCount = u32(128)
    if (tagCount !in 1..MAX_TAGS) return null
    val tags = HashMap<String, Pair<Int, Int>>()
    for (i in 0 until tagCount.toInt()) {
        val entry = 132 + i * 12
        val offset = u32(entry + 4)
        val size = u32(entry + 8)
        if (offset + size > bytes.size) continue
        tags.putIfAbsent(sig(entry), offset.toInt() to size.toInt())
    }

    fun xyzTag(name: String): DoubleArray? {
        val (offset, size) = tags[name] ?: return null
        if (size < 20 || sig(offset) != "XYZ ") return null
        return doubleArrayOf(s15(offset + 8), s15(offset + 12), s15(offset + 16))
    }

    val r = xyzTag("rXYZ") ?: return null
    val g = xyzTag("gXYZ") ?: return null
    val b = xyzTag("bXYZ") ?: return null

    var chad: DoubleArray? = null
    tags["chad"]?.let { (offset, size) ->
        if (size >= 44 && sig(offset) == "sf32") {
            chad = DoubleArray(9) { s15(offset + 8 + it * 4) }
        }
    }
    // PCS values are D50-adapted. Undo the adaptation to recover the display's real primaries/white.
    val adaptation = chad ?: bradford(D65_XYZ, D50_XYZ)
    val inverse = invert(adaptation) ?: return null
    val whiteXy = if (chad != null) {
        val pcsWhite = xyzTag("wtpt") ?: D50_XYZ
        xy(mul(inverse, pcsWhite)) ?: return null
    } else {
        IccRgbSpace.D65_XY
    }
    val rXy = xy(mul(inverse, r)) ?: return null
    val gXy = xy(mul(inverse, g)) ?: return null
    val bXy = xy(mul(inverse, b)) ?: return null

    val (trcOffset, trcSize) = tags["rTRC"] ?: return null
    val transfer = readTrc(trcOffset, trcSize, ::u32, ::u16, ::sig, ::s15) ?: return null
    val values = rXy + gXy + bXy + whiteXy
    if (values.any { it.isNaN() || it.isInfinite() }) return null
    return IccRgbSpace(rXy + gXy + bXy, whiteXy, transfer)
}

private fun readTrc(
    offset: Int,
    size: Int,
    u32: (Int) -> Long,
    u16: (Int) -> Int,
    sig: (Int) -> String,
    s15: (Int) -> Double,
): IccTransfer? {
    if (size < 12) return null
    when (sig(offset)) {
        "curv" -> {
            val count = u32(offset + 8)
            if (count < 0 || 12 + count * 2 > size) return null
            return when {
                count == 0L -> IccTransfer.gamma(1.0)
                count == 1L -> IccTransfer.gamma(u16(offset + 12) / 256.0)
                else -> tableTransfer(count.toInt()) { u16(offset + 12 + it * 2) / 65535.0 }
            }
        }
        "para" -> {
            val type = u16(offset + 8)
            val counts = intArrayOf(1, 3, 4, 5, 7)
            if (type !in counts.indices || size < 12 + counts[type] * 4) return null
            val p = DoubleArray(counts[type]) { s15(offset + 12 + it * 4) }
            val t = when (type) {
                0 -> IccTransfer(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, p[0])
                1 -> IccTransfer(p[1], p[2], 0.0, -p[2] / p[1], 0.0, 0.0, p[0])
                2 -> IccTransfer(p[1], p[2], 0.0, -p[2] / p[1], p[3], p[3], p[0])
                3 -> IccTransfer(p[1], p[2], p[3], p[4], 0.0, 0.0, p[0])
                else -> IccTransfer(p[1], p[2], p[3], p[4], p[5], p[6], p[0])
            }
            return t.takeIf { it.g > 0.0 && it.d.isFinite() && it.a.isFinite() }
        }
    }
    return null
}

/** Sampled curves are accepted only when they look like sRGB or a pure gamma. */
private fun tableTransfer(count: Int, at: (Int) -> Double): IccTransfer? {
    fun sample(x: Double): Double {
        val pos = x * (count - 1)
        val lo = pos.toInt().coerceIn(0, count - 1)
        val hi = (lo + 1).coerceAtMost(count - 1)
        val t = pos - lo
        return at(lo) * (1 - t) + at(hi) * t
    }
    val points = (0..32).map { it / 32.0 }
    if (points.all { abs(sample(it) - IccTransfer.SRGB.eval(it)) < 0.005 }) return IccTransfer.SRGB
    val mid = sample(0.5)
    if (mid <= 0.0 || mid >= 1.0) return null
    val gamma = IccTransfer.gamma(ln(mid) / ln(0.5))
    return gamma.takeIf { t -> points.all { abs(sample(it) - t.eval(it)) < 0.01 } }
}

private const val MAX_TAGS = 1000L
