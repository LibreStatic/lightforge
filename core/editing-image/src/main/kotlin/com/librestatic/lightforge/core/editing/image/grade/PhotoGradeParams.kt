package com.librestatic.lightforge.core.editing.image.grade

import com.librestatic.lightforge.core.editing.image.PhotoColorTransform
import com.librestatic.lightforge.core.model.AdjustKey
import com.librestatic.lightforge.core.model.ColorMixBand
import com.librestatic.lightforge.core.model.EditOperation

/**
 * Resolved inputs of the ADR-045 colour grade: every adjust value (-1..1), the eight colour-mix
 * bands, the optional look LUT and the baked legacy tone/filter LUT.
 */
class PhotoGradeParams(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val tone: Float = 0f,
    val whitePoint: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val blackPoint: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val tint: Float = 0f,
    val skinTone: Float = 0f,
    val blueTone: Float = 0f,
    val vignette: Float = 0f,
    val colorMix: List<ColorMixBand> = List(BandCount) { ColorMixBand() },
    val look: PhotoLut? = null,
    val lookIntensity: Float = 1f,
    val legacyLut: PhotoLut? = null,
    /** Id of a [EditOperation.Look] whose LUT could not be resolved; it was skipped. */
    val missingLookId: String? = null,
) {
    init { require(colorMix.size == BandCount) }

    val hasLook: Boolean get() = look != null && lookIntensity > 0f
    val hasColorMix: Boolean get() = colorMix.any { !it.isNeutral }

    /** True when grading would leave the pixels unchanged, so callers can skip the grader. */
    val isIdentity: Boolean
        get() = brightness == 0f && contrast == 0f && tone == 0f && whitePoint == 0f && highlights == 0f &&
            shadows == 0f && blackPoint == 0f && saturation == 0f && warmth == 0f && tint == 0f &&
            skinTone == 0f && blueTone == 0f && vignette == 0f && !hasColorMix && !hasLook &&
            legacyLut == null

    /** Highlights, shadows and tone read the blurred local luma base. */
    val needsLocalBase: Boolean get() = highlights != 0f || shadows != 0f || tone != 0f

    companion object {
        const val BandCount = 8

        /**
         * Resolves the colour operations of [operations]. The last Adjust, ColorMix and Look win;
         * every legacy Tone/Filter is baked, in order, into [legacyLut]. A look that
         * [lookResolver] cannot resolve is skipped and reported through [missingLookId].
         */
        fun from(operations: List<EditOperation>, lookResolver: (String) -> PhotoLut?): PhotoGradeParams {
            val adjust = operations.filterIsInstance<EditOperation.Adjust>().lastOrNull()
            val mix = operations.filterIsInstance<EditOperation.ColorMix>().lastOrNull()
            val lookOp = operations.filterIsInstance<EditOperation.Look>().lastOrNull()
            val lut = lookOp?.let { lookResolver(it.id) }
            val legacy = operations.filter { it is EditOperation.Tone || it is EditOperation.Filter }
            fun value(key: AdjustKey) = adjust?.get(key) ?: 0f
            return PhotoGradeParams(
                brightness = value(AdjustKey.Brightness),
                contrast = value(AdjustKey.Contrast),
                tone = value(AdjustKey.Tone),
                whitePoint = value(AdjustKey.WhitePoint),
                highlights = value(AdjustKey.Highlights),
                shadows = value(AdjustKey.Shadows),
                blackPoint = value(AdjustKey.BlackPoint),
                saturation = value(AdjustKey.Saturation),
                warmth = value(AdjustKey.Warmth),
                tint = value(AdjustKey.Tint),
                skinTone = value(AdjustKey.SkinTone),
                blueTone = value(AdjustKey.BlueTone),
                vignette = value(AdjustKey.Vignette),
                colorMix = mix?.bands ?: List(BandCount) { ColorMixBand() },
                look = lut,
                lookIntensity = lookOp?.intensity ?: 1f,
                legacyLut = bakeLegacy(legacy),
                missingLookId = if (lookOp != null && lut == null) lookOp.id else null,
            )
        }

        /**
         * Applies each legacy matrix in the 0..255 domain, clamping after every one like
         * `ColorMatrixColorFilter` does, at each grid point (ADR-045 stage 0).
         */
        internal fun bakeLegacy(operations: List<EditOperation>, size: Int = PhotoLut.DefaultSize): PhotoLut? {
            val matrices = operations.map { PhotoColorTransform.matrixValues(it) }.filterNot { it.isIdentityMatrix() }
            if (matrices.isEmpty()) return null
            val data = FloatArray(size * size * size * 3)
            var i = 0
            val rgb = FloatArray(3)
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                rgb[0] = r * 255f / (size - 1)
                rgb[1] = g * 255f / (size - 1)
                rgb[2] = b * 255f / (size - 1)
                matrices.forEach { applyMatrix(it, rgb) }
                data[i++] = rgb[0] / 255f
                data[i++] = rgb[1] / 255f
                data[i++] = rgb[2] / 255f
            }
            return PhotoLut(size, data)
        }

        /** One ColorMatrix pass on an opaque 0..255 colour, clamped to 0..255. */
        internal fun applyMatrix(m: FloatArray, rgb: FloatArray) {
            val r = rgb[0]
            val g = rgb[1]
            val b = rgb[2]
            for (row in 0 until 3) {
                val v = m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * b + m[row * 5 + 3] * 255f + m[row * 5 + 4]
                rgb[row] = v.coerceIn(0f, 255f)
            }
        }

        private fun FloatArray.isIdentityMatrix(): Boolean {
            for (i in 0 until 20) {
                val expected = if (i % 6 == 0) 1f else 0f
                if (this[i] != expected) return false
            }
            return true
        }
    }
}
