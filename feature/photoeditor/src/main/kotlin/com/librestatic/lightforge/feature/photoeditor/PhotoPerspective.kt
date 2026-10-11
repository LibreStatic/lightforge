package com.librestatic.lightforge.feature.photoeditor

import com.librestatic.lightforge.core.model.EditOperation
import com.librestatic.lightforge.core.model.NormalizedPoint

// Pure geometry for the perspective tool. Corners are the source quad (top-left, top-right,
// bottom-right, bottom-left of the output) in the frame after Rotate/Flip, 0..1, y pointing down.

/** How far the keystone sliders may pull an edge in at full travel, as a share of the frame. */
internal const val KEYSTONE_MAX_INSET = 0.25f

/** Smallest turn (edge cross product) a dragged quad may keep; below it the quad is nearly flat. */
internal const val MIN_QUAD_CROSS = 0.004f

internal val IdentityCorners: List<NormalizedPoint> get() = EditOperation.Perspective.Identity

/** True when every turn has the sign [EditOperation.Perspective] requires and is not nearly flat. */
internal fun isConvexQuad(corners: List<NormalizedPoint>, margin: Float = MIN_QUAD_CROSS): Boolean {
    if (corners.size != 4) return false
    for (i in 0 until 4) {
        val a = corners[i]
        val b = corners[(i + 1) % 4]
        val c = corners[(i + 2) % 4]
        if ((b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x) <= margin) return false
    }
    return true
}

/**
 * Moves corner [index] toward [target]. A drag that would make the quad concave or degenerate
 * stops where the quad is still valid, so the corner slides along the allowed boundary.
 */
internal fun movePerspectiveCorner(
    corners: List<NormalizedPoint>,
    index: Int,
    target: NormalizedPoint,
): List<NormalizedPoint> {
    val goal = NormalizedPoint(target.x.coerceIn(0f, 1f), target.y.coerceIn(0f, 1f))
    fun with(point: NormalizedPoint) = corners.toMutableList().also { it[index] = point }
    if (isConvexQuad(with(goal))) return with(goal)
    val from = corners[index]
    var low = 0f
    var high = 1f
    repeat(18) {
        val mid = (low + high) / 2f
        val candidate = NormalizedPoint(from.x + (goal.x - from.x) * mid, from.y + (goal.y - from.y) * mid)
        if (isConvexQuad(with(candidate))) low = mid else high = mid
    }
    return with(NormalizedPoint(from.x + (goal.x - from.x) * low, from.y + (goal.y - from.y) * low))
}

private fun topInset(c: List<NormalizedPoint>) = (c[0].x + 1f - c[1].x) / 2f
private fun bottomInset(c: List<NormalizedPoint>) = (c[3].x + 1f - c[2].x) / 2f
private fun leftInset(c: List<NormalizedPoint>) = (c[0].y + 1f - c[3].y) / 2f
private fun rightInset(c: List<NormalizedPoint>) = (c[1].y + 1f - c[2].y) / 2f

/**
 * Vertical keystone in -1..1: positive pulls the top edge in (fixes converging verticals when
 * shooting up), negative pulls the bottom edge in.
 */
internal fun verticalKeystone(corners: List<NormalizedPoint>): Float =
    ((topInset(corners) - bottomInset(corners)) / KEYSTONE_MAX_INSET).coerceIn(-1f, 1f)

/** Horizontal keystone in -1..1: positive pulls the left edge in, negative the right edge. */
internal fun horizontalKeystone(corners: List<NormalizedPoint>): Float =
    ((leftInset(corners) - rightInset(corners)) / KEYSTONE_MAX_INSET).coerceIn(-1f, 1f)

/** Moves the corners so [verticalKeystone] reads [value]; the less inset edge stays where it is. */
internal fun withVerticalKeystone(corners: List<NormalizedPoint>, value: Float): List<NormalizedPoint> {
    val difference = value.coerceIn(-1f, 1f) * KEYSTONE_MAX_INSET
    val top = topInset(corners)
    val bottom = bottomInset(corners)
    val base = minOf(top, bottom)
    val dTop = (if (difference >= 0f) base + difference else base) - top
    val dBottom = (if (difference >= 0f) base else base - difference) - bottom
    return validated(
        corners,
        listOf(
            corners[0].x + dTop to corners[0].y,
            corners[1].x - dTop to corners[1].y,
            corners[2].x - dBottom to corners[2].y,
            corners[3].x + dBottom to corners[3].y,
        ),
    )
}

/** Moves the corners so [horizontalKeystone] reads [value]; the less inset edge stays where it is. */
internal fun withHorizontalKeystone(corners: List<NormalizedPoint>, value: Float): List<NormalizedPoint> {
    val difference = value.coerceIn(-1f, 1f) * KEYSTONE_MAX_INSET
    val left = leftInset(corners)
    val right = rightInset(corners)
    val base = minOf(left, right)
    val dLeft = (if (difference >= 0f) base + difference else base) - left
    val dRight = (if (difference >= 0f) base else base - difference) - right
    return validated(
        corners,
        listOf(
            corners[0].x to corners[0].y + dLeft,
            corners[1].x to corners[1].y + dRight,
            corners[2].x to corners[2].y - dRight,
            corners[3].x to corners[3].y - dLeft,
        ),
    )
}

/** Raw coordinates may sit a rounding step outside 0..1 (which [NormalizedPoint] rejects): snap them, then check. */
private fun validated(original: List<NormalizedPoint>, raw: List<Pair<Float, Float>>): List<NormalizedPoint> {
    val tolerance = 1e-4f
    if (raw.any { (x, y) -> x < -tolerance || x > 1f + tolerance || y < -tolerance || y > 1f + tolerance }) return original
    val proposed = raw.map { (x, y) -> NormalizedPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)) }
    return if (isConvexQuad(proposed)) proposed else original
}

/** The same quad after the frame is rotated a quarter turn clockwise; corner order follows the output. */
internal fun List<NormalizedPoint>.rotatedClockwise(): List<NormalizedPoint> =
    remapped { NormalizedPoint(1f - it.y, it.x) }

/** The same quad after the frame is mirrored. */
internal fun List<NormalizedPoint>.flipped(horizontal: Boolean): List<NormalizedPoint> =
    remapped { if (horizontal) NormalizedPoint(1f - it.x, it.y) else NormalizedPoint(it.x, 1f - it.y) }

private fun List<NormalizedPoint>.remapped(map: (NormalizedPoint) -> NormalizedPoint): List<NormalizedPoint> {
    val rect = IdentityCorners
    val result = arrayOfNulls<NormalizedPoint>(4)
    forEachIndexed { index, corner -> result[rect.indexOf(map(rect[index]))] = map(corner) }
    return result.map { it!! }
}
