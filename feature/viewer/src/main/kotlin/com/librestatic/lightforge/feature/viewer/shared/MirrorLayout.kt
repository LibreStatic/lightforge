package com.librestatic.lightforge.feature.viewer.shared

/**
 * Pure geometry for drawing one video frame onto a mirrored output.
 *
 * The frame is rotated clockwise by [rotationDegrees] on screen, then scaled uniformly to fit the
 * output (letterboxed). [contentWidth] x [contentHeight] is the upright display size of the video,
 * i.e. with the file's own rotation metadata and pixel aspect ratio already applied.
 */
internal object MirrorLayout {
    /**
     * Column-major 4x4 matrix (OpenGL convention) mapping the unit quad (-1..1 on both axes, y up)
     * to the fitted, rotated frame in normalized device coordinates of an output of
     * [outputWidth] x [outputHeight] pixels.
     */
    fun quadMatrix(
        outputWidth: Int,
        outputHeight: Int,
        contentWidth: Float,
        contentHeight: Float,
        rotationDegrees: Int,
    ): FloatArray {
        val quarterTurns = Math.floorMod(rotationDegrees / 90, 4)
        val swapped = quarterTurns % 2 == 1
        val rotatedWidth = if (swapped) contentHeight else contentWidth
        val rotatedHeight = if (swapped) contentWidth else contentHeight
        val scale = minOf(outputWidth / rotatedWidth, outputHeight / rotatedHeight)
        // Half extents of the unrotated frame, in output pixels.
        val halfWidth = contentWidth * scale / 2f
        val halfHeight = contentHeight * scale / 2f
        // Clockwise on screen with y up is a negative mathematical angle.
        val cos = intArrayOf(1, 0, -1, 0)[quarterTurns].toFloat()
        val sin = intArrayOf(0, -1, 0, 1)[quarterTurns].toFloat()
        val toNdcX = 2f / outputWidth
        val toNdcY = 2f / outputHeight
        return floatArrayOf(
            cos * halfWidth * toNdcX, sin * halfWidth * toNdcY, 0f, 0f,
            -sin * halfHeight * toNdcX, cos * halfHeight * toNdcY, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
    }
}
