package com.ugallery.core.thumbnail

import com.ugallery.core.model.MediaKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailPrefetchPolicyTest {
    @Test
    fun sourceMegapixelCeilingsAreInclusiveAndOverflowSafe() {
        val flagship = policy(DevicePerformanceTier.Flagship)
        val midRange = policy(DevicePerformanceTier.MidRange)

        assertTrue(flagship.isSourceEligible(10_000, 5_000))
        assertFalse(flagship.isSourceEligible(10_001, 5_000))
        assertTrue(midRange.isSourceEligible(4_000, 3_000))
        assertFalse(midRange.isSourceEligible(4_001, 3_000))
        assertFalse(flagship.isSourceEligible(Int.MAX_VALUE, Int.MAX_VALUE))
        assertFalse(midRange.isSourceEligible(0, 3_000))
    }

    @Test
    fun completeVisibleWindowAndExtraRowArePlannedWhenSafe() {
        val policy = policy(DevicePerformanceTier.Flagship, maxCacheBytes = 1_000_000)
        val visible = listOf(candidate(1, 2), candidate(2, 0), candidate(3, 1))
        val extra = listOf(candidate(4, 3), candidate(5, 4))

        val plan = ThumbnailPrefetchPlanner.plan(visible, extra, policy)

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), plan.map { it.request.mediaKey.mediaStoreId })
        assertEquals(3, plan.count { it.priority == ThumbnailLoadPriority.Visible })
        assertEquals(2, plan.count { it.priority == ThumbnailLoadPriority.Prefetch })
    }

    @Test
    fun extraRowIsDroppedBeforeVisibleWindowIsReduced() {
        val policy = policy(DevicePerformanceTier.Flagship, maxCacheBytes = 130_000)
        val visible = listOf(candidate(1, 2), candidate(2, 0), candidate(3, 1))
        val extra = listOf(candidate(4, 3))

        val plan = ThumbnailPrefetchPlanner.plan(visible, extra, policy)

        assertEquals(setOf(1L, 2L, 3L), plan.map { it.request.mediaKey.mediaStoreId }.toSet())
        assertTrue(plan.all { it.priority == ThumbnailLoadPriority.Visible })
    }

    @Test
    fun visiblePrefetchFallsBackToViewportCenterWhenWindowIsUnsafe() {
        val policy = policy(DevicePerformanceTier.Flagship, maxCacheBytes = 80_000)
        val visible = listOf(candidate(1, 2), candidate(2, 0), candidate(3, 1))

        val plan = ThumbnailPrefetchPlanner.plan(visible, emptyList(), policy)

        assertEquals(listOf(2L, 3L), plan.map { it.request.mediaKey.mediaStoreId })
    }

    @Test
    fun lowRamOrSystemLowMemoryDisablesBackgroundPrefetch() {
        val lowRam = policy(DevicePerformanceTier.LowRam)
        val lowMemory = ThumbnailPrefetchPolicy.forTest(
            tier = DevicePerformanceTier.MidRange,
            maxCacheBytes = 1_000_000,
            memorySnapshot = ThumbnailMemorySnapshot(2_000_000, 100_000, lowMemory = true),
        )
        val visible = listOf(candidate(1, 0))

        assertTrue(ThumbnailPrefetchPlanner.plan(visible, emptyList(), lowRam).isEmpty())
        assertTrue(ThumbnailPrefetchPlanner.plan(visible, emptyList(), lowMemory).isEmpty())
    }

    private fun policy(
        tier: DevicePerformanceTier,
        maxCacheBytes: Long = 1_000_000,
    ) = ThumbnailPrefetchPolicy.forTest(
        tier = tier,
        maxCacheBytes = maxCacheBytes,
        memorySnapshot = ThumbnailMemorySnapshot(2_000_000_000, 100_000_000, lowMemory = false),
    )

    private fun candidate(id: Long, distance: Int) = ThumbnailPrefetchCandidate(
        request = ThumbnailRequest(MediaKey("external_primary", id), 1, 100, 100),
        sourceWidth = 4_000,
        sourceHeight = 3_000,
        distanceFromViewportCenter = distance,
    )
}
