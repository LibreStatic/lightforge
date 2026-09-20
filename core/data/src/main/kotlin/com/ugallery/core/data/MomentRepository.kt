package com.ugallery.core.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.room.withTransaction
import com.ugallery.core.database.NativeGeneratedMomentIdSql
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.database.MomentCoverEntity
import com.ugallery.core.database.MomentDiscoveryChanged
import com.ugallery.core.database.MomentDiscoverySchema
import com.ugallery.core.database.MomentEntity
import com.ugallery.core.database.MomentMemberEntity
import com.ugallery.core.database.MomentMemberRow
import com.ugallery.core.database.MomentRunEntity
import com.ugallery.core.database.MomentSummaryRow
import com.ugallery.core.model.MediaKey
import java.util.UUID
import java.util.WeakHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class MomentGenerationProgress(
    val processedItems: Long,
    val generatedMoments: Int,
    val complete: Boolean,
)

enum class MemoriesFilter(val state: String?) {
    All(null),
    Saved("SAVED"),
    Suggested("SUGGESTED"),
}

class MomentRepository(
    private val database: GalleryDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.momentDao()
    private val discovery = database.momentDiscoveryDao()
    private val generationMutex: Mutex
        get() =
            synchronized(generationLocks) {
                if (database.openHelper.databaseName == null)
                    generationLocks.getOrPut(database) { Mutex() }
                else
                    fileGenerationLocks.getOrPut(
                        java.io.File(database.openHelper.writableDatabase.path).canonicalPath
                    ) {
                        Mutex()
                    }
            }

    fun browserCover(key: MediaKey) = discovery.observeSource(key.volumeName, key.mediaStoreId)

    fun browser(filter: MemoriesFilter = MemoriesFilter.All) =
        Pager(
                PagingConfig(20, initialLoadSize = 40, enablePlaceholders = false, maxSize = 120),
                pagingSourceFactory = { browserSource(filter) },
            )
            .flow

    internal fun browserSource(
        filter: MemoriesFilter = MemoriesFilter.All
    ): PagingSource<Int, MomentSummaryRow> {
        val source = dao.browser(filter.state)
        return object : PagingSource<Int, MomentSummaryRow>() {
            init {
                source.registerInvalidatedCallback { invalidate() }
                registerInvalidatedCallback { source.invalidate() }
            }

            override fun getRefreshKey(state: PagingState<Int, MomentSummaryRow>) =
                source.getRefreshKey(state)

            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MomentSummaryRow> {
                // Room Paging owns its read transaction/context. Holding a writer transaction while
                // awaiting source.load deadlocks its initial invalidation/refresh handshake.
                database.withTransaction {
                    database.memoryExclusionDao().captureCurrentPersonMatches()
                }
                return source.load(params)
            }
        }
    }

    fun summaries(limit: Int = 20): Flow<List<MomentSummaryRow>> =
        dao.summaries(limit.coerceIn(1, 50)).map {
            database.withTransaction {
                database.memoryExclusionDao().captureCurrentPersonMatches()
                dao.summarySnapshot(limit.coerceIn(1, 50))
            }
        }

    fun observeMoment(momentId: String): Flow<MomentEntity?> = dao.observeMoment(momentId)

    fun observeMembers(momentId: String): Flow<List<MomentMemberRow>> =
        dao.observeMembers(momentId).map { members(momentId) }

    suspend fun reorderVisible(momentId: String, orderedKeys: List<MediaKey>): Boolean =
        dao.reorderVisible(
            momentId,
            orderedKeys.map { it.volumeName to it.mediaStoreId },
            nowMillis(),
        )

    suspend fun members(momentId: String): List<MomentMemberRow> =
        database.withTransaction {
            database.memoryExclusionDao().captureCurrentPersonMatches()
            dao.members(momentId)
        }

    suspend fun rename(momentId: String, title: String): Boolean =
        title
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let { dao.rename(momentId, it.take(120), nowMillis()) == 1 } == true

    suspend fun save(momentId: String): Boolean = dao.save(momentId, nowMillis()) == 1

    suspend fun dismiss(momentId: String): Boolean = dao.dismiss(momentId, nowMillis()) == 1

    suspend fun delete(momentId: String): Boolean = dao.delete(momentId) == 1

    suspend fun setCover(momentId: String, key: MediaKey): Boolean =
        dao.setUserCover(momentId, key.volumeName, key.mediaStoreId, nowMillis())

    suspend fun reorder(momentId: String, orderedKeys: List<MediaKey>): Boolean {
        val current = dao.allMembers(momentId)
        val byKey = current.associateBy { MediaKey(it.volumeName, it.mediaStoreId) }
        if (orderedKeys.size != current.size || orderedKeys.toSet().size != current.size)
            return false
        val ordered = orderedKeys.map { byKey[it] ?: return false }
        return dao.reorder(momentId, ordered, nowMillis())
    }

    suspend fun generate(
        pageSize: Int = 256,
        onProgress: (MomentGenerationProgress) -> Unit = {},
    ): MomentGenerationProgress =
        generationMutex.withLock {
            prepareDiscovery()
            generateStable(pageSize, onProgress)
        }

    private suspend fun prepareDiscovery() {
        // Existing direct Room clients may not install the production factory callback. Initial
        // inputs still receive revision zero; subsequent real changes must be observed durably.
        MomentDiscoverySchema.install(database.openHelper.writableDatabase)
        database.withTransaction {
            // Adopt only our historical automatic heuristic. Keep IDs and every user decision;
            // imported/manual stories retain their own algorithm provenance.
            database.openHelper.writableDatabase.execSQL(
                "UPDATE moments SET algorithmVersion=? WHERE origin='AUTO' AND algorithmVersion=? AND " + NativeGeneratedMomentIdSql,
                arrayOf(AlgorithmVersion, LegacyAlgorithmVersion),
            )
            // A COMPLETE v1 checkpoint must not suppress the corrected source-name policy.
            // Foreign-key cascades remove its staged candidates/seen rows, not saved stories.
            dao.deleteRun(LegacyAlgorithmVersion)
        }
    }

    private suspend fun generateStable(
        pageSize: Int,
        onProgress: (MomentGenerationProgress) -> Unit,
    ): MomentGenerationProgress {
        while (true) {
            try {
                return generatePass(pageSize, onProgress)
            } catch (_: MomentDiscoveryChanged) {
                coroutineContext.ensureActive()
            }
        }
    }

    private suspend fun generatePass(
        pageSize: Int,
        onProgress: (MomentGenerationProgress) -> Unit,
    ): MomentGenerationProgress {
        require(pageSize in 64..500)
        val revision = discovery.discoveryRevision()
        val prior = dao.run(AlgorithmVersion)
        if (prior != null && (prior.inputRevision != revision || prior.status == "COMPLETE"))
            dao.deleteRun(AlgorithmVersion)
        var run =
            dao.run(AlgorithmVersion)
                ?: MomentRunEntity(
                    AlgorithmVersion,
                    UUID.randomUUID().toString(),
                    "RUNNING",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    0,
                    0,
                    nowMillis(),
                    inputRevision = revision,
                )
        database.withTransaction {
            dao.upsertRun(run)
            discovery.assertCurrent(run)
        }
        var accumulator =
            MomentAccumulator(
                AlgorithmVersion,
                run.openStartMillis,
                run.openEndMillis,
                run.openLastMillis,
                run.openItemCount,
                dao.stagedCandidates(AlgorithmVersion),
                run.openLastLatitude,
                run.openLastLongitude,
            )
        var generated = 0
        while (true) {
            coroutineContext.ensureActive()
            val page =
                dao.candidatePage(
                    run.afterTimelineSortMillis,
                    run.afterMediaStoreId,
                    run.afterVolumeName,
                    pageSize,
                )
            if (page.isEmpty()) {
                accumulator.finish()?.let { if (persist(it, run)) generated++ }
                val complete =
                    run.copy(
                        status = "COMPLETE",
                        openStartMillis = null,
                        openEndMillis = null,
                        openLastMillis = null,
                        openItemCount = 0,
                        openLastLatitude = null,
                        openLastLongitude = null,
                        updatedAtMillis = nowMillis(),
                    )
                discovery.complete(complete)
                return MomentGenerationProgress(complete.processedItems, generated, true)
                    .also(onProgress)
            }
            page.forEach { row ->
                accumulator.offer(row)?.let { if (persist(it, run)) generated++ }
            }
            val last = page.last().media
            run =
                run.copy(
                    status = "RUNNING",
                    afterTimelineSortMillis = last.timelineSortMillis,
                    afterMediaStoreId = last.mediaStoreId,
                    afterVolumeName = last.volumeName,
                    openStartMillis = accumulator.startMillis,
                    openEndMillis = accumulator.endMillis,
                    openLastMillis = accumulator.lastMillis,
                    openItemCount = accumulator.itemCount,
                    openLastLatitude = accumulator.lastLatitude,
                    openLastLongitude = accumulator.lastLongitude,
                    processedItems = run.processedItems + page.size,
                    updatedAtMillis = nowMillis(),
                )
            database.withTransaction {
                discovery.assertCurrent(run)
                dao.checkpointRun(run, accumulator.staged())
            }
            onProgress(MomentGenerationProgress(run.processedItems, generated, false))
            if (page.size < pageSize) continue
        }
    }

    /** Reconcile only after a real indexed input change; old imports restart from the beginning. */
    suspend fun generateIfNeeded(
        pageSize: Int = 256,
        onProgress: (MomentGenerationProgress) -> Unit = {},
    ): MomentGenerationProgress? =
        generationMutex.withLock {
            prepareDiscovery()
            val run = dao.run(AlgorithmVersion)
            if (run?.status == "COMPLETE" && run.inputRevision == discovery.discoveryRevision())
                null
            else generateStable(pageSize, onProgress)
        }

    suspend fun restartGeneration() =
        generationMutex.withLock {
            dao.deleteRun(AlgorithmVersion)
            Unit
        }

    private suspend fun persist(event: MomentEvent, run: MomentRunEntity): Boolean {
        val selected = MomentAccumulator.selectMembers(event)
        if (selected.isEmpty()) return false
        val momentId = MomentAccumulator.stableMomentId(event, selected)
        val now = nowMillis()
        val moment =
            MomentEntity(
                momentId,
                "AUTO",
                "SUGGESTED",
                AlgorithmVersion,
                event.startMillis,
                event.endMillis,
                null,
                "AUTO",
                false,
                now,
                now,
            )
        val members =
            selected.mapIndexed { index, candidate ->
                MomentMemberEntity(
                    momentId,
                    index,
                    candidate.volumeName,
                    candidate.mediaStoreId,
                    candidate.generationModified,
                    "GENERATED",
                    candidate.score,
                )
            }
        val coverCandidate = selected.maxBy { it.score }
        return discovery.reconcile(
            run,
            moment,
            members,
            MomentCoverEntity(
                momentId,
                coverCandidate.volumeName,
                coverCandidate.mediaStoreId,
                false,
            ),
            event.candidates,
        )
    }

    companion object {
        private val generationLocks = WeakHashMap<GalleryDatabase, Mutex>()
        private val fileGenerationLocks = mutableMapOf<String, Mutex>()
        private const val LegacyAlgorithmVersion = "moments-temporal-geo-v1"
        const val AlgorithmVersion = "moments-temporal-geo-v2"
    }
}
