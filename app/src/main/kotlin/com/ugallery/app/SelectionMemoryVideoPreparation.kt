package com.ugallery.app

import android.content.ContentUris
import android.provider.MediaStore
import com.ugallery.core.database.MediaItemEntity
import com.ugallery.core.editing.video.MemoryVideoSource
import com.ugallery.core.mediastore.MediaStoreRecord
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.MediaKind
import java.util.UUID

/** A new identity per explicit handoff, retained by the owner across configuration changes. */
data class SelectionMemoryVideoPreparedSession(val id: String, val sources: List<MemoryVideoSource>)

/**
 * Read-only handoff to the existing local video editor. Run on IO; the owner must revalidate
 * runtime, current permission and selection after this suspending call before publishing it.
 * No selection member is silently removed or sorted. Export rechecks both generations around
 * its owned frame snapshot, so an ID reused after this handoff does not select another photo.
 */
object SelectionMemoryVideoPreparation {
    fun acceptsSelection(keys: List<MediaKey>): Boolean = keys.size in 1..120 && keys.distinct().size == keys.size

    suspend fun prepare(
        keys: List<MediaKey>,
        indexed: suspend (MediaKey) -> MediaItemEntity?,
        current: (MediaKey) -> MediaStoreRecord?,
    ): SelectionMemoryVideoPreparedSession? {
        val snapshot = keys.toList()
        if (!acceptsSelection(snapshot)) return null
        return try {
            val sources = snapshot.map { key ->
                val row = indexed(key) ?: return null
                val actual = current(key) ?: return null
                if (!matchesCurrentPhoto(key, row, actual)) return null
                MemoryVideoSource(
                    ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(key.volumeName), key.mediaStoreId),
                    actual.generationModified,
                    actual.generationAdded,
                )
            }
            SelectionMemoryVideoPreparedSession(UUID.randomUUID().toString(), sources)
        } catch (_: SecurityException) {
            null
        }
    }

    internal fun matchesCurrentPhoto(key: MediaKey, row: MediaItemEntity, actual: MediaStoreRecord): Boolean =
        row.volumeName == key.volumeName && row.mediaStoreId == key.mediaStoreId && actual.key == key &&
            row.isAccessible && !row.isTrashed && !actual.isTrashed && row.mediaType == 1 &&
            actual.kind == MediaKind.Image && row.generationModified == actual.generationModified &&
            row.generationAdded == actual.generationAdded
}
