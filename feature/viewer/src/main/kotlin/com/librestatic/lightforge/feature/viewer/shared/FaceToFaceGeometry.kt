package com.librestatic.lightforge.feature.viewer.shared

import androidx.compose.ui.geometry.Rect

/** Direction of the line that splits the window: [Vertical] gives left and right halves. */
enum class SplitAxis {
    Vertical,
    Horizontal;

    val other: SplitAxis get() = if (this == Vertical) Horizontal else Vertical
}

/**
 * A separating fold, in window pixels. [start] and [end] span the hinge along the axis it splits:
 * x for a [SplitAxis.Vertical] hinge, y for a [SplitAxis.Horizontal] one.
 */
data class FaceToFaceFold(val axis: SplitAxis, val start: Float, val end: Float)

/** One half of the split window and the clockwise turn that points its content top at the split. */
data class FaceToFaceHalf(val bounds: Rect, val rotationDegrees: Int)

/** [first] is the left or top half, [second] the right or bottom one. */
data class FaceToFaceLayout(val axis: SplitAxis, val first: FaceToFaceHalf, val second: FaceToFaceHalf)

/**
 * Splits a [width] x [height] window for two people facing each other across it. The top of each
 * half's content points at the split line: with a vertical split the left half turns 90 degrees
 * clockwise and the right half 270; with a horizontal split the top half turns 180 and the bottom
 * one stays upright. The hinge gap of [fold] belongs to neither half. Without a usable fold, or
 * when [axisOverride] asks for the other axis, the window splits at the midpoint of that axis; the
 * default axis is the longer one.
 */
fun faceToFaceLayout(
    width: Float,
    height: Float,
    fold: FaceToFaceFold? = null,
    axisOverride: SplitAxis? = null,
): FaceToFaceLayout {
    val axis = axisOverride ?: fold?.axis ?: if (width >= height) SplitAxis.Vertical else SplitAxis.Horizontal
    val extent = if (axis == SplitAxis.Vertical) width else height
    val usable = fold?.takeIf { it.axis == axis }
    val start = usable?.start?.coerceIn(0f, extent) ?: (extent / 2f)
    val end = usable?.end?.coerceIn(start, extent) ?: start
    return when (axis) {
        SplitAxis.Vertical -> FaceToFaceLayout(
            axis,
            FaceToFaceHalf(Rect(0f, 0f, start, height), 90),
            FaceToFaceHalf(Rect(end, 0f, width, height), 270),
        )
        SplitAxis.Horizontal -> FaceToFaceLayout(
            axis,
            FaceToFaceHalf(Rect(0f, 0f, width, start), 180),
            FaceToFaceHalf(Rect(0f, end, width, height), 0),
        )
    }
}
