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
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class GalleryTimelineRepository(private val database: GalleryDatabase) {
    private val sources = PagingInvalidator()

    /** Reloads every live timeline page from Room, whether or not Room saw a table change. */
    fun invalidate() = sources.invalidate()

    fun timeline(
        zoneId: ZoneId,
        settings: LibrarySettings = LibrarySettings(),
        collapseStacks: Boolean = true,
    ): Flow<PagingData<TimelineEntry>> {
        val config =
            PagingConfig(
                pageSize = 120,
                initialLoadSize = 180,
                prefetchDistance = 180,
                enablePlaceholders = false,
                maxSize = 720,
            )
        val rows: Flow<PagingData<TimelineMedia>> =
            if (collapseStacks) {
                Pager(
                        config,
                        pagingSourceFactory = sources.track {
                            if (settings == LibrarySettings()) StackTimelinePagingSource(database)
                            else
                                database
                                    .libraryDao()
                                    .rawStackTimelinePagingSource(
                                        GalleryTimelineQuery.stacked(settings)
                                    )
                        },
                    )
                    .flow
                    .map { data ->
                        data.map { row ->
                            row.media
                                .toTimelineMedia()
                                .copy(
                                    stack =
                                        row.timelineStackId?.let { id ->
                                            com.ugallery.core.model.TimelineStack(
                                                id,
                                                requireNotNull(row.timelineStackRevision),
                                                row.timelineStackCount,
                                            )
                                        }
                                )
                        }
                    }
            } else {
                Pager(
                        config,
                        pagingSourceFactory = sources.track {
                            if (settings == LibrarySettings()) TimelinePagingSource(database)
                            else
                                database
                                    .libraryDao()
                                    .rawTimelinePagingSource(GalleryTimelineQuery.build(settings))
                        },
                    )
                    .flow
                    .map { data -> data.map { it.toTimelineMedia() } }
            }
        return rows.map { data ->
            val media = data.map { TimelineEntry.Media(it) as TimelineEntry }
            if (settings.grouping == LibraryGrouping.None) media
            else
                media.insertSeparators { before: TimelineEntry?, after: TimelineEntry? ->
                    val afterMedia =
                        (after as? TimelineEntry.Media)?.value ?: return@insertSeparators null
                    val beforeGroup =
                        (before as? TimelineEntry.Media)
                            ?.value
                            ?.dateGroup(zoneId, settings.grouping)
                    val afterGroup = afterMedia.dateGroup(zoneId, settings.grouping)
                    if (beforeGroup != afterGroup)
                        TimelineEntry.DayHeader(afterGroup.first, afterGroup.second)
                    else null
                }
        }
    }

    /** Selection is the current filtered membership, never hidden/archived/inaccessible photos. */
    suspend fun selectStack(
        stack: com.ugallery.core.model.TimelineStack,
        settings: LibrarySettings,
    ): List<TimelineMedia> {
        val rows =
            database
                .libraryDao()
                .stackTimelineSelection(
                    GalleryTimelineQuery.stackSelection(settings, stack.id, stack.revision)
                )
        if (rows.size != stack.count || rows.size !in 2..500) throw PhotoStackChanged()
        return rows.map { it.toTimelineMedia() }
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

internal fun MediaItemEntity.toTimelineMedia() =
    TimelineMedia(
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
