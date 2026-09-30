package com.librestatic.lightforge.feature.pdfstudio

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A photo print size on a sheet (feedback item B): the physical size of each printed photo,
 * independent of the paper it is printed on. `null` (no [PdfPrintSize]) means "Free grid" — the
 * pre-existing columns/photos-per-page behavior ([PdfGeometry.grid]).
 *
 * [widthMm]/[heightMm] are the portrait dimensions; [PdfPrintLayout.fit] tries both orientations
 * of the slot against the paper and picks whichever fits more slots.
 */
enum class PdfPrintSize(val id: String, val widthMm: Double, val heightMm: Double) {
    Wallet6x9("wallet_6x9", 60.0, 90.0),
    Print9x13("9x13", 90.0, 130.0),
    Print10x15("10x15", 100.0, 150.0),
    Print13x18("13x18", 130.0, 180.0),
    Print15x20("15x20", 150.0, 200.0);

    companion object {
        fun fromId(id: String?): PdfPrintSize? = id?.let { candidate -> entries.firstOrNull { it.id == candidate } }
    }
}

/**
 * The slot-fitting algorithm behind feedback item B: "the quantity of photos per page is
 * COMPUTED, not fixed by the template". Given a paper size and a photo print size, computes the
 * maximum number of exact-size slots that fit inside the printable area (paper minus margins)
 * with a gap between slots, trying both slot orientations (the print size portrait or rotated
 * 90°) and picking whichever fits more — a tie is broken in favor of the orientation matching the
 * paper's own orientation, matching how a print shop / Windows Photo Printing lays out multiple
 * same-size prints on one sheet.
 */
object PdfPrintLayout {
    /** A resolved slot grid for one page: [columns] x [rows] slots, each exactly
     * [slotWidthMm] x [slotHeightMm] (already oriented — [rotated] just records whether that is
     * the print size's own portrait dimensions or its 90°-rotated ones, for UI/labeling). */
    data class SlotFit(
        val columns: Int,
        val rows: Int,
        val slotWidthMm: Double,
        val slotHeightMm: Double,
        val rotated: Boolean,
    ) {
        init {
            require(columns >= 0 && rows >= 0)
        }

        val perPage: Int
            get() = columns * rows
    }

    /**
     * Computes the [SlotFit] for [printSize] within a page of [pageWidthMm] x [pageHeightMm] with
     * [marginMm] margins on every side and [gapMm] between adjacent slots. Returns a [SlotFit]
     * with `perPage == 0` when not even a single slot fits the printable area (e.g. a print size
     * larger than the printable area) — callers surface this as a validation error rather than
     * silently producing an empty page.
     */
    fun fit(
        pageWidthMm: Double,
        pageHeightMm: Double,
        marginMm: Double,
        gapMm: Double,
        printSize: PdfPrintSize,
    ): SlotFit {
        val printableW = pageWidthMm - 2 * marginMm
        val printableH = pageHeightMm - 2 * marginMm
        if (printableW <= 0 || printableH <= 0) return SlotFit(0, 0, printSize.widthMm, printSize.heightMm, false)

        fun countAlong(spaceMm: Double, slotMm: Double): Int =
            if (slotMm <= 0) 0 else floor((spaceMm + gapMm) / (slotMm + gapMm) + 1e-9).toInt().coerceAtLeast(0)

        val portraitCols = countAlong(printableW, printSize.widthMm)
        val portraitRows = countAlong(printableH, printSize.heightMm)
        val portraitCount = portraitCols * portraitRows

        val rotatedCols = countAlong(printableW, printSize.heightMm)
        val rotatedRows = countAlong(printableH, printSize.widthMm)
        val rotatedCount = rotatedCols * rotatedRows

        val paperIsLandscape = pageWidthMm > pageHeightMm
        val usePortrait =
            when {
                portraitCount > rotatedCount -> true
                rotatedCount > portraitCount -> false
                else -> {
                    // Tie: pick the slot orientation that matches the paper's own orientation.
                    val portraitSlotIsLandscape = printSize.widthMm > printSize.heightMm
                    portraitSlotIsLandscape == paperIsLandscape
                }
            }

        return if (usePortrait) {
            SlotFit(portraitCols, portraitRows, printSize.widthMm, printSize.heightMm, rotated = false)
        } else {
            SlotFit(rotatedCols, rotatedRows, printSize.heightMm, printSize.widthMm, rotated = true)
        }
    }

