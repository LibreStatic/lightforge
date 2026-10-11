package com.librestatic.lightforge.core.editing.image

import android.graphics.ColorMatrix
import com.librestatic.lightforge.core.model.EditOperation

/** Shared color transform used by the interactive preview and bitmap render/export paths. */
object PhotoColorTransform {
    fun matrixFor(operation: EditOperation): ColorMatrix = when (operation) {
        is EditOperation.Tone -> toneMatrix(operation)
        is EditOperation.Filter -> filterMatrix(operation.name)
        else -> ColorMatrix()
    }

    /**
     * The 4x5 matrix of [operation] as plain row-major values (identity for non-colour operations).
     * Pure Kotlin so JVM code can bake the legacy grade without `android.graphics`.
     */
    fun matrixValues(operation: EditOperation): FloatArray = when (operation) {
        is EditOperation.Tone -> toneValues(operation)
        is EditOperation.Filter -> filterValues(operation.name)
        else -> identityValues()
    }

    fun combinedValues(operations: List<EditOperation>): FloatArray {
        val combined = ColorMatrix()
        operations.forEach { operation ->
            if (operation is EditOperation.Tone || operation is EditOperation.Filter) {
                combined.postConcat(matrixFor(operation))
            }
        }
        return combined.array.copyOf()
    }

    private fun toneMatrix(operation: EditOperation.Tone): ColorMatrix {
        val contrast = operation.contrast
        val translate = (1f - contrast) * 127.5f + operation.brightness * 255f
        val matrix = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f,
        ))
        if (operation.saturation != 1f) {
            matrix.postConcat(ColorMatrix().apply { setSaturation(operation.saturation) })
        }
        return matrix
    }

    private fun filterMatrix(name: String): ColorMatrix = when (name) {
        "natural" -> toneMatrix(EditOperation.Tone(contrast = 1.08f, saturation = 0.9f))
        "vivid" -> toneMatrix(EditOperation.Tone(brightness = 0.03f, saturation = 1.2f))
        "mono" -> ColorMatrix().apply { setSaturation(0f) }
        else -> ColorMatrix()
    }

    // Pure mirrors of the matrices above (same maths as ColorMatrix.setSaturation / postConcat).
    private fun identityValues() = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    private fun saturationValues(sat: Float): FloatArray {
        val inv = 1f - sat
        val r = 0.213f * inv
        val g = 0.715f * inv
        val b = 0.072f * inv
        return floatArrayOf(
            r + sat, g, b, 0f, 0f,
            r, g + sat, b, 0f, 0f,
            r, g, b + sat, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** `after * before` for 4x5 matrices, like `ColorMatrix.setConcat(after, before)`. */
    private fun concat(after: FloatArray, before: FloatArray): FloatArray {
        val out = FloatArray(20)
        for (row in 0 until 4) {
            for (col in 0 until 5) {
                var sum = if (col == 4) after[row * 5 + 4] else 0f
                for (k in 0 until 4) sum += after[row * 5 + k] * before[k * 5 + col]
                out[row * 5 + col] = sum
            }
        }
        return out
    }

    private fun toneValues(operation: EditOperation.Tone): FloatArray {
        val contrast = operation.contrast
        val translate = (1f - contrast) * 127.5f + operation.brightness * 255f
        val values = floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f,
        )
        return if (operation.saturation != 1f) concat(saturationValues(operation.saturation), values) else values
    }

    private fun filterValues(name: String): FloatArray = when (name) {
        "natural" -> toneValues(EditOperation.Tone(contrast = 1.08f, saturation = 0.9f))
        "vivid" -> toneValues(EditOperation.Tone(brightness = 0.03f, saturation = 1.2f))
        "mono" -> saturationValues(0f)
        else -> identityValues()
    }
}
