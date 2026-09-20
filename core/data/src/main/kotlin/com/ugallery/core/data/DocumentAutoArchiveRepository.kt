package com.ugallery.core.data

import androidx.room.withTransaction
import com.ugallery.core.database.*
import com.ugallery.core.model.MediaKey
import java.util.UUID
import kotlinx.coroutines.flow.map

/** A snapshot, not authorization to operate on different or newly arrived media. */
data class DocumentArchivePreview
internal constructor(
    val category: DocumentCategory,
    val minimumAgeDays: Int,
    internal val revision: String,
    internal val cutoff: Long,
    val items: List<DocumentArchiveCandidate>,
    val hasMore: Boolean,
)

class DocumentArchivePreviewChanged : IllegalStateException("DocumentArchivePreviewChanged")

class DocumentAutoArchiveRepository(
    private val database: GalleryDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.documentArchiveDao()

    fun state() = dao.observeRule().map { it ?: DocumentArchiveRuleEntity() }

    suspend fun preview(category: DocumentCategory, minimumAgeDays: Int): DocumentArchivePreview {
        require(category != DocumentCategory.Excluded)
        require(minimumAgeDays in Ages)
        return database.withTransaction {
            val cutoff = (now() - minimumAgeDays * DayMillis).coerceAtLeast(0)
            val rows = dao.candidates(category.name, cutoff)
            DocumentArchivePreview(
                category,
                minimumAgeDays,
                rule().revision,
                cutoff,
                rows.take(BatchSize),
                rows.size > BatchSize,
            )
        }
    }

    /**
     * Archives only the reviewed snapshot now; explicit consent also enables future bounded runs.
     */
    suspend fun enable(preview: DocumentArchivePreview, keep: Set<MediaKey> = emptySet()): Int =
        database.withTransaction {
            require(preview.category != DocumentCategory.Excluded && preview.minimumAgeDays in Ages)
            require(keep.all { key -> preview.items.any { it.key() == key } })
            val current = rule()
            val rows = dao.candidates(preview.category.name, preview.cutoff)
            if (
                current.revision != preview.revision ||
                    rows.take(BatchSize) != preview.items ||
                    (rows.size > BatchSize) != preview.hasMore
            )
                throw DocumentArchivePreviewChanged()
            val updated =
                current.copy(
                    enabled = true,
                    category = preview.category.name,
                    minimumAgeDays = preview.minimumAgeDays,
                    afterSortMillis = null,
                    afterMediaStoreId = null,
                    afterVolumeName = null,
                    revision = UUID.randomUUID().toString(),
                )
            apply(updated, preview.items, keep)
        }

    suspend fun pause() =
        database.withTransaction {
            dao.putRule(rule().copy(enabled = false, revision = UUID.randomUUID().toString()))
        }

    /**
     * Snapshot before external source checks; commit revalidates the rule and every DB candidate.
     */
    suspend fun runScheduled(
        sourceAvailable: suspend (DocumentArchiveCandidate) -> Boolean = { true }
    ): Int {
        val captured =
            database.withTransaction {
                val rule = rule()
                if (!rule.enabled) return@withTransaction null
                val cutoff = (now() - rule.minimumAgeDays * DayMillis).coerceAtLeast(0)
                val rows =
                    dao.candidates(
                        rule.category,
                        cutoff,
                        rule.afterSortMillis,
                        rule.afterMediaStoreId,
                        rule.afterVolumeName,
                    )
                rule to
                    DocumentArchivePreview(
                        DocumentCategory.valueOf(rule.category),
                        rule.minimumAgeDays,
                        rule.revision,
                        cutoff,
                        rows.take(BatchSize),
                        rows.size > BatchSize,
                    )
            } ?: return 0
        val (expectedRule, snapshot) = captured
        val available = snapshot.items.filter { sourceAvailable(it) }
        return database.withTransaction {
            val current = rule()
            if (!current.enabled || current != expectedRule) return@withTransaction 0
            val stillMatching =
                dao.candidates(
                        snapshot.category.name,
                        snapshot.cutoff,
                        current.afterSortMillis,
                        current.afterMediaStoreId,
                        current.afterVolumeName,
                    )
                    .toSet()
            // Advance even past unavailable sources so stale index entries cannot starve later
            // photos.
            // Wrap after the final window to retry temporarily unavailable sources on a later run.
            val cursor = snapshot.items.lastOrNull().takeIf { snapshot.hasMore }
            apply(
                current.copy(
                    afterSortMillis = cursor?.timelineSortMillis,
                    afterMediaStoreId = cursor?.mediaStoreId,
                    afterVolumeName = cursor?.volumeName,
                ),
                available.filter { it in stillMatching },
                emptySet(),
            )
        }
    }

    /**
     * Durable undo pauses the rule; changed sources or later manual archive states are untouched.
     */
    suspend fun undoLastRun(): Int =
        database.withTransaction {
            val current = rule()
            val history = current.lastRunId?.let { dao.history(it) }.orEmpty()
            var restored = 0
            for (item in history) {
                val row = database.documentDao().get(item.volumeName, item.mediaStoreId) ?: continue
                if (
                    row.media.generationModified != item.generationModified ||
                        database.documentDao().archivedAt(item.volumeName, item.mediaStoreId) !=
                            item.writtenAtMillis
                )
                    continue
                database.libraryDao().deleteArchived(item.volumeName, item.mediaStoreId)
                restored++
            }
            dao.putRule(
                current.copy(
                    enabled = false,
                    revision = UUID.randomUUID().toString(),
                    lastRunId = null,
                    lastRunCount = 0,
                )
            )
            if (restored > 0)
                GalleryActivityRepository(database, now)
                    .record(GalleryActivityType.Unarchived, restored.toLong())
            restored
        }

    private suspend fun rule() = dao.rule() ?: DocumentArchiveRuleEntity()

    private suspend fun apply(
        rule: DocumentArchiveRuleEntity,
        rows: List<DocumentArchiveCandidate>,
        keep: Set<MediaKey>,
    ): Int {
        check(rows.size <= BatchSize)
        val runId = UUID.randomUUID().toString()
        val time = now()
        var count = 0
        for (row in rows) {
            val written = if (row.key() in keep) null else time
            dao.putHistory(
                DocumentArchiveHistoryEntity(
                    row.volumeName,
                    row.mediaStoreId,
                    row.generationModified,
                    runId,
                    written,
                )
            )
            if (written != null) {
                database
                    .libraryDao()
                    .upsertArchived(ArchivedMediaEntity(row.volumeName, row.mediaStoreId, written))
                count++
            }
        }
        dao.putRule(
            if (count > 0) rule.copy(lastRunId = runId, lastRunMillis = time, lastRunCount = count)
            else rule
        )
        if (count > 0)
            GalleryActivityRepository(database, now)
                .record(GalleryActivityType.Archived, count.toLong())
        return count
    }

    companion object {
        const val BatchSize = 100
        const val DayMillis = 86_400_000L
        val Ages = listOf(0, 7, 30, 90)
    }
}

fun DocumentArchiveCandidate.key() = MediaKey(volumeName, mediaStoreId)
