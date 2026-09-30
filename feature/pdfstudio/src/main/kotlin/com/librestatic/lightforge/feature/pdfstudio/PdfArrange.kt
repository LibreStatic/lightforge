package com.librestatic.lightforge.feature.pdfstudio

/**
 * Pure geometry for the canvas's multi-selection group operations (Phase G2): the group's
 * bounding box, per-member deltas for Align/Distribute relative to that box, and clamping a group
 * drag/nudge so every member stays on the page. Kept independent of [PdfStudioViewModel] (which
 * only maps element ids to/from [Bounds] and applies the returned deltas) so this has plain JVM
 * unit tests, same spirit as [PdfSnapGuides].
 *
 * Every function here is id-agnostic: an image and a text look identical once reduced to
 * [Bounds], so mixed image+text selections need no special-casing.
 */
object PdfArrange {
    data class Bounds(val x: Double, val y: Double, val width: Double, val height: Double) {
        val right: Double get() = x + width
        val bottom: Double get() = y + height
        val centerX: Double get() = x + width / 2
        val centerY: Double get() = y + height / 2
    }

    fun boundsOf(image: PdfImage): Bounds = Bounds(image.x, image.y, image.width, image.height)

    fun boundsOf(text: PdfText): Bounds = Bounds(text.x, text.y, text.width, text.height)

    /** The smallest axis-aligned box containing every one of [boxes]. Requires at least one. */
    fun union(boxes: List<Bounds>): Bounds {
        require(boxes.isNotEmpty())
        val left = boxes.minOf { it.x }
        val top = boxes.minOf { it.y }
        val right = boxes.maxOf { it.right }
        val bottom = boxes.maxOf { it.bottom }
        return Bounds(left, top, right - left, bottom - top)
    }

    /**
     * The (dx, dy) to add to a member with [memberBounds] so it aligns to [align] *within*
     * [groupBounds] — e.g. [PdfGeometry.Align.Left] moves every member's left edge to the group's
     * left edge, [PdfGeometry.Align.Center] centers each member on the group's horizontal midline.
     * Only one axis moves per [align] value (Left/Center/Right affect x; Top/Middle/Bottom affect
     * y), matching [PdfGeometry.align]'s per-axis behavior.
     */
    fun alignDelta(memberBounds: Bounds, groupBounds: Bounds, align: PdfGeometry.Align): Pair<Double, Double> =
        when (align) {
            PdfGeometry.Align.Left -> (groupBounds.x - memberBounds.x) to 0.0
            PdfGeometry.Align.Center -> (groupBounds.centerX - memberBounds.centerX) to 0.0
            PdfGeometry.Align.Right -> (groupBounds.right - memberBounds.right) to 0.0
            PdfGeometry.Align.Top -> 0.0 to (groupBounds.y - memberBounds.y)
            PdfGeometry.Align.Middle -> 0.0 to (groupBounds.centerY - memberBounds.centerY)
            PdfGeometry.Align.Bottom -> 0.0 to (groupBounds.bottom - memberBounds.bottom)
        }

    /**
     * Equal-gap distribution along one axis (Distribute horizontally/vertically, needs >= 3
     * members): the first and last member by [selector] (left/top edge) stay put, and every
     * member in between is spaced so the gaps between consecutive members' edges are equal. Ties
     * in [selector] keep the input's own relative order (a stable sort), so two members starting
     * at the exact same position never swap places non-deterministically. Returns an id -> new
     * leading-edge-coordinate map for every member EXCEPT the first and last (whose coordinate is
     * unchanged, so callers can skip them) — an empty map if there are fewer than 3 members.
     */
    private fun distribute(
        members: List<Pair<String, Bounds>>,
        selector: (Bounds) -> Double,
        size: (Bounds) -> Double,
    ): Map<String, Double> {
        if (members.size < 3) return emptyMap()
        val sorted = members.sortedBy { selector(it.second) }
        val first = sorted.first()
        val last = sorted.last()
        val span = (selector(last.second) + size(last.second)) - selector(first.second)
        val totalSize = sorted.sumOf { size(it.second) }
        val gapCount = sorted.size - 1
        val gap = (span - totalSize) / gapCount
        var cursor = selector(first.second) + size(first.second) + gap
        val result = LinkedHashMap<String, Double>()
        for (i in 1 until sorted.size - 1) {
            val (id, bounds) = sorted[i]
            result[id] = cursor
            cursor += size(bounds) + gap
        }
        return result
    }

    /** As [distribute], spacing left edges horizontally (equal gaps between consecutive boxes). */
    fun distributeHorizontal(members: List<Pair<String, Bounds>>): Map<String, Double> =
        distribute(members, Bounds::x, Bounds::width)

    /** As [distribute], spacing top edges vertically (equal gaps between consecutive boxes). */
    fun distributeVertical(members: List<Pair<String, Bounds>>): Map<String, Double> =
        distribute(members, Bounds::y, Bounds::height)

    /**
     * Clamps a proposed group move ([dx], [dy]) so that EVERY member in [memberBounds] stays
     * within the physical page ([pageWidth] x [pageHeight]) — the same "margins are a guide, not a
     * wall" rule [PdfGeometry.constrainToPage] uses for a single element. Each axis is clamped
     * independently (a drag that would push one member off the right edge while another still has
     * room below is clamped on x only, not stopped on y too), by intersecting every member's own
     * allowed delta range.
     */
    fun clampGroupMove(
        dx: Double,
        dy: Double,
        memberBounds: List<Bounds>,
        pageWidth: Double,
        pageHeight: Double,
    ): Pair<Double, Double> {
        if (memberBounds.isEmpty()) return dx to dy
        var minDx = Double.NEGATIVE_INFINITY
        var maxDx = Double.POSITIVE_INFINITY
        var minDy = Double.NEGATIVE_INFINITY
        var maxDy = Double.POSITIVE_INFINITY
        for (b in memberBounds) {
            minDx = maxOf(minDx, -b.x)
            maxDx = minOf(maxDx, pageWidth - b.right)
            minDy = maxOf(minDy, -b.y)
            maxDy = minOf(maxDy, pageHeight - b.bottom)
        }
        // A member wider/taller than the page (shouldn't normally happen post-validate, but stay
        // safe): the min/max can cross, in which case clamp to the single point that keeps that
        // member's own reference edge on-page rather than producing NaN/an inverted range.
        val clampedDx = dx.coerceIn(minOf(minDx, maxDx), maxOf(minDx, maxDx))
        val clampedDy = dy.coerceIn(minOf(minDy, maxDy), maxOf(minDy, maxDy))
        return clampedDx to clampedDy
    }

    /** The union of [imageBounds] and [textBounds] — the group's overall bounding box, used both
     * for group Align's reference frame and for drawing the group outline. */
    fun groupBounds(imageBounds: List<Bounds>, textBounds: List<Bounds>): Bounds =
        union(imageBounds + textBounds)
}
