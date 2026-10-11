package com.librestatic.lightforge.core.editing.image.grade

import com.librestatic.lightforge.core.model.ColorMixBand
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-Kotlin reference of ADR-045 stages 0-12. The GLSL shader is a mechanical port: one function
 * here per stage, same constants, same order. Each stage returns immediately when its control is
 * neutral, so a neutral grade is the identity up to the 8-bit round trip.
 */
object PhotoGradeMath {
    private const val LumaR = 0.2126f
    private const val LumaG = 0.7152f
    private const val LumaB = 0.0722f

    /** A working colour, reused across pixels to avoid allocation. */
    class Rgb(@JvmField var r: Float = 0f, @JvmField var g: Float = 0f, @JvmField var b: Float = 0f)

    /**
     * Grades [pixels] (ARGB premultiplied, [width] x [height]) that sit at ([tileOriginX],
     * [tileOriginY]) inside a [frameWidth] x [frameHeight] frame. Global coordinates drive the
     * vignette, the local-base UV and the dither hash, so tiles agree with a whole-frame grade.
     */
    fun grade(
        pixels: IntArray,
        width: Int,
        height: Int,
        params: PhotoGradeParams,
        base: PhotoLocalBase?,
        tileOriginX: Int,
        tileOriginY: Int,
        frameWidth: Int,
        frameHeight: Int,
    ): IntArray {
        val out = IntArray(width * height)
        val color = Rgb()
        val scratch = FloatArray(3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                out[y * width + x] = gradePixel(
                    pixels[y * width + x], tileOriginX + x, tileOriginY + y,
                    params, base, frameWidth, frameHeight, color, scratch,
                )
            }
        }
        return out
    }

    /** Grades one premultiplied ARGB pixel at global frame position ([gx], [gy]). */
    fun gradePixel(
        argb: Int,
        gx: Int,
        gy: Int,
        params: PhotoGradeParams,
        base: PhotoLocalBase?,
        frameWidth: Int,
        frameHeight: Int,
        c: Rgb = Rgb(),
        scratch: FloatArray = FloatArray(3),
    ): Int {
        val alpha = argb ushr 24
        if (alpha == 0) return argb
        val a = alpha.toFloat()
        c.r = min(((argb shr 16) and 0xff) / a, 1f)
        c.g = min(((argb shr 8) and 0xff) / a, 1f)
        c.b = min((argb and 0xff) / a, 1f)

        val u = (gx + 0.5f) / frameWidth
        val v = (gy + 0.5f) / frameHeight
        val aspect = frameWidth.toFloat() / frameHeight

        params.legacyLut?.let { legacyGrade(c, it, scratch) }                        // 0
        toLinear(c)                                                                  // 1
        warmthTint(c, params.warmth, params.tint)                                    // 2
        brightness(c, params.brightness)                                             // 3
        levels(c, params.blackPoint, params.whitePoint)                              // 4
        if (params.needsLocalBase) {
            val b = base?.sample(u, v) ?: luma(c)
            highlightsShadows(c, params.highlights, params.shadows, b)               // 5
            tone(c, params.tone, b)                                                  // 6
        }
        contrast(c, params.contrast)                                                 // 7
        toEncoded(c)                                                                 // 8
        saturation(c, params.saturation)                                             // 9
        hsvAdjust(c, params.skinTone, params.blueTone, params.colorMix)              // 9
        if (params.hasLook) look(c, params.look!!, params.lookIntensity, scratch)    // 10
        vignette(c, params.vignette, u, v, aspect)                                   // 11
        return output(c, alpha, gx, gy)                                              // 12
    }

    // ---- helpers shared by the stages ----

    fun lin(p: Float): Float = if (p <= 0.04045f) p / 12.92f else ((p + 0.055f) / 1.055f).pow(2.4f)

    fun enc(x: Float): Float {
        val c = max(x, 0f)
        return if (c <= 0.0031308f) 12.92f * c else 1.055f * c.pow(1f / 2.4f) - 0.055f
    }

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun luma(c: Rgb): Float = c.r * LumaR + c.g * LumaG + c.b * LumaB

    /** Linear luma of a premultiplied ARGB pixel (used for the local base). */
    fun linearLuma(argb: Int): Float {
        val alpha = argb ushr 24
        if (alpha == 0) return 0f
        val a = alpha.toFloat()
        val r = lin(min(((argb shr 16) and 0xff) / a, 1f))
        val g = lin(min(((argb shr 8) and 0xff) / a, 1f))
        val b = lin(min((argb and 0xff) / a, 1f))
        return r * LumaR + g * LumaG + b * LumaB
    }

    private fun ratioApply(c: Rgb, y: Float, newY: Float) {
        val s = newY / max(y, 1e-5f)
        c.r *= s
        c.g *= s
        c.b *= s
    }

    private fun hueDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    // ---- stages ----

    /** Stage 0: legacy tone/filter matrices baked into a LUT, applied to encoded values. */
    fun legacyGrade(c: Rgb, lut: PhotoLut, scratch: FloatArray) {
        lut.sample(c.r, c.g, c.b, scratch)
        c.r = scratch[0]
        c.g = scratch[1]
        c.b = scratch[2]
    }

    /** Stage 1. */
    fun toLinear(c: Rgb) {
        c.r = lin(c.r)
        c.g = lin(c.g)
        c.b = lin(c.b)
    }

    /** Stage 2: warmth [w] and tint [t] (positive tint is magenta). */
    fun warmthTint(c: Rgb, w: Float, t: Float) {
        if (w == 0f && t == 0f) return
        c.r *= 1f + 0.10f * w
        c.b *= 1f - 0.10f * w
        c.g *= 1f - 0.05f * t
    }

    /** Stage 3: brightness [b], a luma power curve that keeps 1.0 fixed. */
    fun brightness(c: Rgb, b: Float) {
        if (b == 0f) return
        val y = luma(c)
        ratioApply(c, y, max(y, 0f).pow(2f.pow(-0.8f * b)))
    }

    /** Stage 4: black point [k] and white point [q], per channel. */
    fun levels(c: Rgb, k: Float, q: Float) {
        if (k == 0f && q == 0f) return
        c.r = levelsChannel(c.r, k, q)
        c.g = levelsChannel(c.g, k, q)
        c.b = levelsChannel(c.b, k, q)
    }

    private fun levelsChannel(value: Float, k: Float, q: Float): Float {
        var x = value
        if (k > 0f) {
            x = max(x - 0.05f * k, 0f) / (1f - 0.05f * k)
        } else if (k < 0f) {
            val f = 0.03f * abs(k)
            x = x * (1f - f) + f
        }
        if (q > 0f) {
            x /= 1f - 0.35f * q
        } else if (q < 0f) {
            x *= 1f - 0.30f * abs(q)
        }
        return x
    }

    /** Stage 5: highlights [h] and shadows [s], weighted by the linear local base [base]. */
    fun highlightsShadows(c: Rgb, h: Float, s: Float, base: Float) {
        if (h == 0f && s == 0f) return
        val bp = enc(base)
        val ws = 1f - smoothstep(0f, 0.5f, bp)
        val wh = smoothstep(0.5f, 1f, bp)
        val gain = 2f.pow(1.2f * s * ws + 1.0f * h * wh)
        c.r *= gain
        c.g *= gain
        c.b *= gain
    }

    /** Stage 6: tone [o], local compression of the base while keeping the detail ratio. */
    fun tone(c: Rgb, o: Float, base: Float) {
        if (o == 0f) return
        val y = luma(c)
        val b = max(base, 1e-4f)
        val d = y / b
        val compressed = 2f.pow((1f - 0.5f * o) * log2(b) + 0.5f * o * log2(0.18f))
        ratioApply(c, y, compressed * d)
    }

    /** Stage 7: contrast [k] as an S-curve (or its inverse) on encoded luma. */
    fun contrast(c: Rgb, k: Float) {
        if (k == 0f) return
        val y = luma(c)
        val u = enc(y)
        // The curve is defined on 0..1; values outside (unclamped highlights) stay unchanged.
        if (u <= 0f || u >= 1f) return
        val shaped = if (k > 0f) {
            u + (u * u * (3f - 2f * u) - u) * k
        } else {
            u + (0.5f - sin(asin(1f - 2f * u) / 3f) - u) * abs(k)
        }
        ratioApply(c, y, lin(shaped))
    }

    /** Stage 8: clamp and encode. */
    fun toEncoded(c: Rgb) {
        c.r = enc(c.r.coerceIn(0f, 1f))
        c.g = enc(c.g.coerceIn(0f, 1f))
        c.b = enc(c.b.coerceIn(0f, 1f))
    }

    /** Stage 9a: saturation [a] around the encoded luma; the result is clamped for the HSV stage. */
    fun saturation(c: Rgb, a: Float) {
        if (a == 0f) return
        val yp = luma(c)
        val s = 1f + a
        c.r = (yp + (c.r - yp) * s).coerceIn(0f, 1f)
        c.g = (yp + (c.g - yp) * s).coerceIn(0f, 1f)
        c.b = (yp + (c.b - yp) * s).coerceIn(0f, 1f)
    }

    /** Stage 9b: skin tone, blue tone and the eight-band colour mix, in HSV (hue in degrees). */
    fun hsvAdjust(c: Rgb, skin: Float, blue: Float, mix: List<ColorMixBand>) {
        val hasMix = mix.any { !it.isNeutral }
        if (skin == 0f && blue == 0f && !hasMix) return
        val mx = max(c.r, max(c.g, c.b))
        val mn = min(c.r, min(c.g, c.b))
        val delta = mx - mn
        var hue = when {
            delta <= 0f -> 0f
            mx == c.r -> 60f * (((c.g - c.b) / delta) % 6f)
            mx == c.g -> 60f * ((c.b - c.r) / delta + 2f)
            else -> 60f * ((c.r - c.g) / delta + 4f)
        }
        if (hue < 0f) hue += 360f
        var sat = if (mx <= 0f) 0f else delta / mx
        // Hue is unstable near grey; fading the hue-band controls there keeps neutrals untouched.
        val chroma = smoothstep(0.02f, 0.12f, sat)
        var value = mx

        if (skin != 0f) {
            val w = smoothstep(25f, 0f, hueDistance(hue, 35f)) * chroma
            hue += 8f * skin * w
            sat *= 1f + 0.25f * skin * w
        }
        if (blue != 0f) {
            val w = smoothstep(35f, 0f, hueDistance(hue, 220f)) * chroma
            sat *= 1f + 0.6f * blue * w
            value *= 1f - 0.15f * blue * w
        }
        if (hasMix) {
            var dh = 0f
            var ds = 0f
            var dl = 0f
            for (i in mix.indices) {
                val w = max(0f, 1f - hueDistance(hue, 45f * i) / 45f) * chroma
                dh += w * mix[i].hue
                ds += w * mix[i].saturation
                dl += w * mix[i].luminance
            }
            hue += 30f * dh
            sat = (sat * (1f + ds)).coerceIn(0f, 1f)
            value *= 2f.pow(0.75f * dl)
        }
        sat = sat.coerceIn(0f, 1f)
        value = value.coerceIn(0f, 1f)
        hue = ((hue % 360f) + 360f) % 360f

        val hp = hue / 60f
        val sector = floor(hp).toInt() % 6
        val f = hp - floor(hp)
        val p = value * (1f - sat)
        val q = value * (1f - sat * f)
        val t = value * (1f - sat * (1f - f))
        when (sector) {
            0 -> { c.r = value; c.g = t; c.b = p }
            1 -> { c.r = q; c.g = value; c.b = p }
            2 -> { c.r = p; c.g = value; c.b = t }
            3 -> { c.r = p; c.g = q; c.b = value }
            4 -> { c.r = t; c.g = p; c.b = value }
            else -> { c.r = value; c.g = p; c.b = q }
        }
    }

    /** Stage 10: look LUT at [intensity] (the texture-coordinate remap equals a grid-trilinear lookup). */
    fun look(c: Rgb, lut: PhotoLut, intensity: Float, scratch: FloatArray) {
        if (intensity <= 0f) return
        lut.sample(c.r, c.g, c.b, scratch)
        c.r += (scratch[0] - c.r) * intensity
        c.g += (scratch[1] - c.g) * intensity
        c.b += (scratch[2] - c.b) * intensity
    }

    /** Stage 11: vignette [vig] from the global frame UV ([u], [v]) and frame [aspect]. */
    fun vignette(c: Rgb, vig: Float, u: Float, v: Float, aspect: Float) {
        if (vig == 0f) return
        val dx = (u - 0.5f) * aspect
        val dy = v - 0.5f
        val r = sqrt(dx * dx + dy * dy) / sqrt(0.25f * aspect * aspect + 0.25f)
        val w = smoothstep(0.35f, 1f, r)
        if (vig > 0f) {
            val k = 1f - 0.6f * vig * w
            c.r *= k
            c.g *= k
            c.b *= k
        } else {
            val k = 0.5f * abs(vig) * w
            c.r += (1f - c.r) * k
            c.g += (1f - c.g) * k
            c.b += (1f - c.b) * k
        }
    }

    /** The uint32 hash of ADR-045 stage 12, as a signed Int carrying the same bits. */
    fun hash(x: Int, y: Int): Int {
        var h = (x * 0x8da6b343.toInt()) xor (y * 0xd8163841.toInt())
        h = h xor (h ushr 16)
        h *= 0x7feb352d
        h = h xor (h ushr 15)
        h *= 0x846ca68b.toInt()
        h = h xor (h ushr 16)
        return h
    }

    /** Dither offset in encoded units, `(hash / 2^32 - 0.5) / 255`. */
    fun dither(gx: Int, gy: Int): Float =
        (((hash(gx, gy).toLong() and 0xffffffffL).toDouble() / 4294967296.0).toFloat() - 0.5f) / 255f

    /** Stage 12: clamp, dither, round to 8 bits and re-premultiply with [alpha]. */
    fun output(c: Rgb, alpha: Int, gx: Int, gy: Int): Int {
        val d = dither(gx, gy)
        val a = alpha.toFloat()
        val r = Math.round((c.r.coerceIn(0f, 1f) + d).coerceIn(0f, 1f) * a)
        val g = Math.round((c.g.coerceIn(0f, 1f) + d).coerceIn(0f, 1f) * a)
        val b = Math.round((c.b.coerceIn(0f, 1f) + d).coerceIn(0f, 1f) * a)
        return (alpha shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun log2(x: Float): Float = (kotlin.math.ln(x) / kotlin.math.ln(2f))
}
