package com.librestatic.lightforge.core.ml

import com.librestatic.lightforge.core.database.GalleryDatabase
import com.librestatic.lightforge.core.selection.MediaQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class CleanupSummary(
    val exactGroupCount: Long,
    val exactRecoverableBytes: Long,
    val largeVideoCount: Long,
    val largeVideoBytes: Long,
    val screenshotCount: Long,
    val blurryCandidateCount: Long,
)

class CleanupRepository(database: GalleryDatabase) {
    private val dao = database.libraryDao()

    val summary: Flow<CleanupSummary> = dao.cleanupSummaryFlow(
        ExactDuplicateMlEngine.HashVersion,
        SimilarityMlEngine.AlgorithmVersion,
        LargeVideoMinimumBytes,
        BlurryMaximumScore,
    ).map { row ->
        CleanupSummary(
            row.exactGroupCount,
            row.exactRecoverableBytes,
            row.largeVideoCount,
            row.largeVideoBytes,
            row.screenshotCount,
            row.blurryCandidateCount,
        )
    }

    fun largeVideosQuery() = MediaQuery(
        scope = MediaQuery.Scope.LargeVideos(LargeVideoMinimumBytes),
        kindFilter = MediaQuery.KindFilter.Videos,
    )

    fun screenshotsQuery() = MediaQuery(
        scope = MediaQuery.Scope.Screenshots,
        kindFilter = MediaQuery.KindFilter.Images,
    )

    fun blurryCandidatesQuery() = MediaQuery(
        scope = MediaQuery.Scope.BlurryCandidates(BlurryMaximumScore, SimilarityMlEngine.AlgorithmVersion),
        kindFilter = MediaQuery.KindFilter.Images,
    )

    fun exactDuplicateGroupQuery(group: ExactDuplicateGroup) = MediaQuery(
        scope = MediaQuery.Scope.ExactDuplicateGroup(
            group.sha256,
            group.sizeBytes,
            ExactDuplicateMlEngine.HashVersion,
        ),
    )

    companion object {
        const val LargeVideoMinimumBytes = 100L * 1_024 * 1_024
        const val BlurryMaximumScore = 20f
    }
}
