package com.librestatic.lightforge.core.editing.image.grade

import kotlin.math.pow

/**
 * Bakes the built-in looks into 33³ LUTs from small Kotlin presets, so the app ships no binary
 * assets. All maths run on encoded sRGB 0..1.
 */
object PhotoLutBaker {
    /** A look: saturation, then lift/gamma/gain, then an S-curve, then split-toning. */
    internal class Preset(
        val saturation: Float = 1f,
        /** Per-channel black lift (0 = untouched). */
        val lift: FloatArray = floatArrayOf(0f, 0f, 0f),
        /** Per-channel gamma (>1 brightens the midtones). */
        val gamma: FloatArray = floatArrayOf(1f, 1f, 1f),
        /** Per-channel white level (1 = untouched). */
        val gain: FloatArray = floatArrayOf(1f, 1f, 1f),
        /** S-curve amount 0..1. */
        val contrast: Float = 0f,
        val shadowHue: Float = 0f,
        val shadowAmount: Float = 0f,
        val highlightHue: Float = 0f,
        val highlightAmount: Float = 0f,
        /** Desaturate fully and colour the result with [monoTintHue] at [monoTintAmount]. */
        val mono: Boolean = false,
        val monoTintHue: Float = 0f,
        val monoTintAmount: Float = 0f,
    )

    private val presets: Map<String, Preset> = linkedMapOf(
        "builtin:vivid" to Preset(saturation = 1.22f, gamma = floatArrayOf(1.05f, 1.05f, 1.05f), contrast = 0.16f),
        "builtin:natural" to Preset(saturation = 0.92f, contrast = 0.10f),
        "builtin:warm" to Preset(
            saturation = 1.05f, gain = floatArrayOf(1f, 0.97f, 0.88f), lift = floatArrayOf(0.01f, 0f, 0f),
            contrast = 0.08f, shadowHue = 28f, shadowAmount = 0.03f,
        ),
        "builtin:cool" to Preset(
            saturation = 0.98f, gain = floatArrayOf(0.90f, 0.97f, 1f), lift = floatArrayOf(0f, 0.005f, 0.02f),
            contrast = 0.08f, highlightHue = 215f, highlightAmount = 0.03f,
        ),
        "builtin:golden" to Preset(
            saturation = 1.12f, gain = floatArrayOf(1f, 0.95f, 0.78f), gamma = floatArrayOf(1.06f, 1.04f, 1f),
            contrast = 0.10f, shadowHue = 25f, shadowAmount = 0.04f, highlightHue = 45f, highlightAmount = 0.08f,
        ),
        "builtin:film" to Preset(
            saturation = 0.88f, lift = floatArrayOf(0.025f, 0.03f, 0.04f), gain = floatArrayOf(0.97f, 0.97f, 0.95f),
            contrast = 0.22f, shadowHue = 195f, shadowAmount = 0.06f, highlightHue = 42f, highlightAmount = 0.07f,
        ),
        "builtin:fade" to Preset(
            saturation = 0.82f, lift = floatArrayOf(0.075f, 0.075f, 0.08f), gain = floatArrayOf(0.96f, 0.96f, 0.96f),
            contrast = 0.04f,
        ),
        "builtin:teal_orange" to Preset(
            saturation = 1.10f, contrast = 0.20f,
            shadowHue = 190f, shadowAmount = 0.14f, highlightHue = 28f, highlightAmount = 0.14f,
        ),
        "builtin:pastel" to Preset(
            saturation = 0.68f, lift = floatArrayOf(0.07f, 0.07f, 0.075f), gamma = floatArrayOf(1.14f, 1.14f, 1.14f),
            gain = floatArrayOf(0.98f, 0.98f, 0.98f), shadowHue = 330f, shadowAmount = 0.02f,
            highlightHue = 200f, highlightAmount = 0.02f,
        ),
        "builtin:dramatic" to Preset(
            saturation = 1.08f, gamma = floatArrayOf(0.92f, 0.92f, 0.92f), contrast = 0.42f,
            shadowHue = 215f, shadowAmount = 0.04f,
        ),
        "builtin:mono" to Preset(mono = true, contrast = 0.12f),
        "builtin:noir" to Preset(
            mono = true, contrast = 0.55f, gamma = floatArrayOf(0.88f, 0.88f, 0.88f),
            gain = floatArrayOf(0.98f, 0.98f, 0.98f), monoTintHue = 220f, monoTintAmount = 0.02f,
        ),
    )

