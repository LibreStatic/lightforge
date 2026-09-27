package com.librestatic.lightforge.feature.pdfstudio

/**
 * Pure placement/limit math for inserting one image via the Media panel (Phase F item 3: tap,
 * drag & drop, or "In this project" reuse) - kept dependency-free so it has plain JVM tests,
 * mirroring [PdfSnapGuides]/[PdfRulerMath]. Both [PdfProjectRepository.importUnlocked] (tap/drop,
 * through the targeted gallery-delivery path) and [PdfStudioViewModel.insertOwnAsset] ("In this
 * project") call this instead of duplicating the arithmetic.
 */
internal object PdfMediaPlacement {
    /** The default 24-elements-per-page limit every import/add path enforces (also checked by
     * [PdfProject.validate], which is the final backstop even where a call site skips this) —
     * images and texts share this one cap (Phase G1b), so every call site passes
     * `page.images.size + page.texts.size`, not images alone. */
    const val MaxImagesPerPage = 24

    /** True while [currentElementCount] (images + texts) still has room for one more element on
     * the page. */
    fun hasRoomForOneMore(currentElementCount: Int, limit: Int = MaxImagesPerPage): Boolean =
        currentElementCount < limit

    /** A drag & drop's page-space CENTER (`centerX`/`centerY`, mm) converted to the top-left
     * [PdfImage] stores, for an image of the given [width]/[height] (mm). */
    fun centerToTopLeft(
        centerX: Double,
        centerY: Double,
        width: Double,
        height: Double,
    ): Pair<Double, Double> = (centerX - width / 2) to (centerY - height / 2)

    /** Auto-placement for a tap-insert or an "In this project" reuse (no explicit drop point):
     * the next diagonal slot near the top-left margin, same incremental offset every other
     * auto-placed insert already uses, capped so images never march past a sensible starting
     * area regardless of how many are already on the page. */
    fun autoSlotTopLeft(existingImageCount: Int, margin: Double): Pair<Double, Double> {
        val offset = margin + minOf(existingImageCount * 6, 30)
        return offset to offset
    }

    /** The inserted image's width/height (mm), fit to at most [maxWidthMm] and never wider than
     * the page's own printable area, keeping the source's aspect ratio. */
    fun fitSize(
        pageWidth: Double,
        pageMargin: Double,
        assetWidth: Int,
        assetHeight: Int,
        maxWidthMm: Double = 85.0,
    ): Pair<Double, Double> {
        require(assetWidth > 0 && assetHeight > 0)
        val w = minOf(maxWidthMm, pageWidth - 2 * pageMargin)
        return w to (w * assetHeight / assetWidth)
    }
}
