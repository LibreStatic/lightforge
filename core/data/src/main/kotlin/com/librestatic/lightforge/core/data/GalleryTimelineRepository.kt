package com.librestatic.lightforge.core.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.insertSeparators
import androidx.paging.map
import com.librestatic.lightforge.core.database.StackTimelineRow
import com.librestatic.lightforge.core.database.TimelineKeyset
import com.librestatic.lightforge.core.model.TimelineAnchor
import com.librestatic.lightforge.core.model.TimelineDayBucket
import com.librestatic.lightforge.core.model.TimelineDayCover
import com.librestatic.lightforge.core.model.TimelineIndex
import com.librestatic.lightforge.core.preferences.LibrarySort
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import com.librestatic.lightforge.core.database.GalleryDatabase
import com.librestatic.lightforge.core.database.MediaItemEntity
import com.librestatic.lightforge.core.database.TimelinePagingSource
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.model.TimelineEntry
import com.librestatic.lightforge.core.model.TimelineGrouping
import com.librestatic.lightforge.core.model.TimelineMedia
import com.librestatic.lightforge.core.preferences.LibraryGrouping
import com.librestatic.lightforge.core.preferences.LibrarySettings
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
        anchor: TimelineAnchor? = null,
    ): Flow<PagingData<TimelineEntry>> {
        val config =
            PagingConfig(
                pageSize = 120,
                initialLoadSize = 180,
                prefetchDistance = 180,
                enablePlaceholders = false,
                maxSize = 720,
            )
        val keyset = settings == LibrarySettings()
        val rows: Flow<PagingData<TimelineMedia>> =
            if (collapseStacks) {
                    val stackRows: Flow<PagingData<StackTimelineRow>> =
                        if (keyset) {
                            Pager(
                                    config,
                                    initialKey = anchor?.let { anchorKeyset(it, zoneId) },
                                    pagingSourceFactory = sources.track {
                                        StackTimelinePagingSource(database)
                                    },
                                )
                                .flow
                        } else {
                            flow { emit(anchor?.let { anchorOffset(it, zoneId, settings, true) }) }
                                .flatMapLatest { offset ->
                                    Pager(
                                            config,
                                            initialKey = offset,
                                            pagingSourceFactory = sources.track {
                                                database
                                                    .libraryDao()
                                                    .rawStackTimelinePagingSource(
                                                        GalleryTimelineQuery.stacked(settings)
                                                    )
                                            },
                                        )
                                        .flow
                                }
                        }
                    stackRows.map { data ->
                        data.map { row ->
                            row.media
                                .toTimelineMedia()
                                .copy(
                                    stack =
                                        row.timelineStackId?.let { id ->
                                            com.librestatic.lightforge.core.model.TimelineStack(
                                                id,
                                                requireNotNull(row.timelineStackRevision),
                                                row.timelineStackCount,
                                            )
                                        }
                                )
                        }
                    }
            } else {
                val plainRows: Flow<PagingData<MediaItemEntity>> =
                    if (keyset) {
                        Pager(
                                config,
                                initialKey = anchor?.let { anchorKeyset(it, zoneId) },
                                pagingSourceFactory = sources.track { TimelinePagingSource(database) },
                            )
                            .flow
                    } else {
                        flow { emit(anchor?.let { anchorOffset(it, zoneId, settings, false) }) }
                            .flatMapLatest { offset ->
                                Pager(
                                        config,
                                        initialKey = offset,
                                        pagingSourceFactory = sources.track {
                                            database
                                                .libraryDao()
                                                .rawTimelinePagingSource(GalleryTimelineQuery.build(settings))
                                        },
                                    )
                                    .flow
                            }
                    }
                plainRows.map { data -> data.map { it.toTimelineMedia() } }
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

    /**
     * Per-day histogram of exactly the rows [timeline] shows, or null when the sort is not by
     * date taken (the only order whose position the scrubber can express as a date).
     */
    @OptIn(FlowPreview::class)
    fun timelineIndex(settings: LibrarySettings, collapseStacks: Boolean): Flow<TimelineIndex?> {
        if (settings.sort != LibrarySort.DateTaken) return flowOf(null)
        return database
            .libraryDao()
            .timelineDayCounts(GalleryTimelineQuery.dayCounts(settings, collapseStacks))
            .debounce(IndexDebounceMillis)
            .map { rows ->
                TimelineIndex(
                    rows.map { row ->
                        val id = row.mediaStoreId
                        val volume = row.volumeName
                        val cover = if (id != null && id >= 0 && !volume.isNullOrBlank()) {
                            TimelineDayCover(MediaKey(volume, id), (row.generationModified ?: 0L).coerceAtLeast(0L))
                        } else {
                            null
                        }
                        TimelineDayBucket(LocalDate.parse(row.day).toEpochDay(), row.count, cover)
                    },
                    settings.ascending,
                )
            }
            .flowOn(Dispatchers.Default)
    }

    /** Exclusive upper bound that sorts just after every row of the anchor day. */
    private fun anchorKeyset(anchor: TimelineAnchor, zoneId: ZoneId) =
        TimelineKeyset(
            LocalDate.ofEpochDay(anchor.epochDay + 1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1,
            Long.MAX_VALUE,
            "\uFFFF",
        )

    private suspend fun anchorOffset(
        anchor: TimelineAnchor,
        zoneId: ZoneId,
        settings: LibrarySettings,
        collapsed: Boolean,
    ): Int {
        val boundary =
            LocalDate.ofEpochDay(if (settings.ascending) anchor.epochDay else anchor.epochDay + 1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
        return database
            .libraryDao()
            .timelineRowCount(GalleryTimelineQuery.rowsBefore(settings, collapsed, boundary))
    }

    /** Selection is the current filtered membership, never hidden/archived/inaccessible photos. */
    suspend fun selectStack(
        stack: com.librestatic.lightforge.core.model.TimelineStack,
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

private const val IndexDebounceMillis = 250L

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
