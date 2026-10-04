package com.librestatic.lightforge.feature.pdfstudio

import kotlin.math.roundToInt

/** Pure clamping/step math behind [PdfStepperField]; kept separate so it has plain JVM coverage. */
internal object PdfStepperMath {
    /** [value] moved by one [step] in the direction of [delta] (usually -1 or 1), clamped. */
    fun stepped(value: Double, step: Double, delta: Int, min: Double, max: Double): Double =
        (value + step * delta).coerceIn(min, max)

    /** Margin/gap stepper increment in the displayed [unit]: about 1 mm everywhere (0.1 cm, 0.05 in, 1 px). */
    fun layoutStep(unit: PdfUnit): Double =
        when (unit) {
            PdfUnit.Millimeter -> 1.0
            PdfUnit.Centimeter -> 0.1
            PdfUnit.Inch -> 0.05
            PdfUnit.Pixel -> 1.0
        }
}

/** A visual paper preset offered by the Layout panel's paper cards, in millimeters. */
internal data class PdfPaperPreset(val id: String, val label: String, val widthMm: Double, val heightMm: Double)

internal object PdfPaperPresets {
    const val A4 = "a4"
    const val LETTER = "letter"
    const val PRINT_10X15 = "10x15"
    const val SQUARE = "square"
    const val CUSTOM = "custom"

    /** Portrait dimensions; callers swap width/height themselves for landscape. */
    val presets =
        listOf(
            PdfPaperPreset(A4, "A4", 210.0, 297.0),
            PdfPaperPreset(LETTER, "Letter", 215.9, 279.4),
            PdfPaperPreset(PRINT_10X15, "10 × 15 cm", 100.0, 150.0),
            PdfPaperPreset(SQUARE, "Square", 148.0, 148.0),
        )

    /** Which preset (if any) a page's current, orientation-normalized size matches. */
    fun matching(widthMm: Double, heightMm: Double): String {
        val w = maxOf(widthMm, heightMm)
        val h = minOf(widthMm, heightMm)
        presets.forEach { preset ->
            val pw = maxOf(preset.widthMm, preset.heightMm)
            val ph = minOf(preset.widthMm, preset.heightMm)
            if (kotlin.math.abs(pw - w) < 0.5 && kotlin.math.abs(ph - h) < 0.5) return preset.id
        }
        return CUSTOM
    }
}

/** The label resource for [PdfPrintSize] chips (New project sheet's "Print size" row, Layout
 * panel), shared so both read the same string. */
internal fun PdfPrintSize.labelRes(): Int =
    when (this) {
        PdfPrintSize.Wallet6x9 -> R.string.pdf_print_size_wallet_6x9
        PdfPrintSize.Print9x13 -> R.string.pdf_print_size_9x13
        PdfPrintSize.Print10x15 -> R.string.pdf_print_size_10x15
        PdfPrintSize.Print13x18 -> R.string.pdf_print_size_13x18
        PdfPrintSize.Print15x20 -> R.string.pdf_print_size_15x20
    }

/**
 * Maps a photos-per-page template tile to a column count for [PdfGeometry.grid]. 1/4/9 have an
 * obvious square layout; 2 and 6 depend on the page's orientation so the grid reads naturally
 * (2 photos stack in portrait, sit side-by-side in landscape; 6 is 2×3 in portrait, 3×2 in
 * landscape).
 */
internal object PdfLayoutTemplates {
    val TEMPLATES = listOf(1, 2, 4, 6, 9)

    fun columnsFor(template: Int, landscape: Boolean): Int =
        when (template) {
            1 -> 1
            2 -> if (landscape) 2 else 1
            4 -> 2
            6 -> if (landscape) 3 else 2
            9 -> 3
            else -> throw IllegalArgumentException("Unsupported template: $template")
        }

    /**
     * Row count for [template], so 4 (2×2 in portrait) and 6 (2×3 in portrait) — which share the
     * same 2-column layout — still produce differently proportioned image frames when arranged
     * via [PdfGeometry.grid]'s `rowsHint`. Phase F item 0.
     */
    fun rowsFor(template: Int, landscape: Boolean): Int =
        when (template) {
            1 -> 1
            2 -> if (landscape) 1 else 2
            4 -> 2
            6 -> if (landscape) 2 else 3
            9 -> 3
            else -> throw IllegalArgumentException("Unsupported template: $template")
        }

