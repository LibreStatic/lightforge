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
}
