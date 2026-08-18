package com.ugallery.core.data

import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.database.MomentCoverEntity
import com.ugallery.core.database.MomentEntity
import com.ugallery.core.database.MomentMemberEntity
import com.ugallery.core.database.MomentMemberRow
import com.ugallery.core.database.MomentRunEntity
import com.ugallery.core.database.MomentSummaryRow
import com.ugallery.core.model.MediaKey
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import kotlin.coroutines.coroutineContext

data class MomentGenerationProgress(val processedItems: Long, val generatedMoments: Int, val complete: Boolean)

class MomentRepository(
    database: GalleryDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.momentDao()

    fun summaries(limit: Int = 20): Flow<List<MomentSummaryRow>> = dao.summaries(limit.coerceIn(1, 50))
    suspend fun members(momentId: String): List<MomentMemberRow> = dao.members(momentId)
    suspend fun rename(momentId: String, title: String): Boolean =
        title.trim().takeIf { it.isNotEmpty() }?.let { dao.rename(momentId, it.take(120), nowMillis()) == 1 } == true
    suspend fun save(momentId: String): Boolean = dao.save(momentId, nowMillis()) == 1
    suspend fun dismiss(momentId: String): Boolean = dao.dismiss(momentId, nowMillis()) == 1
    suspend fun delete(momentId: String): Boolean = dao.delete(momentId) == 1
    suspend fun setCover(momentId: String, key: MediaKey): Boolean =
        dao.setUserCover(momentId, key.volumeName, key.mediaStoreId, nowMillis())

    suspend fun reorder(momentId: String, orderedKeys: List<MediaKey>): Boolean {
        val current = dao.allMembers(momentId)
        val byKey = current.associateBy { MediaKey(it.volumeName, it.mediaStoreId) }
        if (orderedKeys.size != current.size || orderedKeys.toSet().size != current.size) return false
        val ordered = orderedKeys.map { byKey[it] ?: return false }
        return dao.reorder(momentId, ordered, nowMillis())
    }

    suspend fun generate(
        pageSize: Int = 256,
        onProgress: (MomentGenerationProgress) -> Unit = {},
    ): MomentGenerationProgress {
        require(pageSize in 64..500)
        var run = dao.run(AlgorithmVersion) ?: MomentRunEntity(
            AlgorithmVersion, UUID.randomUUID().toString(), "RUNNING",
            null, null, null, null, null, null, 0, 0, nowMillis(),
        )
        var accumulator = MomentAccumulator(
            AlgorithmVersion, run.openStartMillis, run.openEndMillis, run.openLastMillis,
            run.openItemCount, dao.stagedCandidates(AlgorithmVersion),
        )
        var generated = 0
        while (true) {
            coroutineContext.ensureActive()
            val page = dao.candidatePage(
                run.afterTimelineSortMillis, run.afterMediaStoreId, run.afterVolumeName, pageSize,
            )
            if (page.isEmpty()) {
                accumulator.finish()?.let { if (persist(it)) generated++ }
                val complete = run.copy(
                    status = "COMPLETE", openStartMillis = null, openEndMillis = null,
                    openLastMillis = null, openItemCount = 0, updatedAtMillis = nowMillis(),
                )
                dao.checkpointRun(complete, emptyList())
                return MomentGenerationProgress(complete.processedItems, generated, true).also(onProgress)
            }
            page.forEach { row -> accumulator.offer(row)?.let { if (persist(it)) generated++ } }
            val last = page.last().media
            run = run.copy(
                status = "RUNNING",
                afterTimelineSortMillis = last.timelineSortMillis,
                afterMediaStoreId = last.mediaStoreId,
                afterVolumeName = last.volumeName,
                openStartMillis = accumulator.startMillis,
                openEndMillis = accumulator.endMillis,
                openLastMillis = accumulator.lastMillis,
                openItemCount = accumulator.itemCount,
                processedItems = run.processedItems + page.size,
                updatedAtMillis = nowMillis(),
            )
            dao.checkpointRun(run, accumulator.staged())
            onProgress(MomentGenerationProgress(run.processedItems, generated, false))
            if (page.size < pageSize) continue
        }
    }

    suspend fun restartGeneration() {
        dao.deleteRun(AlgorithmVersion)
    }

    private suspend fun persist(event: MomentEvent): Boolean {
        val selected = MomentAccumulator.selectMembers(event)
        if (selected.isEmpty()) return false
        val momentId = MomentAccumulator.stableMomentId(event, selected)
        val now = nowMillis()
        val moment = MomentEntity(
            momentId, "AUTO", "SUGGESTED", AlgorithmVersion, event.startMillis, event.endMillis,
            null, "AUTO", false, now, now,
        )
        val members = selected.mapIndexed { index, candidate ->
            MomentMemberEntity(
                momentId, index, candidate.volumeName, candidate.mediaStoreId,
                candidate.generationModified, "GENERATED", candidate.score,
            )
        }
        val coverCandidate = selected.maxBy { it.score }
        return dao.replaceGeneratedMoment(
            moment,
            members,
            MomentCoverEntity(momentId, coverCandidate.volumeName, coverCandidate.mediaStoreId, false),
        )
    }

    companion object { const val AlgorithmVersion = "moments-temporal-geo-v1" }
}
