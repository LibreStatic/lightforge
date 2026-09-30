package com.librestatic.lightforge.core.thumbnail

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.librestatic.lightforge.core.model.MediaKey
import kotlin.math.min

enum class DevicePerformanceTier { Flagship, MidRange, LowRam }

data class DeviceHardwareProfile(
    val mediaPerformanceClass: Int,
    val processors: Int,
    val totalMemoryBytes: Long,
    val lowRam: Boolean,
)

object DevicePerformanceClassifier {
    private const val SixGiB = 6L * 1_024 * 1_024 * 1_024

    fun detect(context: Context): DeviceHardwareProfile {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        return DeviceHardwareProfile(
            mediaPerformanceClass = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.VERSION.MEDIA_PERFORMANCE_CLASS
            } else {
                0
            },
            processors = Runtime.getRuntime().availableProcessors(),
            totalMemoryBytes = memory.totalMem,
            lowRam = activityManager.isLowRamDevice,
        )
    }

    fun classify(profile: DeviceHardwareProfile): DevicePerformanceTier = when {
        profile.lowRam -> DevicePerformanceTier.LowRam
        profile.mediaPerformanceClass >= Build.VERSION_CODES.S ||
            (profile.processors >= 8 && profile.totalMemoryBytes >= SixGiB) -> DevicePerformanceTier.Flagship
        else -> DevicePerformanceTier.MidRange
    }
}

data class ThumbnailMemorySnapshot(
    val availableBytes: Long,
    val lowMemoryThresholdBytes: Long,
    val lowMemory: Boolean,
)

class ThumbnailPrefetchPolicy internal constructor(
    val tier: DevicePerformanceTier,
    val maxSourcePixels: Long,
    val extraRows: Int,
    private val maxCacheBytes: Long,
    private val memorySnapshot: () -> ThumbnailMemorySnapshot,
) {
    private var cachedSnapshot: ThumbnailMemorySnapshot? = null
    private var cachedAtNanos = 0L

    fun safeBudgetBytes(): Long {
        if (tier == DevicePerformanceTier.LowRam) return 0
        val memory = recentMemorySnapshot()
        if (memory.lowMemory) return 0
        val headroom = (memory.availableBytes - memory.lowMemoryThresholdBytes).coerceAtLeast(0)
        return min(maxCacheBytes, headroom / MemoryHeadroomDivisor)
    }

    /** getMemoryInfo is a binder call; a viewport replan must not pay it on every row change. */
    @Synchronized
    private fun recentMemorySnapshot(): ThumbnailMemorySnapshot {
        val now = System.nanoTime()
        cachedSnapshot?.takeIf { now - cachedAtNanos < MemorySnapshotMaxAgeNanos }?.let { return it }
        return memorySnapshot().also {
            cachedSnapshot = it
            cachedAtNanos = now
        }
    }

    fun isSourceEligible(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && width.toLong() * height.toLong() <= maxSourcePixels

    companion object {
        private const val MemoryHeadroomDivisor = 8L
        private const val MemorySnapshotMaxAgeNanos = 1_000_000_000L
        private const val FlagshipMaxPixels = 50_000_000L
        private const val MidRangeMaxPixels = 12_000_000L

        fun detect(context: Context, maxCacheBytes: Long): ThumbnailPrefetchPolicy {
            val activityManager = context.getSystemService(ActivityManager::class.java)
            val tier = DevicePerformanceClassifier.classify(DevicePerformanceClassifier.detect(context))
            return ThumbnailPrefetchPolicy(
                tier = tier,
                maxSourcePixels = when (tier) {
                    DevicePerformanceTier.Flagship -> FlagshipMaxPixels
                    DevicePerformanceTier.MidRange,
                    DevicePerformanceTier.LowRam,
                    -> MidRangeMaxPixels
                },
                extraRows = if (tier == DevicePerformanceTier.LowRam) 0 else 1,
                maxCacheBytes = maxCacheBytes,
                memorySnapshot = {
                    val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
                    ThumbnailMemorySnapshot(memory.availMem, memory.threshold, memory.lowMemory)
                },
            )
        }

        internal fun forTest(
            tier: DevicePerformanceTier,
            maxCacheBytes: Long,
            memorySnapshot: ThumbnailMemorySnapshot,
        ) = ThumbnailPrefetchPolicy(
            tier = tier,
            maxSourcePixels = if (tier == DevicePerformanceTier.Flagship) {
                FlagshipMaxPixels
            } else {
                MidRangeMaxPixels
            },
            extraRows = if (tier == DevicePerformanceTier.LowRam) 0 else 1,
            maxCacheBytes = maxCacheBytes,
            memorySnapshot = { memorySnapshot },
        )
    }
}

data class ThumbnailPrefetchCandidate(
    val request: ThumbnailRequest,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val distanceFromViewportCenter: Int,
)

data class PlannedThumbnailRequest(
    val request: ThumbnailRequest,
    val priority: ThumbnailLoadPriority,
)

object ThumbnailPrefetchPlanner {
    fun plan(
        visible: List<ThumbnailPrefetchCandidate>,
        extraRow: List<ThumbnailPrefetchCandidate>,
        policy: ThumbnailPrefetchPolicy,
    ): List<PlannedThumbnailRequest> {
        val budget = policy.safeBudgetBytes()
        if (budget <= 0) return emptyList()

        val uniqueVisible = visible.distinctBy { it.request }
        val eligibleVisible = uniqueVisible.filter {
            policy.isSourceEligible(it.sourceWidth, it.sourceHeight)
        }
        val visibleBytes = eligibleVisible.sumOf { it.request.decodedByteCount() }
        val selectedVisible = if (visibleBytes <= budget) {
            eligibleVisible
        } else {
            var used = 0L
            eligibleVisible.sortedBy { it.distanceFromViewportCenter }.filter { candidate ->
                val bytes = candidate.request.decodedByteCount()
                if (used + bytes > budget) false else {
                    used += bytes
                    true
                }
            }
        }
        val selectedBytes = selectedVisible.sumOf { it.request.decodedByteCount() }
        val canSpeculate = policy.extraRows > 0 &&
            uniqueVisible.size == eligibleVisible.size &&
            selectedVisible.size == eligibleVisible.size
        val eligibleExtra = if (canSpeculate) {
            extraRow.distinctBy { it.request }.filter {
                policy.isSourceEligible(it.sourceWidth, it.sourceHeight)
            }
        } else {
            emptyList()
        }
        val extraBytes = eligibleExtra.sumOf { it.request.decodedByteCount() }
        val selectedExtra = if (selectedBytes + extraBytes <= budget) eligibleExtra else emptyList()

        return selectedVisible.map { PlannedThumbnailRequest(it.request, ThumbnailLoadPriority.Visible) } +
            selectedExtra.map { PlannedThumbnailRequest(it.request, ThumbnailLoadPriority.Prefetch) }
    }
}

private fun ThumbnailRequest.decodedByteCount(): Long =
    widthPx.toLong() * heightPx.toLong() * ArgbBytesPerPixel

private const val ArgbBytesPerPixel = 4L
