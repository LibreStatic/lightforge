package com.ugallery.feature.viewer

import com.ugallery.core.model.MediaKind
import com.ugallery.core.model.TimelineMedia

/**
 * Keeps the two pages touching the current viewer page warm without allowing
 * speculative previews to consume memory needed by the visible image.
 */
object ViewerAdjacentPreloadPlanner {
    const val PreviewWidthPx = 1_080
    const val PreviewHeightPx = 1_920
    private const val ArgbBytesPerPixel = 4L
    const val PreviewBudgetBytes = PreviewWidthPx.toLong() * PreviewHeightPx * ArgbBytesPerPixel

    fun plan(
        items: List<TimelineMedia>,
        currentIndex: Int,
        maxSourcePixels: Long,
        safeBudgetBytes: Long,
        backgroundPreloadEnabled: Boolean,
    ): List<TimelineMedia> {
        if (!backgroundPreloadEnabled || currentIndex !in items.indices) return emptyList()
        val adjacent = listOfNotNull(
            items.getOrNull(currentIndex - 1),
            items.getOrNull(currentIndex + 1),
        ).filter { media ->
            media.kind == MediaKind.Image &&
                media.width > 0 &&
                media.height > 0 &&
                media.width.toLong() * media.height.toLong() <= maxSourcePixels
        }
        return adjacent.takeIf {
            it.isNotEmpty() && PreviewBudgetBytes * it.size <= safeBudgetBytes
        }.orEmpty()
    }
}
