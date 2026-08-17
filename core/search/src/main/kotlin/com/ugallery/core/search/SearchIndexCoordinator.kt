package com.ugallery.core.search

import android.content.Context
import com.ugallery.core.model.MediaKey

fun interface SearchDocumentSource {
    suspend fun page(afterExclusive: MediaKey?, limit: Int): List<MediaSearchDocument>
}

data class SearchRebuildCheckpoint(
    val afterExclusive: MediaKey?,
    val indexedCount: Long,
)

interface SearchRebuildStateStore {
    fun read(): SearchRebuildCheckpoint?
    fun write(checkpoint: SearchRebuildCheckpoint)
    fun clear()
}

class SharedPreferencesSearchRebuildStateStore(
    context: Context,
    databaseName: String = MediaSearchIndex.DefaultDatabase,
) : SearchRebuildStateStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        "search-rebuild-$databaseName",
        Context.MODE_PRIVATE,
    )

    override fun read(): SearchRebuildCheckpoint? {
        if (!preferences.getBoolean(Key.Running, false)) return null
        val volume = preferences.getString(Key.Volume, null)
        val id = preferences.getLong(Key.Id, Long.MIN_VALUE)
        return SearchRebuildCheckpoint(
            if (volume == null || id == Long.MIN_VALUE) null else MediaKey(volume, id),
            preferences.getLong(Key.Count, 0),
        )
    }

    override fun write(checkpoint: SearchRebuildCheckpoint) {
        preferences.edit()
            .putBoolean(Key.Running, true)
            .putString(Key.Volume, checkpoint.afterExclusive?.volumeName)
            .putLong(Key.Id, checkpoint.afterExclusive?.mediaStoreId ?: Long.MIN_VALUE)
            .putLong(Key.Count, checkpoint.indexedCount)
            .commit()
    }

    override fun clear() {
        preferences.edit().clear().commit()
    }

    private object Key {
        const val Running = "running"
        const val Volume = "volume"
        const val Id = "id"
        const val Count = "count"
    }
}

sealed interface SearchRebuildResult {
    data class Complete(val indexedCount: Long) : SearchRebuildResult
    data class Paused(val checkpoint: SearchRebuildCheckpoint) : SearchRebuildResult
}

class SearchIndexCoordinator(
    private val index: MediaSearchIndex,
    private val source: SearchDocumentSource,
    private val state: SearchRebuildStateStore,
) {
    suspend fun onMediaChanged(documents: List<MediaSearchDocument>) {
        documents.chunked(MediaSearchIndex.MaxBatchSize).forEach { index.put(it) }
    }

    suspend fun rebuild(
        restart: Boolean = false,
        shouldContinue: suspend () -> Boolean = { true },
    ): SearchRebuildResult {
        index.ensureSchema()
        val restored = state.read()
        var checkpoint: SearchRebuildCheckpoint
        if (restart || restored == null) {
            index.clear()
            checkpoint = SearchRebuildCheckpoint(null, 0)
            state.write(checkpoint)
        } else checkpoint = restored
        while (shouldContinue()) {
            val page = source.page(checkpoint.afterExclusive, MediaSearchIndex.MaxBatchSize)
            if (page.isEmpty()) {
                state.clear()
                return SearchRebuildResult.Complete(checkpoint.indexedCount)
            }
            index.put(page)
            checkpoint = SearchRebuildCheckpoint(
                page.last().key,
                checkpoint.indexedCount + page.size,
            )
            state.write(checkpoint)
        }
        return SearchRebuildResult.Paused(checkpoint)
    }

    suspend fun onPermissionRevoked(keys: List<MediaKey>) {
        keys.chunked(MediaSearchIndex.MaxBatchSize).forEach { index.remove(it) }
    }

    suspend fun onVolumeUnavailable(volumeName: String) = index.purgeVolume(volumeName)

    suspend fun deleteAndReset() {
        index.clear()
        state.clear()
    }
}