    /** One slot's rectangle on the page, in page-space mm (top-left origin), sized exactly to the
     * print size so the page can be cut to size. */
    data class SlotRect(val x: Double, val y: Double, val width: Double, val height: Double)

    /**
     * The centered grid of [SlotRect]s for [fit] within a page of [pageWidthMm] x [pageHeightMm]
     * with [marginMm]/[gapMm]: any leftover space (the printable area is rarely an exact multiple
     * of `slot + gap`) is split evenly as extra margin around the whole block, so the grid always
     * sits centered on the page rather than pinned to the top-left margin corner.
     */
    fun slotRects(
        pageWidthMm: Double,
        pageHeightMm: Double,
        marginMm: Double,
        gapMm: Double,
        fit: SlotFit,
    ): List<SlotRect> {
        if (fit.perPage == 0) return emptyList()
        val blockWidth = fit.columns * fit.slotWidthMm + (fit.columns - 1) * gapMm
        val blockHeight = fit.rows * fit.slotHeightMm + (fit.rows - 1) * gapMm
        val printableW = pageWidthMm - 2 * marginMm
        val printableH = pageHeightMm - 2 * marginMm
        val offsetX = marginMm + max(0.0, (printableW - blockWidth) / 2)
        val offsetY = marginMm + max(0.0, (printableH - blockHeight) / 2)
        return (0 until fit.rows).flatMap { row ->
            (0 until fit.columns).map { col ->
                SlotRect(
                    x = offsetX + col * (fit.slotWidthMm + gapMm),
                    y = offsetY + row * (fit.slotHeightMm + gapMm),
                    width = fit.slotWidthMm,
                    height = fit.slotHeightMm,
                )
            }
        }
    }

    /** Splits [count] photos into pages of [perPage] slots each, overflowing onto as many
     * additional pages as needed (empty when [perPage] is 0 and [count] > 0 — the caller should
     * have already rejected that as a validation error via [fit]'s `perPage == 0`). */
    fun paginate(count: Int, perPage: Int): List<Int> {
        if (count <= 0 || perPage <= 0) return if (count <= 0) emptyList() else emptyList()
        val pages = mutableListOf<Int>()
        var remaining = count
        while (remaining > 0) {
            val n = min(perPage, remaining)
            pages += n
            remaining -= n
        }
        return pages
    }

    /** A content rectangle placed within a frame, in the frame's own local coordinates
     * (0,0 = the frame's top-left corner). */
    data class ContentRect(val x: Double, val y: Double, val width: Double, val height: Double)

    /**
     * The placement geometry behind feedback item A's two modes, shared by [PdfProcessingService]
     * (the exporter) and the canvas so both paint identical pixels (WYSIWYG): scales the
     * [contentWidth] x [contentHeight] source image to [PdfFit.Cover] (fills the frame completely,
     * cropping — "Fill") or [PdfFit.Contain] (scales down to fit entirely inside the frame,
     * "Fit"/whole photo) a [frameWidth] x [frameHeight] frame, then centers it according to
     * [focusX]/[focusY] (0.5/0.5 = centered; only visible for Cover, where the scaled content is
     * larger than the frame on at least one axis). Handles panoramic and portrait source photos
     * identically — the aspect-ratio comparison, not the orientation, decides which axis is
     * clipped/letterboxed.
     */
    fun contentRect(
        frameWidth: Double,
        frameHeight: Double,
        contentWidth: Double,
        contentHeight: Double,
        fit: PdfFit,
        focusX: Double = .5,
        focusY: Double = .5,
    ): ContentRect {
        require(frameWidth > 0 && frameHeight > 0 && contentWidth > 0 && contentHeight > 0)
        val scale =
            if (fit == PdfFit.Cover) max(frameWidth / contentWidth, frameHeight / contentHeight)
            else min(frameWidth / contentWidth, frameHeight / contentHeight)
        val w = contentWidth * scale
        val h = contentHeight * scale
        val x = (frameWidth - w) * focusX.coerceIn(0.0, 1.0)
        val y = (frameHeight - h) * focusY.coerceIn(0.0, 1.0)
        return ContentRect(x, y, w, h)
    }
}
