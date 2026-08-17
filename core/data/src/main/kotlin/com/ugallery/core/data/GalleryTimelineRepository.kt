package com.ugallery.core.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.insertSeparators
import androidx.paging.map
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.database.MediaItemEntity
import com.ugallery.core.database.TimelinePagingSource
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.MediaKind
import com.ugallery.core.model.TimelineEntry
import com.ugallery.core.model.TimelineMedia
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId

class GalleryTimelineRepository(private val database: GalleryDatabase) {
    fun timeline(zoneId: ZoneId): Flow<PagingData<TimelineEntry>> = Pager(
        config = PagingConfig(
            pageSize = 120,
            initialLoadSize = 180,
            prefetchDistance = 180,
            enablePlaceholders = false,
            maxSize = 720,
        ),
        pagingSourceFactory = { TimelinePagingSource(database) },
    ).flow.map { data ->
        data.map { TimelineEntry.Media(it.toTimelineMedia()) as TimelineEntry }
            .insertSeparators { before: TimelineEntry?, after: TimelineEntry? ->
                val afterMedia = (after as? TimelineEntry.Media)?.value ?: return@insertSeparators null
                val beforeDay = (before as? TimelineEntry.Media)?.value?.epochDay(zoneId)
                val afterDay = afterMedia.epochDay(zoneId)
                if (beforeDay != afterDay) TimelineEntry.DayHeader(afterDay) else null
            }
    }
}

internal fun TimelineMedia.epochDay(zoneId: ZoneId): Long =
    Instant.ofEpochMilli(timelineSortMillis).atZone(zoneId).toLocalDate().toEpochDay()

internal fun MediaItemEntity.toTimelineMedia() = TimelineMedia(
    key = MediaKey(volumeName, mediaStoreId),
    kind = if (mediaType == 3) MediaKind.Video else MediaKind.Image,
    generationModified = generationModified,
    timelineSortMillis = timelineSortMillis,
    width = width,
    height = height,
    durationMillis = durationMillis,
)