    /** Ids of every built-in look, in picker order. */
    val ids: List<String> = presets.keys.toList()

    private val cache = HashMap<String, PhotoLut>()

    /** Returns the baked 33³ LUT for [id], or null for an unknown id. Results are cached. */
    fun bake(id: String): PhotoLut? {
        val preset = presets[id] ?: return null
        return synchronized(cache) { cache.getOrPut(id) { bake(preset, PhotoLut.DefaultSize) } }
    }

    internal fun bake(preset: Preset, size: Int): PhotoLut {
        val data = FloatArray(size * size * size * 3)
        val rgb = FloatArray(3)
        var i = 0
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            rgb[0] = r / (size - 1f)
            rgb[1] = g / (size - 1f)
            rgb[2] = b / (size - 1f)
            apply(preset, rgb)
            data[i++] = rgb[0]
            data[i++] = rgb[1]
            data[i++] = rgb[2]
        }
        return PhotoLut(size, data)
    }

    private fun luma(rgb: FloatArray) = 0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2]

    private fun apply(p: Preset, rgb: FloatArray) {
        var y = luma(rgb)
        if (p.mono) {
            rgb[0] = y; rgb[1] = y; rgb[2] = y
        } else if (p.saturation != 1f) {
            for (c in 0..2) rgb[c] = (y + (rgb[c] - y) * p.saturation).coerceIn(0f, 1f)
        }
        for (c in 0..2) {
            val lifted = p.lift[c] + (p.gain[c] - p.lift[c]) * rgb[c]
            rgb[c] = lifted.coerceIn(0f, 1f).pow(1f / p.gamma[c])
        }
        if (p.contrast != 0f) {
            for (c in 0..2) {
                val v = rgb[c]
                rgb[c] = v + (v * v * (3f - 2f * v) - v) * p.contrast
            }
        }
        y = luma(rgb)
        val tint = FloatArray(3)
        if (p.mono && p.monoTintAmount > 0f) tintOffset(p.monoTintHue, p.monoTintAmount, tint, rgb)
        if (p.shadowAmount > 0f) {
            val w = 1f - PhotoGradeMath.smoothstep(0f, 0.6f, y)
            tintOffset(p.shadowHue, p.shadowAmount * w, tint, rgb)
        }
        if (p.highlightAmount > 0f) {
            val w = PhotoGradeMath.smoothstep(0.4f, 1f, y)
            tintOffset(p.highlightHue, p.highlightAmount * w, tint, rgb)
        }
        for (c in 0..2) rgb[c] = rgb[c].coerceIn(0f, 1f)
    }

    /** Adds the luma-neutral colour of [hue] scaled by [amount] to [rgb]. */
    private fun tintOffset(hue: Float, amount: Float, scratch: FloatArray, rgb: FloatArray) {
        hueToRgb(hue, scratch)
        val ty = luma(scratch)
        for (c in 0..2) rgb[c] += (scratch[c] - ty) * amount
    }

    /** Full-saturation, full-value colour of [hue] degrees. */
    internal fun hueToRgb(hue: Float, out: FloatArray) {
        val h = ((hue % 360f) + 360f) % 360f / 60f
        val offsets = floatArrayOf(5f, 3f, 1f)
        for (c in 0..2) {
            val k = (offsets[c] + h) % 6f
            out[c] = 1f - minOf(k, 4f - k, 1f).coerceAtLeast(0f)
        }
    }
}
