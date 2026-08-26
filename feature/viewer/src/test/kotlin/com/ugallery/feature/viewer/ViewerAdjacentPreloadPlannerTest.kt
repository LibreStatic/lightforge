package com.ugallery.feature.viewer

import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.MediaKind
import com.ugallery.core.model.TimelineMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerAdjacentPreloadPlannerTest {
    @Test fun plansTheImagesImmediatelyLeftAndRight() {
        val items = listOf(image(1), image(2), image(3))

        val planned = ViewerAdjacentPreloadPlanner.plan(
            items = items,
            currentIndex = 1,
            maxSourcePixels = 50_000_000,
            safeBudgetBytes = ViewerAdjacentPreloadPlanner.PreviewBudgetBytes * 2,
            backgroundPreloadEnabled = true,
        )

        assertEquals(listOf(1L, 3L), planned.map { it.key.mediaStoreId })
    }

    @Test fun rejectsIneligibleSourcesAndVideos() {
        val planned = ViewerAdjacentPreloadPlanner.plan(
            items = listOf(image(1, 12_000, 5_000), image(2), video(3)),
            currentIndex = 1,
            maxSourcePixels = 50_000_000,
            safeBudgetBytes = ViewerAdjacentPreloadPlanner.PreviewBudgetBytes * 2,
            backgroundPreloadEnabled = true,
        )

        assertTrue(planned.isEmpty())
    }

    @Test fun abandonsSpeculationWhenBothNeighborsAreNotMemorySafe() {
        val planned = ViewerAdjacentPreloadPlanner.plan(
            items = listOf(image(1), image(2), image(3)),
            currentIndex = 1,
            maxSourcePixels = 50_000_000,
            safeBudgetBytes = ViewerAdjacentPreloadPlanner.PreviewBudgetBytes * 2 - 1,
            backgroundPreloadEnabled = true,
        )

        assertTrue(planned.isEmpty())
    }

    private fun image(id: Long, width: Int = 4_000, height: Int = 3_000) = TimelineMedia(
        key = MediaKey("external_primary", id),
        kind = MediaKind.Image,
        generationModified = 1,
        timelineSortMillis = id,
        width = width,
        height = height,
        durationMillis = 0,
        dateExpiresMillis = null,
        isFavorite = false,
        isTrashed = false,
    )

    private fun video(id: Long) = image(id).copy(kind = MediaKind.Video)
}
