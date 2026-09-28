package com.librestatic.lightforge.feature.pdfstudio

import kotlin.math.min

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
    Prints10x15(
        R.string.pdf_library_template_prints,
        R.string.pdf_template_prints_desc,
        PdfPaperPresets.PRINT_10X15,
        false,
        1,
        2.0,
        0.0,
        1,
        PdfFit.Cover,
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
        PdfProject(name = name, pages = listOf(blankPage(landscape, margin)), columns = columns, gap = gap)
            .validate()

    /**
     * Lays out [assetIds] (image asset hashes, expected to already be present in the project's
     * [PdfProject.assets]) across as many pages as needed: [photosPerPage] images per page,
     * overflowing onto additional pages beyond that, each page gridded per this template's
     * [columns]/[gap]/[margin] via [PdfGeometry.grid] (the same Arrange logic the Layout panel
     * uses, not a duplicate). Zero assets yields a single blank page, matching [newProject]. Each
     * placed image starts sized to the page's margin box with this template's [fit] — Prints
     * 10×15's `Cover` fills the printable area; the others use `Contain`.
     */
    fun layoutPages(assetIds: List<String>): List<PdfPage> {
        if (assetIds.isEmpty()) return listOf(blankPage())
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
        PdfProject(name = name, pages = layoutPages(assetIds), assets = assets, columns = columns, gap = gap)
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
