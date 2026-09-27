package com.ugallery.feature.pdfstudio

import kotlin.math.abs

/**
 * Pure geometry for the canvas snap guides (Phase C item 5): page center, margins, and other
 * images' edges/centers, all in page-space millimeters. Kept independent of Compose so it has JVM
 * unit tests; [PdfCanvas] only draws the [GuideLine]s this returns and formats [SnapResult.deltaX]
 * / [SnapResult.deltaY] for the measurement chip.
 *
 * This is separate from the project's persisted 5 mm grid ([PdfProject.snap], applied in
 * [PdfStudioViewModel.moveImage]): guides snap within [snap]'s threshold regardless of that
 * setting, matching the plan's "guides snap within threshold regardless."
 */
object PdfSnapGuides {
    enum class Orientation {
        Vertical,
        Horizontal,
    }

    data class GuideLine(val orientation: Orientation, val position: Double)

    data class SnapResult(
        val x: Double,
        val y: Double,
        val vertical: GuideLine? = null,
        val horizontal: GuideLine? = null,
    )

    /** Candidate lines: page center and margins, plus every other image's edges and center. */
    fun candidates(page: PdfPage, others: List<PdfImage>): List<GuideLine> {
        val v = linkedSetOf(page.width / 2, page.margin, page.width - page.margin)
        val h = linkedSetOf(page.height / 2, page.margin, page.height - page.margin)
        others.forEach { i ->
            v += i.x
            v += i.x + i.width / 2
            v += i.x + i.width
            h += i.y
            h += i.y + i.height / 2
            h += i.y + i.height
        }
        return v.map { GuideLine(Orientation.Vertical, it) } +
            h.map { GuideLine(Orientation.Horizontal, it) }
    }

    /**
     * Snaps a candidate top-left [x]/[y] for an image of the given [width]/[height] against
     * [candidates], trying the left/center/right edge (resp. top/middle/bottom) against every
     * guide and keeping the closest match within [thresholdMm]. Returns the possibly-adjusted
     * position plus which guide (if any) is now active per axis, for drawing.
     */
    fun snap(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        candidates: List<GuideLine>,
        thresholdMm: Double = 2.0,
    ): SnapResult {
        var bestV: Pair<GuideLine, Double>? = null
        var bestH: Pair<GuideLine, Double>? = null
        for (g in candidates) {
            if (g.orientation == Orientation.Vertical) {
                for (edge in doubleArrayOf(x, x + width / 2, x + width)) {
                    val diff = g.position - edge
                    val current = bestV
                    if (abs(diff) <= thresholdMm && (current == null || abs(diff) < abs(current.second)))
                        bestV = g to diff
                }
            } else {
                for (edge in doubleArrayOf(y, y + height / 2, y + height)) {
                    val diff = g.position - edge
                    val current = bestH
                    if (abs(diff) <= thresholdMm && (current == null || abs(diff) < abs(current.second)))
                        bestH = g to diff
                }
            }
        }
        return SnapResult(
            x = x + (bestV?.second ?: 0.0),
            y = y + (bestH?.second ?: 0.0),
            vertical = bestV?.first,
            horizontal = bestH?.first,
        )
    }

    /** Snaps [image]'s top-left against the page's center/margins and every image in [others]. */
    fun snap(image: PdfImage, page: PdfPage, others: List<PdfImage>, thresholdMm: Double = 2.0): SnapResult =
        snap(image.x, image.y, image.width, image.height, candidates(page, others), thresholdMm)
}
