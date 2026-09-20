package com.ugallery.core.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.room.withTransaction
import com.ugallery.core.database.*
import com.ugallery.core.model.MediaKey
import java.util.UUID

/** Explicit user stacks survive replaceable similarity features and cluster IDs. */
data class PhotoStackPreview
internal constructor(val suggestion: PhotoStackSuggestion, val photos: List<PhotoStackPhoto>)

class PhotoStackChanged : IllegalStateException("PhotoStackChanged")

class GalleryPhotoStackRepository(
    private val database: GalleryDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.photoStackDao()

    fun saved() =
        Pager(
                PagingConfig(20, initialLoadSize = 40, enablePlaceholders = false, maxSize = 120),
                pagingSourceFactory = dao::pages,
            )
            .flow

    fun suggestions() =
        Pager(
                PagingConfig(20, initialLoadSize = 40, enablePlaceholders = false, maxSize = 120),
                pagingSourceFactory = dao::suggestions,
            )
            .flow

    fun separated() =
        Pager(
                PagingConfig(40, initialLoadSize = 60, enablePlaceholders = false, maxSize = 200),
                pagingSourceFactory = dao::separated,
            )
            .flow

    fun count() = dao.count()

    fun observe(id: String) = dao.observe(id)

    fun members(id: String) = dao.members(id)

    suspend fun preview(suggestion: PhotoStackSuggestion): PhotoStackPreview =
        database.withTransaction {
            val photos = dao.suggestionMembers(suggestion.clusterId, suggestion.algorithmVersion)
            if (photos.size !in 2..MaxMembers) throw PhotoStackChanged()
            PhotoStackPreview(suggestion, photos)
        }

    suspend fun save(preview: PhotoStackPreview, cover: MediaKey): String =
        database.withTransaction {
            val current =
                dao.suggestionMembers(
                    preview.suggestion.clusterId,
                    preview.suggestion.algorithmVersion,
                )
            fun signature(photos: List<PhotoStackPhoto>) =
                photos.map { it.key() to it.media.generationModified }
            if (signature(current) != signature(preview.photos)) throw PhotoStackChanged()
            create(current.map { it.key() }, cover)
        }

    suspend fun create(
        keys: List<MediaKey>,
        cover: MediaKey = keys.first(),
        title: String? = null,
    ): String =
        database.withTransaction {
            require(
                keys.size in 2..MaxMembers && keys.distinct().size == keys.size && cover in keys
            )
            for (key in keys) {
                checkNotNull(database.documentDao().get(key.volumeName, key.mediaStoreId)) {
                    "StackPhotoUnavailable"
                }
                check(dao.membership(key.volumeName, key.mediaStoreId) == null) {
                    "PhotoAlreadyStacked"
                }
            }
            val time = now()
            val id = UUID.randomUUID().toString()
            dao.insert(
                PhotoStackEntity(
                    id,
                    normalizeTitle(title),
                    cover.volumeName,
                    cover.mediaStoreId,
                    UUID.randomUUID().toString(),
                    time,
                    time,
                )
            )
            dao.insertMembers(
                keys.mapIndexed { index, key ->
                    PhotoStackMemberEntity(key.volumeName, key.mediaStoreId, id, index)
                }
            )
            id
        }

    suspend fun setCover(id: String, revision: String, key: MediaKey) =
        database.withTransaction {
            val stack = current(id, revision)
            check(dao.membership(key.volumeName, key.mediaStoreId)?.stackId == id)
            checkNotNull(database.documentDao().get(key.volumeName, key.mediaStoreId))
            dao.update(
                stack.copy(
                    coverVolumeName = key.volumeName,
                    coverMediaStoreId = key.mediaStoreId,
                    revision = UUID.randomUUID().toString(),
                    updatedAtMillis = now(),
                )
            )
        }

    suspend fun rename(id: String, revision: String, title: String?) =
        database.withTransaction {
            dao.update(
                current(id, revision)
                    .copy(
                        title = normalizeTitle(title),
                        revision = UUID.randomUUID().toString(),
                        updatedAtMillis = now(),
                    )
            )
        }

    /** Separating changes only references, never files, favorites, archives or album membership. */
    suspend fun separate(id: String, revision: String, key: MediaKey): Boolean =
        database.withTransaction {
            val stack = current(id, revision)
            check(dao.membership(key.volumeName, key.mediaStoreId)?.stackId == id)
            dao.exclude(PhotoStackExclusionEntity(key.volumeName, key.mediaStoreId, now()))
            dao.removeMember(key.volumeName, key.mediaStoreId)
            val remaining = dao.rawMembers(id)
            if (remaining.size < 2) {
                dao.dissolve(id)
                false
            } else {
                val cover =
                    remaining.firstOrNull {
                        it.volumeName == stack.coverVolumeName &&
                            it.mediaStoreId == stack.coverMediaStoreId
                    } ?: remaining.first()
                dao.update(
                    stack.copy(
                        coverVolumeName = cover.volumeName,
                        coverMediaStoreId = cover.mediaStoreId,
                        revision = UUID.randomUUID().toString(),
                        updatedAtMillis = now(),
                    )
                )
                true
            }
        }

    suspend fun dissolve(id: String, revision: String) =
        database.withTransaction {
            current(id, revision)
            dao.rawMembers(id).forEach {
                dao.exclude(PhotoStackExclusionEntity(it.volumeName, it.mediaStoreId, now()))
            }
            dao.dissolve(id)
        }

    suspend fun allowSuggestion(key: MediaKey) =
        database.withTransaction { dao.allowSuggestion(key.volumeName, key.mediaStoreId) }

    private suspend fun current(id: String, revision: String): PhotoStackEntity {
        val stack = dao.get(id)
        if (stack == null || stack.revision != revision) throw PhotoStackChanged()
        return stack
    }

    companion object {
        const val MaxMembers = 500

        fun normalizeTitle(title: String?): String? {
            val normalized = title?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }
            require(normalized == null || normalized.length <= 80)
            return normalized
        }
    }
}

fun PhotoStackPhoto.key() = MediaKey(media.volumeName, media.mediaStoreId)
