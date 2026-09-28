package com.librestatic.lightforge.feature.pdfstudio

import kotlin.math.min

/** The [PdfPrintLayout.SlotFit] for [printSize] on the [paperId] paper preset with [marginMm]
 * margins and [gapMm] gap — used by [PdfTemplate.Prints10x15]'s enum constants below, computed
 * once here (rather than inline per entry) since [PdfTemplate]'s own `photosPerPage` and
 * `columns` fields are plain constructor args, evaluated before the enum class (and its
 * `paperPreset()` helper, which needs a constructed instance) exists. */
private fun printSizeFit(paperId: String, printSize: PdfPrintSize, marginMm: Double, gapMm: Double): PdfPrintLayout.SlotFit {
    val preset = PdfPaperPresets.presets.first { it.id == paperId }
    return PdfPrintLayout.fit(preset.widthMm, preset.heightMm, marginMm, gapMm, printSize)
}

/**
 * Built-in project/page presets (Phase G4): the single source of truth for "Photo grid",
 * "Receipts", "Prints 10×15" and the implicit "Blank" default, reused by:
 *  - the library's first-run empty state (Phase E item 2);
 *  - the "New project" sheet's template row (Phase E item 3);
 *  - the gallery "Create PDF" batch handoff, which routes its default layout through [Blank]
 *    instead of the ad hoc literals it used before this file existed.
 *
 * Each entry is a paper/orientation/margin/gap/columns preset plus a [photosPerPage] cap used to
 * lay out an incoming batch of photos across as many pages as needed (see [layoutPages]). Picking
 * conventional defaults (Canva / Google Photos print books / Apple Pages "photo grid" style
 * templates):
 *  - [PhotoGrid]: A4 portrait, 2×2 grid, 10 mm margin, 5 mm gap between photos.
 *  - [Receipts]: A4 portrait, a single stacked column with generous margins so a scanned receipt
 *    reads like a printed page rather than a thumbnail.
 *  - [Prints10x15]: a 10×15 cm print, one photo per page, a near-zero margin (2 mm) and `Cover`
 *    fit so the photo fills the printable area edge to edge, matching how a photo lab print looks.
 *  - [Blank]: the pre-existing defaults (A4 portrait, 2 columns, 10 mm margin, 4 mm gap) — not a
 *    library shortcut, just the fallback every non-templated project (including a "New project"
 *    sheet with no template tapped, and the gallery batch handoff) already used.
 */
