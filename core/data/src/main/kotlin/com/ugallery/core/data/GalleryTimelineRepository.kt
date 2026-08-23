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
import com.ugallery.core.model.TimelineGrouping
import com.ugallery.core.model.TimelineMedia
import com.ugallery.core.preferences.LibraryGrouping
import com.ugallery.core.preferences.LibrarySettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId

class GalleryTimelineRepository(private val database: GalleryDatabase) {
    fun timeline(
        zoneId: ZoneId,
        settings: LibrarySettings = LibrarySettings(),
    ): Flow<PagingData<TimelineEntry>> = Pager(
        config = PagingConfig(
            pageSize = 120,
            initialLoadSize = 180,
            prefetchDistance = 180,
            enablePlaceholders = false,
            maxSize = 720,
        ),
        pagingSourceFactory = {
            if (settings == LibrarySettings()) TimelinePagingSource(database)
            else database.libraryDao().rawTimelinePagingSource(GalleryTimelineQuery.build(settings))
        },
    ).flow.map { data ->
        val media = data.map { TimelineEntry.Media(it.toTimelineMedia()) as TimelineEntry }
        if (settings.grouping == LibraryGrouping.None) media else media.insertSeparators {
                before: TimelineEntry?, after: TimelineEntry?,
            ->
                val afterMedia = (after as? TimelineEntry.Media)?.value ?: return@insertSeparators null
                val beforeGroup = (before as? TimelineEntry.Media)?.value?.dateGroup(zoneId, settings.grouping)
                val afterGroup = afterMedia.dateGroup(zoneId, settings.grouping)
                if (beforeGroup != afterGroup) TimelineEntry.DayHeader(afterGroup.first, afterGroup.second) else null
            }
    }
}

internal fun TimelineMedia.epochDay(zoneId: ZoneId): Long =
    Instant.ofEpochMilli(timelineSortMillis).atZone(zoneId).toLocalDate().toEpochDay()

private fun TimelineMedia.dateGroup(
    zoneId: ZoneId,
    grouping: LibraryGrouping,
): Pair<Long, TimelineGrouping> {
    val date = Instant.ofEpochMilli(timelineSortMillis).atZone(zoneId).toLocalDate()
    return when (grouping) {
        LibraryGrouping.Day -> date.toEpochDay() to TimelineGrouping.Day
        LibraryGrouping.Month -> date.withDayOfMonth(1).toEpochDay() to TimelineGrouping.Month
        LibraryGrouping.Year -> date.withDayOfYear(1).toEpochDay() to TimelineGrouping.Year
        LibraryGrouping.None -> error("No date group requested")
    }
}

internal fun MediaItemEntity.toTimelineMedia() = TimelineMedia(
    key = MediaKey(volumeName, mediaStoreId),
    kind = if (mediaType == 3) MediaKind.Video else MediaKind.Image,
    generationModified = generationModified,
    timelineSortMillis = timelineSortMillis,
    width = width,
    height = height,
    durationMillis = durationMillis,
    dateExpiresMillis = dateExpiresSeconds?.times(1_000),
    isFavorite = isFavorite,
    isTrashed = isTrashed,
    displayName = displayName,
    sizeBytes = sizeBytes,
    dateModifiedSeconds = dateModifiedSeconds,
)