    /**
     * Which template tile (if any) unambiguously corresponds to [columns] at the given
     * orientation. Several templates can share a column count (4 and 6 both use 2 columns in
     * portrait; 1 and 2-portrait both use 1), so a column-count match alone cannot say which tile
     * to highlight as selected — used only as a fallback until the user explicitly taps a tile
     * (see the per-page/per-sheet `selectedTemplate` UI state in PdfLayoutPanel.kt and
     * PdfLibraryScreen.kt's new-project sheet). Phase F item 0.
     */
    fun unambiguousMatch(columns: Int, landscape: Boolean): Int? =
        TEMPLATES.filter { columnsFor(it, landscape) == columns }.singleOrNull()

    /**
     * Like [unambiguousMatch], but when several tiles share [columns] it falls back to the tile
     * equal to [photosPerPage] (if it really has that column count), so a project template such
     * as "Photo grid" (4 photos per page, 2 columns) highlights its 2x2 tile instead of none.
     */
    fun matchFor(columns: Int, landscape: Boolean, photosPerPage: Int): Int? =
        unambiguousMatch(columns, landscape)
            ?: photosPerPage.takeIf { it in TEMPLATES && columnsFor(it, landscape) == columns }
}

/** Inline validation for the custom page-size sheet: min 20 mm, max 2000 mm (matches
 * [PdfProject.validate]'s page bounds). */
internal object PdfCustomSize {
    const val MIN_MM = 20.0
    const val MAX_MM = 2000.0

    enum class Problem {
        Invalid,
        TooSmall,
        TooLarge,
    }

    data class Field(val problem: Problem?) {
        val isValid: Boolean
            get() = problem == null
    }

    data class Result(val width: Field, val height: Field) {
        val isValid: Boolean
            get() = width.isValid && height.isValid
    }

    private fun check(mm: Double?): Field =
        Field(
            when {
                mm == null || !mm.isFinite() -> Problem.Invalid
                mm < MIN_MM -> Problem.TooSmall
                mm > MAX_MM -> Problem.TooLarge
                else -> null
            }
        )

    fun validate(widthMm: Double?, heightMm: Double?): Result = Result(check(widthMm), check(heightMm))
}

/**
 * Shared drag-reorder target math for the Phase C page strip and the Phase D pages grid: given
 * the dragged item's live center and every visible item's center, finds which item the drag is
 * now nearest to and returns its index in [order] — the position the drop should land on. Plain
 * doubles (not a Compose `Offset`) so this has ordinary JVM test coverage; the strip's 1D case is
 * just every center sharing the same y.
 *
 * [excludeKeys] drops non-reorderable items from the search (the grid's trailing "add page"
 * tile), so a drop near the end lands on the last real item instead of resolving to no match at
 * all — [fallback] only fires when a resolvable item is not found and to be safe (e.g. every key
 * is excluded).
 */
internal object PdfDragReorder {
    fun nearestIndex(
        order: List<String>,
        centers: List<Pair<String, Pair<Double, Double>>>,
        selfCenter: Pair<Double, Double>,
        excludeKeys: Set<String> = emptySet(),
        fallback: Int,
    ): Int {
        val nearestKey =
            centers
                .filter { it.first !in excludeKeys }
                .minByOrNull { (_, c) ->
                    val dx = c.first - selfCenter.first
                    val dy = c.second - selfCenter.second
                    dx * dx + dy * dy
                }
                ?.first
        return nearestKey?.let { key -> order.indexOf(key) }?.takeIf { it >= 0 } ?: fallback
    }
}

/** Pure math for the Adjust panel's 2D crop-focus viewport: dragging/keyboard nudging moves the
 * focus point within 0..1 on each axis; formatting is left to the caller (localized string). */
internal object PdfCropFocus {
    const val NUDGE = 0.05

    fun move(current: Double, delta: Double): Double = (current + delta).coerceIn(0.0, 1.0)

    fun percent(focus: Double): Int = (focus * 100).roundToInt().coerceIn(0, 100)
}
