package com.librestatic.lightforge.core.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.librestatic.lightforge.core.database.AlbumMediaFilter
import com.librestatic.lightforge.core.database.AlbumPagingSource
import com.librestatic.lightforge.core.database.AlbumSort
import com.librestatic.lightforge.core.database.AlbumTarget
import com.librestatic.lightforge.core.database.GalleryDatabase
import com.librestatic.lightforge.core.database.PhysicalAlbumRow
import com.librestatic.lightforge.core.database.VirtualAlbumEntity
import com.librestatic.lightforge.core.database.VirtualAlbumMediaEntity
import com.librestatic.lightforge.core.database.VirtualAlbumRow
import com.librestatic.lightforge.core.model.AlbumAvailability
import com.librestatic.lightforge.core.model.AlbumKey
import com.librestatic.lightforge.core.model.AlbumSummary
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.TimelineMedia
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale

class GalleryAlbumRepository(
    private val database: GalleryDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.libraryDao()
    private val summaries = PagingInvalidator()

    /** Reloads album summaries (and their counts) even when Room saw no table change. */
    fun invalidateSummaries() = summaries.invalidate()

    fun physicalAlbums(): Flow<PagingData<AlbumSummary>> = Pager(
        config = summaryPagingConfig(),
        pagingSourceFactory = summaries.track(dao::physicalAlbums),
    ).flow.map { page -> page.map { it.summary() } }

    fun virtualAlbums(): Flow<PagingData<AlbumSummary>> = Pager(
        config = summaryPagingConfig(),
        pagingSourceFactory = summaries.track(dao::virtualAlbums),
    ).flow.map { page -> page.map { it.summary() } }

    fun media(
        key: AlbumKey,
        filter: AlbumMediaFilter = AlbumMediaFilter.All,
        sort: AlbumSort = AlbumSort.NewestFirst,
    ): Flow<PagingData<TimelineMedia>> = Pager(
        config = PagingConfig(
            pageSize = 120,
            initialLoadSize = 180,
            prefetchDistance = 120,
            enablePlaceholders = false,
            maxSize = 600,
        ),
        pagingSourceFactory = {
            AlbumPagingSource(database, key.target(), filter, sort)
        },
    ).flow.map { page -> page.map { it.toTimelineMedia() } }

    suspend fun createVirtualAlbum(name: String): Long {
        val displayName = validateName(name)
        val now = nowMillis()
        return dao.insertVirtualAlbum(
            VirtualAlbumEntity(
                name = displayName,
                normalizedName = displayName.normalized(),
                createdAtMillis = now,
                updatedAtMillis = now,
            ),
        )
    }

    suspend fun renameVirtualAlbum(albumId: Long, name: String): Boolean {
        require(albumId > 0)
        val displayName = validateName(name)
        return dao.renameVirtualAlbum(albumId, displayName, displayName.normalized(), nowMillis()) == 1
    }

    suspend fun setVirtualAlbumCover(albumId: Long, key: MediaKey?): Boolean {
        require(albumId > 0)
        return dao.setVirtualAlbumCover(albumId, key?.volumeName, key?.mediaStoreId, nowMillis()) == 1
    }

    suspend fun deleteVirtualAlbum(albumId: Long): Boolean {
        require(albumId > 0)
        return dao.deleteVirtualAlbum(albumId) == 1
    }

    /** Adds references only; MediaStore rows and files are never moved or renamed. */
    suspend fun addToVirtualAlbum(albumId: Long, keys: List<MediaKey>): Int {
        require(albumId > 0)
        require(keys.size <= MaxMutationChunk) { "Album mutations must be streamed in bounded chunks" }
        val now = nowMillis()
        return dao.addVirtualAlbumMedia(
            keys.distinct().map { VirtualAlbumMediaEntity(albumId, it.volumeName, it.mediaStoreId, now) },
        ).count { it != -1L }
    }

    suspend fun removeFromVirtualAlbum(albumId: Long, key: MediaKey): Boolean =
        dao.removeVirtualAlbumMedia(albumId, key.volumeName, key.mediaStoreId) == 1

    suspend fun physicalAvailability(key: AlbumKey.Physical): AlbumAvailability =
        if (dao.isPhysicalAlbumAvailable(key.volumeName, key.bucketId)) {
            AlbumAvailability.Available
        } else {
            AlbumAvailability.VolumeUnavailable
        }

    private fun validateName(name: String): String = name.trim().replace(Whitespace, " ").also {
        require(it.isNotEmpty()) { "Album name cannot be blank" }
        require(it.length <= MaxAlbumNameLength) { "Album name is too long" }
    }

    private fun String.normalized() = lowercase(Locale.ROOT)

    private fun AlbumKey.target(): AlbumTarget = when (this) {
        is AlbumKey.Physical -> AlbumTarget.Physical(volumeName, bucketId)
        is AlbumKey.Virtual -> AlbumTarget.Virtual(albumId)
    }

    private fun PhysicalAlbumRow.summary() = AlbumSummary(
        key = AlbumKey.Physical(volumeName, bucketId),
        name = displayName?.takeIf(String::isNotBlank),
        itemCount = itemCount,
        latestSortMillis = latestSortMillis,
        cover = coverMediaStoreId?.let { MediaKey(volumeName, it) },
        availability = if (isAvailable) AlbumAvailability.Available else AlbumAvailability.VolumeUnavailable,
    )

    private fun VirtualAlbumRow.summary() = AlbumSummary(
        key = AlbumKey.Virtual(albumId),
        name = name,
        itemCount = itemCount,
        latestSortMillis = latestSortMillis,
        cover = if (coverVolumeName != null && coverMediaStoreId != null) {
            MediaKey(requireNotNull(coverVolumeName), requireNotNull(coverMediaStoreId))
        } else {
            null
        },
        availability = AlbumAvailability.Available,
    )

    private fun summaryPagingConfig() = PagingConfig(
        pageSize = 40,
        initialLoadSize = 60,
        prefetchDistance = 40,
        enablePlaceholders = false,
        maxSize = 240,
    )

    companion object {
        const val MaxMutationChunk = 500
        const val MaxAlbumNameLength = 200
        private val Whitespace = Regex("\\s+")
    }
}
