package com.librestatic.lightforge.feature.pdfstudio

import kotlin.math.abs

/**
 * Pure geometry for the canvas snap guides (Phase C item 5): page center, margins, and other
 * images' edges/centers, all in page-space millimeters. Kept independent of Compose so it has JVM
 * unit tests; [PdfCanvas] only draws the [GuideLine]s this returns and formats [SnapResult.deltaX]
 * / [SnapResult.deltaY] for the measurement chip.
 *
 * This is separate from the project's persisted snap grid ([PdfProject.snap], applied in
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
    fun candidates(page: PdfPage, others: List<PdfImage>): List<GuideLine> =
        candidatesFor(page, others, emptyList())

    /**
     * As [candidates], but also guiding against every other text box's edges/center (Phase G1b):
     * a dragged image or text snaps to margins, the page center, other images AND other texts,
     * since both element kinds share one page-space coordinate system.
     */
    fun candidatesFor(
        page: PdfPage,
        otherImages: List<PdfImage>,
        otherTexts: List<PdfText>,
    ): List<GuideLine> {
        val v = linkedSetOf(page.width / 2, page.margin, page.width - page.margin)
        val h = linkedSetOf(page.height / 2, page.margin, page.height - page.margin)
        fun add(x: Double, y: Double, width: Double, height: Double) {
            v += x
            v += x + width / 2
            v += x + width
            h += y
            h += y + height / 2
            h += y + height
        }
        otherImages.forEach { add(it.x, it.y, it.width, it.height) }
        otherTexts.forEach { add(it.x, it.y, it.width, it.height) }
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

    /**
     * Single pure resolution step for a drag-to-move gesture, shared by the canvas's live guide
     * overlay/measurement chip and its onDragEnd commit so the two can never disagree: the
     * project's snap grid (when [grid] is non-null, i.e. the project's Snap setting is on)
     * rounds the candidate position first, then guides snap within [thresholdMm] regardless of
     * that setting.
     */
    fun resolveDrag(
        candidateX: Double,
        candidateY: Double,
        width: Double,
        height: Double,
        candidates: List<GuideLine>,
        grid: PdfSnapGrid? = null,
        thresholdMm: Double = 2.0,
    ): SnapResult {
        val gridX = if (grid != null) grid.roundX(candidateX) else candidateX
        val gridY = if (grid != null) grid.roundY(candidateY) else candidateY
        return snap(gridX, gridY, width, height, candidates, thresholdMm)
    }
}

/**
 * The project's snap grid in millimeters, measured from the paper's top-left corner: positions
 * round to a multiple of [xMm] horizontally and [yMm] vertically (independent sizes, e.g. 30 x 20).
 */
data class PdfSnapGrid(val xMm: Double, val yMm: Double) {
    init {
        require(xMm.isFinite() && xMm > 0 && yMm.isFinite() && yMm > 0)
    }

    fun roundX(x: Double): Double = kotlin.math.round(x / xMm) * xMm

    fun roundY(y: Double): Double = kotlin.math.round(y / yMm) * yMm

    /** Interior grid line positions (mm from the paper's top-left) every [stepMm] along an axis of
     * [extentMm]; the paper's own edges are not repeated. */
    fun linePositions(stepMm: Double, extentMm: Double): List<Double> {
        if (!(stepMm > 0) || !extentMm.isFinite()) return emptyList()
        val out = ArrayList<Double>()
        var k = 1
        while (k * stepMm < extentMm - 1e-6) {
            out.add(k * stepMm)
            k++
        }
        return out
    }

    companion object {
        const val DEFAULT_MM = 5.0

        /** Smallest grid side the UI offers. */
        const val MIN_MM = 1.0
    }
}