enum class PdfTemplate(
    val nameRes: Int,
    val descriptionRes: Int,
    val paper: String,
    val landscape: Boolean,
    val columns: Int,
    val margin: Double,
    val gap: Double,
    val photosPerPage: Int,
    val fit: PdfFit,
    /**
     * Feedback item B: when non-null, this template lays photos out at an exact physical print
     * size on the paper (via [PdfPrintLayout]) instead of the free-grid [columns]/[photosPerPage]
     * behavior — [photosPerPage] above is still populated (with the COMPUTED per-page count for
     * this paper/margin/gap) so previews ([PdfTemplateCard]) and [PdfProject.printSize] metadata
     * stay in sync with what [layoutPages] actually produces.
     */
    val printSize: PdfPrintSize? = null,
) {
    Blank(
        R.string.pdf_template_blank_name,
        R.string.pdf_template_blank_desc,
        PdfPaperPresets.A4,
        false,
        2,
        10.0,
        4.0,
        4,
        PdfFit.Contain,
    ),
    PhotoGrid(
        R.string.pdf_library_template_photo_grid,
        R.string.pdf_template_photo_grid_desc,
        PdfPaperPresets.A4,
        false,
        2,
        10.0,
        5.0,
        4,
        PdfFit.Contain,
    ),
    Receipts(
        R.string.pdf_library_template_receipts,
        R.string.pdf_template_receipts_desc,
        PdfPaperPresets.A4,
        false,
        1,
        18.0,
        6.0,
        3,
        PdfFit.Contain,
    ),
    /**
     * Feedback item B: redefined from "one 10×15 print filling a 10×15 sheet" to "10×15 cm
     * photos, as many as fit, on A4" — the print size is fixed at 10×15 cm and the photo count
     * per page is COMPUTED from the paper/margin/gap by [PdfPrintLayout.fit] (2 per A4 page with
     * these defaults), not hardcoded. Uses [PdfFit.Cover] ("Fill picture frame") per feedback item
     * A, matching how a photo lab print looks.
     */
    Prints10x15(
        R.string.pdf_library_template_prints,
        R.string.pdf_template_prints_desc,
        PdfPaperPresets.A4,
        false,
        printSizeFit(PdfPaperPresets.A4, PdfPrintSize.Print10x15, 5.0, 0.0).columns,
        5.0,
        0.0,
        printSizeFit(PdfPaperPresets.A4, PdfPrintSize.Print10x15, 5.0, 0.0).perPage,
        PdfFit.Cover,
        PdfPrintSize.Print10x15,
    );

    internal fun paperPreset(): PdfPaperPreset = PdfPaperPresets.presets.first { it.id == paper }

    /** Portrait-normalized paper dimensions, swapped for [landscape]. Public callers that let the
     * user pick a different orientation (the New project sheet, the Layout panel) pass their own
     * `landscape` flag alongside these values instead of relying on the template's default. */
    fun widthMm(landscape: Boolean = this.landscape): Double {
        val p = paperPreset()
        return if (landscape) p.heightMm else p.widthMm
    }

    fun heightMm(landscape: Boolean = this.landscape): Double {
        val p = paperPreset()
        return if (landscape) p.widthMm else p.heightMm
    }

    /** A single blank page honoring this template's paper/orientation/margin, with the margin
     * clamped exactly as [PdfProject.validate] requires (`0..min(width, height) / 4`) so the
     * result always validates regardless of how small the paper preset is. */
    fun blankPage(landscape: Boolean = this.landscape, margin: Double = this.margin): PdfPage {
        val w = widthMm(landscape)
        val h = heightMm(landscape)
        return PdfPage(width = w, height = h, margin = margin.coerceAtMost(min(w, h) / 4))
    }

    /** A brand-new, already-[PdfProject.validate]d project named [name] with a single blank page
     * laid out per this template — the "New project" sheet and library empty-state shortcuts. */
    fun newProject(
        name: String,
        landscape: Boolean = this.landscape,
        columns: Int = this.columns,
        margin: Double = this.margin,
        gap: Double = this.gap,
    ): PdfProject =
        PdfProject(
                name = name,
                pages = listOf(blankPage(landscape, margin)),
                columns = columns,
                gap = gap,
                printSize = printSize?.id,
                placementMode = fit,
            )
            .validate()

    /**
     * Lays out [assetIds] (image asset hashes, expected to already be present in the project's
     * [PdfProject.assets]) across as many pages as needed. Two paths (feedback item B):
     *  - [printSize] non-null: exact-size slots computed by [PdfPrintLayout] — [photosPerPage] per
     *    page (COMPUTED for this paper/margin/gap, not hardcoded), overflow onto further pages.
     *  - [printSize] null (free grid): [photosPerPage] images per page, gridded per this
     *    template's [columns]/[gap]/[margin] via [PdfGeometry.grid] (the same Arrange logic the
     *    Layout panel uses, not a duplicate).
     * Zero assets yields a single blank page, matching [newProject]. Each placed image uses this
     * template's [fit] (feedback item A's placement mode) — Prints 10×15's `Cover`/"Fill" crops to
     * the slot; the free-grid templates use `Contain`/"Fit".
     */
    fun layoutPages(assetIds: List<String>): List<PdfPage> {
        if (assetIds.isEmpty()) return listOf(blankPage())
        val size = printSize
        if (size != null) {
            val template = blankPage()
            val slotFit = PdfPrintLayout.fit(template.width, template.height, template.margin, gap, size)
            require(slotFit.perPage > 0) {
                "Print size ${size.id} does not fit ${template.width}x${template.height}mm with ${template.margin}mm margins"
            }
            val rects = PdfPrintLayout.slotRects(template.width, template.height, template.margin, gap, slotFit)
            return assetIds.chunked(slotFit.perPage).map { chunk ->
                val images =
                    chunk.mapIndexed { n, asset ->
                        val r = rects[n]
                        PdfImage(asset = asset, x = r.x, y = r.y, width = r.width, height = r.height, fit = fit)
                    }
                // A fresh blankPage() per output page (rather than .copy()-ing `template`) so each
                // page gets its own distinct id, as PdfProject.validate requires.
                blankPage().copy(images = images)
            }
        }
        return assetIds.chunked(photosPerPage).map { chunk ->
            val page = blankPage()
            val innerW = page.width - 2 * page.margin
            val innerH = page.height - 2 * page.margin
            val images =
                chunk.map { asset ->
                    PdfImage(
                        asset = asset,
                        x = page.margin,
                        y = page.margin,
                        width = innerW,
                        height = innerH,
                        fit = fit,
                    )
                }
            val rows = (chunk.size + columns - 1) / columns
            PdfGeometry.grid(page.copy(images = images), columns, gap, rows)
        }
    }

    /** [name]'s project with [assetIds] laid out per [layoutPages] and [assets] metadata already
     * attached, fully [PdfProject.validate]d. Used by JVM tests and, once the gallery handoff
     * offers templates rather than always defaulting to [Blank], by that flow too. */
    fun buildProject(name: String, assets: List<PdfAsset>, assetIds: List<String>): PdfProject =
        PdfProject(
                name = name,
                pages = layoutPages(assetIds),
                assets = assets,
                columns = columns,
                gap = gap,
                printSize = printSize?.id,
                placementMode = fit,
            )
            .validate()

    companion object {
        /** Shown as shortcuts from the library empty state and atop the "New project" sheet.
         * [Blank] is offered too (as the sheet's implicit default / explicit first tile) but isn't
         * itself a "quick start" shortcut — see the class doc. */
        val LIBRARY_SHORTCUTS = listOf(PhotoGrid, Receipts, Prints10x15)

        /** Every tile the "New project" sheet's template row offers, [Blank] first. */
        val NEW_PROJECT_TILES = listOf(Blank) + LIBRARY_SHORTCUTS
    }
}
