package com.librestatic.lightforge.core.model.folders

/** Facts about the items stored directly in one folder. */
data class FolderStats(
    val bucketId: Long?,
    val directCount: Long,
    val imageCount: Long,
    val videoCount: Long,
    val totalBytes: Long,
    val minSortMillis: Long,
    val maxSortMillis: Long,
    val coverMediaStoreId: Long?,
    val isAvailable: Boolean,
    /** The folder holds media hidden from the system gallery (a .nomedia folder); advanced mode only. */
    val isHidden: Boolean = false,
    /** A directory on disk with no media at all; advanced mode only. */
    val isEmptyDirectory: Boolean = false,
)

/** Sums over a folder and everything below it. Dates are null when the subtree has no items. */
data class FolderTotals(
    val totalCount: Long,
    val imageCount: Long,
    val videoCount: Long,
    val totalBytes: Long,
    val minSortMillis: Long?,
    val maxSortMillis: Long?,
    val coverMediaStoreId: Long?,
    val isAvailable: Boolean,
) {
    operator fun plus(other: FolderTotals): FolderTotals {
        val newerIsOther = (other.maxSortMillis ?: Long.MIN_VALUE) > (maxSortMillis ?: Long.MIN_VALUE)
        return FolderTotals(
            totalCount = totalCount + other.totalCount,
            imageCount = imageCount + other.imageCount,
            videoCount = videoCount + other.videoCount,
            totalBytes = totalBytes + other.totalBytes,
            minSortMillis = listOfNotNull(minSortMillis, other.minSortMillis).minOrNull(),
            maxSortMillis = listOfNotNull(maxSortMillis, other.maxSortMillis).maxOrNull(),
            coverMediaStoreId = if (newerIsOther || coverMediaStoreId == null) other.coverMediaStoreId ?: coverMediaStoreId else coverMediaStoreId,
            isAvailable = isAvailable || other.isAvailable,
        )
    }

    companion object {
        val Empty = FolderTotals(0, 0, 0, 0, null, null, null, false)

        fun of(stats: FolderStats): FolderTotals = FolderTotals(
            totalCount = stats.directCount,
            imageCount = stats.imageCount,
            videoCount = stats.videoCount,
            totalBytes = stats.totalBytes,
            minSortMillis = stats.minSortMillis,
            maxSortMillis = stats.maxSortMillis,
            coverMediaStoreId = stats.coverMediaStoreId,
            isAvailable = stats.isAvailable,
        )
    }
}

/**
 * The folder hierarchy: one synthetic root per volume (name = volume name, no path, no payload)
 * whose children are that volume's forest. Subtree totals are precomputed per node.
 */
class FolderTree(
    val volumes: List<FolderNode<FolderStats>>,
    private val totalsByKey: Map<String, FolderTotals>,
) {
    fun totals(node: FolderNode<FolderStats>): FolderTotals = totalsByKey[keyOf(node)] ?: FolderTotals.Empty

    fun find(volumeName: String, relativePath: String): FolderNode<FolderStats>? =
        volumes.find(volumeName, relativePath)

    override fun equals(other: Any?): Boolean =
        other is FolderTree && volumes == other.volumes && totalsByKey == other.totalsByKey

    override fun hashCode(): Int = 31 * volumes.hashCode() + totalsByKey.hashCode()

    companion object {
        val Empty = FolderTree(emptyList(), emptyMap())

        /** Stable node identity: path nodes by path, path-less folders by bucket, volume roots by volume. */
        fun keyOf(node: FolderNode<FolderStats>): String = when {
            node.relativePath != null -> "${node.volumeName}|${node.relativePath}"
            node.direct != null -> "${node.volumeName}|#${node.direct?.bucketId}"
            else -> node.volumeName
        }

        fun from(forest: List<FolderNode<FolderStats>>): FolderTree {
            val totals = HashMap<String, FolderTotals>()
            fun walk(node: FolderNode<FolderStats>): FolderTotals {
                var sum = node.direct?.let(FolderTotals::of) ?: FolderTotals.Empty
                node.children.forEach { sum += walk(it) }
                totals[keyOf(node)] = sum
                return sum
            }
            val volumes = forest.groupBy { it.volumeName }.map { (volume, nodes) ->
                FolderNode<FolderStats>(volume, null, volume, null, nodes).also(::walk)
            }
            return FolderTree(volumes, totals)
        }
    }
}
