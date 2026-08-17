package com.ugallery.core.data

import android.content.Context
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.database.MediaItemEntity
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

    private fun MediaItemEntity.searchDocument() = MediaSearchDocument(
        key = MediaKey(volumeName, mediaStoreId),
        kind = if (mediaType == 3) MediaKind.Video else MediaKind.Image,
        mimeType = mimeType,
        displayName = displayName,
        bucketName = bucketDisplayName,
        timelineSortMillis = timelineSortMillis,
        generationModified = generationModified,
        favorite = isFavorite,
    )
}

class GallerySearchIndexRepository(
    context: Context,
    database: GalleryDatabase,
    databaseName: String = MediaSearchIndex.DefaultDatabase,
) : Closeable {
    private val index = AppSearchMediaIndex(context, databaseName)
    private val coordinator = SearchIndexCoordinator(
        index,
        RoomSearchDocumentSource(database),
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

    override fun close() = index.close()
}
