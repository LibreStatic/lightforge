package com.ugallery.core.data

import android.content.Context
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.database.SearchRebuildRow
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.MediaKind
import com.ugallery.core.search.AppSearchMediaIndex
import com.ugallery.core.search.MediaSearchDocument
import com.ugallery.core.search.MediaSearchIndex
import com.ugallery.core.search.SearchDocumentSource
import com.ugallery.core.search.SearchIndexCoordinator
import com.ugallery.core.search.SearchRebuildResult
import com.ugallery.core.search.SharedPreferencesSearchRebuildStateStore
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

    private fun SearchRebuildRow.searchDocument() = MediaSearchDocument(
        key = MediaKey(media.volumeName, media.mediaStoreId),
        kind = if (media.mediaType == 3) MediaKind.Video else MediaKind.Image,
        mimeType = media.mimeType,
        displayName = media.displayName,
        bucketName = media.bucketDisplayName,
        timelineSortMillis = media.timelineSortMillis,
        generationModified = media.generationModified,
        favorite = media.isFavorite,
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
    )

    suspend fun rebuild(
        restart: Boolean = false,
        shouldContinue: suspend () -> Boolean = { true },
    ): SearchRebuildResult = coordinator.rebuild(restart, shouldContinue)

    suspend fun purgeRevoked(keys: List<MediaKey>) = coordinator.onPermissionRevoked(keys)

    suspend fun indexChanged(documents: List<MediaSearchDocument>) = coordinator.onMediaChanged(documents)

    suspend fun purgeUnavailableVolume(volumeName: String) = coordinator.onVolumeUnavailable(volumeName)

    suspend fun deleteDerivedIndex() = coordinator.deleteAndReset()

    suspend fun indexKey(key: MediaKey) {
        val document = source.document(key)
        if (document == null) coordinator.onPermissionRevoked(listOf(key))
        else coordinator.onMediaChanged(listOf(document))
    }

    override fun close() = index.close()
}
