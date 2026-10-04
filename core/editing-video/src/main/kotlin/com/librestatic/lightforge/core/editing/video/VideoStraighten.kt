@file:Suppress("UnsafeOptInUsageError")

package com.librestatic.lightforge.core.editing.video

import android.graphics.Matrix
import androidx.media3.common.util.Size
import androidx.media3.effect.MatrixTransformation
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Straightening, like Google Photos: a rotation splits into whole quarter turns, which swap the
 * frame's sides, and a small remaining angle that keeps the frame size and zooms in just enough to
 * hide the corners the tilt would otherwise expose.
 */
object VideoStraighten {
    /**
     * The whole quarter turns in [rotationDegrees], as a multiple of 90. A half turn (exactly 45 degrees
     * past a quarter) rounds towards zero, so a straighten slider reaching +45 or -45 stays on its
     * quarter turn instead of jumping to the next one and changing the output size.
     */
    fun quarterTurnDegrees(rotationDegrees: Float): Float {
        val turns = ceil(abs(rotationDegrees) / 90f - 0.5f)
        return (if (rotationDegrees < 0f) -turns else turns) * 90f
    }

    /** The straighten angle left after the quarter turns, in -45..45 degrees. */
    fun fineDegrees(rotationDegrees: Float): Float = rotationDegrees - quarterTurnDegrees(rotationDegrees)

    /** Zoom for a [width]×[height] frame tilted by [degrees] to cover a frame of the same size. */
    fun coverScale(degrees: Float, width: Float, height: Float): Float {
        if (degrees == 0f || width <= 0f || height <= 0f) return 1f
        val radians = Math.toRadians(abs(degrees).toDouble())
        val ratio = max(width / height, height / width)
        return (cos(radians) + ratio * sin(radians)).toFloat()
    }
}

/** Tilts frames counter-clockwise by [degrees] and zooms them to fill, keeping the frame size. */
internal class StraightenTransformation(private val degrees: Float) : MatrixTransformation {
    private var matrix = Matrix()

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        val scale = VideoStraighten.coverScale(degrees, inputWidth.toFloat(), inputHeight.toFloat())
        // Rotate in pixel space so non-square frames keep their proportions, then return to NDC.
        matrix = Matrix().apply {
            setScale(inputWidth / 2f, inputHeight / 2f)
            postRotate(degrees)
            postScale(scale, scale)
            postScale(2f / inputWidth, 2f / inputHeight)
        }
        return Size(inputWidth, inputHeight)
    }

    override fun getMatrix(presentationTimeUs: Long): Matrix = matrix

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = degrees == 0f
}
