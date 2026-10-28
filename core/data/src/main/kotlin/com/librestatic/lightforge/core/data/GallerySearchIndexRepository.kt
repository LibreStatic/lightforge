package com.librestatic.lightforge.core.data

import android.content.Context
import com.librestatic.lightforge.core.database.GalleryDatabase
import com.librestatic.lightforge.core.database.SearchRebuildRow
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.search.AppSearchMediaIndex
import com.librestatic.lightforge.core.search.MediaSearchDocument
import com.librestatic.lightforge.core.search.MediaSearchIndex
import com.librestatic.lightforge.core.search.SearchDocumentSource
import com.librestatic.lightforge.core.search.SearchIndexCoordinator
import com.librestatic.lightforge.core.search.SearchIndexJournal
import com.librestatic.lightforge.core.search.SearchSyncResult
import com.librestatic.lightforge.core.search.SearchRebuildResult
import com.librestatic.lightforge.core.search.SharedPreferencesSearchRebuildStateStore
import kotlinx.coroutines.flow.Flow
import java.io.Closeable

class RoomSearchDocumentSource(database: GalleryDatabase) : SearchDocumentSource {
    private val dao = database.libraryDao()

    override suspend fun page(afterExclusive: MediaKey?, limit: Int): List<MediaSearchDocument> {
        require(limit in 1..MediaSearchIndex.MaxBatchSize)
        return dao.searchRebuildPage(
            afterVolume = afterExclusive?.volumeName,
            afterId = afterExclusive?.mediaStoreId ?: Long.MIN_VALUE,
            limit = limit,
        ).map { it.searchDocument() }
    }

    suspend fun document(key: MediaKey): MediaSearchDocument? =
        dao.searchRebuildRow(key.volumeName, key.mediaStoreId)?.searchDocument()

    internal suspend fun documents(keys: List<MediaKey>): List<MediaSearchDocument> =
        keys.groupBy(MediaKey::volumeName).flatMap { (volume, volumeKeys) ->
            volumeKeys.map(MediaKey::mediaStoreId).chunked(MediaSearchIndex.MaxBatchSize).flatMap {
                dao.searchRebuildRows(volume, it).map { row -> row.searchDocument() }
            }
        }

    private fun SearchRebuildRow.searchDocument() = MediaSearchDocument(
        key = MediaKey(media.volumeName, media.mediaStoreId),
        kind = if (media.mediaType == 3) MediaKind.Video else MediaKind.Image,
        mimeType = media.mimeType,
        displayName = media.displayName,
        bucketName = media.bucketDisplayName,
        timelineSortMillis = media.timelineSortMillis,
        generationModified = media.generationModified,
        favorite = media.isFavorite,
        width = media.width,
        height = media.height,
        ocrText = ocrText,
        canonicalLabels = canonicalLabelsCsv?.split(',').orEmpty(),
        labelModelVersion = labelModelVersion.versionCode(),
        ocrModelVersion = ocrModelVersion.versionCode(),
        durationMillis = media.durationMillis,
    )

    private fun String?.versionCode(): Long {
        val parts = this?.let { Regex("\\d+").findAll(it).map(MatchResult::value).toList() }.orEmpty()
        return when {
            parts.size >= 3 -> {
                val (major, minor, patch) = parts.takeLast(3).map(String::toLong)
                major * 1_000 + minor * 100 + patch
            }
            else -> 0L
        }
    }
}

/** The trigger-maintained `search_index_journal` table behind the incremental index. */
class RoomSearchIndexJournal(
    database: GalleryDatabase,
    private val source: RoomSearchDocumentSource,
) : SearchIndexJournal {
    private val dao = database.libraryDao()

    override suspend fun maxSeq(): Long = dao.searchJournalMaxSeq()

    override suspend fun keys(throughSeq: Long, limit: Int): List<MediaKey> =
        dao.searchJournalKeys(throughSeq, limit).map { MediaKey(it.volumeName, it.mediaStoreId) }

    override suspend fun documents(keys: List<MediaKey>): List<MediaSearchDocument> = source.documents(keys)

    override suspend fun acknowledge(throughSeq: Long, keys: List<MediaKey>) {
        keys.groupBy(MediaKey::volumeName).forEach { (volume, volumeKeys) ->
            volumeKeys.map(MediaKey::mediaStoreId).chunked(MediaSearchIndex.MaxBatchSize).forEach {
                dao.deleteSearchJournal(throughSeq, volume, it)
            }
        }
    }

    override suspend fun clear(throughSeq: Long) = dao.clearSearchJournal(throughSeq)

    override suspend fun eligibleCount(): Int = dao.searchEligibleCount()
}

class GallerySearchIndexRepository(
    context: Context,
    database: GalleryDatabase,
    databaseName: String = MediaSearchIndex.DefaultDatabase,
) : Closeable {
    private val index = AppSearchMediaIndex(context, databaseName)
    private val source = RoomSearchDocumentSource(database)
    private val coordinator = SearchIndexCoordinator(
        index,
        source,
        SharedPreferencesSearchRebuildStateStore(context, databaseName),
        RoomSearchIndexJournal(database, source),
    )
    private val dao = database.libraryDao()

    /** Emits the number of journalled changes the index has not absorbed yet. */
    fun pendingChangeCount(): Flow<Int> = dao.observeSearchJournalCount()

    fun hasInterruptedRebuild(): Boolean = coordinator.hasInterruptedRebuild()

    suspend fun synchronize(
        verifyCount: Boolean,
        onRebuild: suspend (reason: String) -> Unit = {},
    ): SearchSyncResult = coordinator.synchronize(verifyCount, onRebuild)

    suspend fun rebuild(
        restart: Boolean = false,
        shouldContinue: suspend () -> Boolean = { true },
    ): SearchRebuildResult = coordinator.rebuild(restart, shouldContinue)

    suspend fun purgeRevoked(keys: List<MediaKey>) = coordinator.onPermissionRevoked(keys)

    suspend fun indexChanged(documents: List<MediaSearchDocument>) = coordinator.onMediaChanged(documents)

    suspend fun purgeUnavailableVolume(volumeName: String) = coordinator.onVolumeUnavailable(volumeName)

    suspend fun deleteDerivedIndex() = coordinator.deleteAndReset()

    override fun close() = index.close()
}
