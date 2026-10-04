package com.librestatic.lightforge.feature.pdfstudio

/**
 * Pure, side-effect-free size estimator for the export sheet's "≈ size" figures.
 *
 * Mirrors the sampling rules the isolated renderer actually applies
 * (`PdfProcessingService.kt`): a non-JPEG image is re-encoded losslessly for Original quality only
 * if it already fits [ORIGINAL_MAX_SIDE] px per side and [MAX_PIXELS] total pixels; otherwise the
 * renderer throws [PdfFailure.LimitExceeded] rather than silently downsampling a "lossless" export.
 * Compact quality always downsamples to [COMPACT_MAX_SIDE] and re-encodes as JPEG at
 * [COMPACT_JPEG_QUALITY]. A JPEG source is copied byte-for-byte in Original quality, so its
 * contribution there is exact, not a heuristic.
 *
 * The byte-per-pixel constants below are heuristics tuned against typical photo content; the UI
 * must always present this as an approximation ("≈") and swap in the exact `outputBytes` once a
 * job finishes.
 */
object PdfExportEstimator {
    const val ORIGINAL_MAX_SIDE = 8192
    const val COMPACT_MAX_SIDE = 1600
    const val MAX_PIXELS = 16_000_000L
    const val COMPACT_JPEG_QUALITY = 0.75f

    /** Heuristic bytes/pixel for a JPEG re-encode at [COMPACT_JPEG_QUALITY] of photographic content. */
    private const val COMPACT_JPEG_BYTES_PER_PIXEL = 0.16
    /** Heuristic bytes/pixel for a lossless PNG/WebP re-encode of photographic content. */
    private const val LOSSLESS_BYTES_PER_PIXEL = 0.9
    /** Rough per-page container overhead added on top of an imported PDF page's proportional bytes. */
    private const val IMPORTED_PDF_PAGE_OVERHEAD_BYTES = 1_500L

    /**
     * The [width]x[height] the renderer would actually decode at, after halving repeatedly (as
     * `BitmapFactory.Options.inSampleSize` does) until both [maxSide] and [maxPixels] are honored.
     */
    fun effectiveDimensions(width: Int, height: Int, maxSide: Int, maxPixels: Long): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width to height
        var sample = 1
        while (
            maxOf(width, height) / sample > maxSide ||
                (width.toLong() / sample) * (height.toLong() / sample) > maxPixels
        )
            sample *= 2
        return (width / sample) to (height / sample)
    }

    /**
     * True when [asset] cannot be re-encoded losslessly at Original quality without downsampling,
     * i.e. exporting it as Original would fail with [PdfFailure.LimitExceeded]. JPEG sources and
     * imported PDF pages are copied/appended directly and never hit this budget.
     */
    fun exceedsOriginalBudget(asset: PdfAsset): Boolean {
        if (asset.mime == "image/jpeg" || asset.mime == "application/pdf") return false
        val (w, h) = effectiveDimensions(asset.width, asset.height, ORIGINAL_MAX_SIDE, MAX_PIXELS)
        return w != asset.width || h != asset.height
    }

    /**
     * 1-based photo numbers (order of first appearance across pages, matching the canvas's numbered
     * "Image N" labels) whose asset would trip [exceedsOriginalBudget].
     */
    fun oversizedOriginalPhotoNumbers(project: PdfProject): List<Int> {
        val result = mutableListOf<Int>()
        var number = 0
        project.pages.forEach { page ->
            page.images.forEach { image ->
                number++
                val asset = project.assets.firstOrNull { it.hash == image.asset }
                if (asset != null && exceedsOriginalBudget(asset)) result += number
            }
        }
        return result
    }

    /**
     * Approximate total output bytes for exporting [project] at [compact] quality.
     *
     * @param sourceBytes on-disk byte size of each used asset, keyed by hash (from
     *   `repository.file(hash).length()`); a missing entry contributes 0.
     * @param sourcePageCounts total page count of each imported-PDF asset, keyed by hash, used to
     *   prorate its bytes across the pages this project actually uses; a missing entry assumes 1
     *   (the whole file is attributed to a single used page).
     */
    fun estimate(
        project: PdfProject,
        sourceBytes: Map<String, Long>,
        sourcePageCounts: Map<String, Int> = emptyMap(),
        compact: Boolean,
    ): Long {
        var total = 0L
        project.pages.forEach { page ->
            val hash = page.source ?: return@forEach
            val bytes = sourceBytes[hash] ?: 0L
            val totalPages = sourcePageCounts[hash]?.takeIf { it > 0 } ?: 1
            total += bytes / totalPages + IMPORTED_PDF_PAGE_OVERHEAD_BYTES
        }
        val used = project.usedAssets()
        project.assets
            .filter { it.hash in used && it.mime != "application/pdf" }
            .forEach { asset -> total += estimateImageBytes(asset, sourceBytes[asset.hash] ?: 0L, compact) }
        return total
    }

    private fun estimateImageBytes(asset: PdfAsset, sourceBytes: Long, compact: Boolean): Long =
        if (!compact) {
            when (asset.mime) {
                // Original quality copies a JPEG source byte-for-byte: this is exact, not a guess.
                "image/jpeg" -> sourceBytes
                else -> {
                    val (w, h) =
                        effectiveDimensions(asset.width, asset.height, ORIGINAL_MAX_SIDE, MAX_PIXELS)
                    (w.toLong() * h.toLong() * LOSSLESS_BYTES_PER_PIXEL).toLong()
                }
            }
        } else {
            val (w, h) = effectiveDimensions(asset.width, asset.height, COMPACT_MAX_SIDE, MAX_PIXELS)
            val heuristic = (w.toLong() * h.toLong() * COMPACT_JPEG_BYTES_PER_PIXEL).toLong()
            // A JPEG source is already lossy-compressed: its Compact re-encode (never upscaled)
            // is not expected to outgrow the file Original copies byte-for-byte, so the heuristic
            // (tuned for photographic content) must not make Compact read larger than Original
            // for flat or already-small images.
            if (asset.mime == "image/jpeg" && sourceBytes > 0L) minOf(heuristic, sourceBytes) else heuristic
        }
}
