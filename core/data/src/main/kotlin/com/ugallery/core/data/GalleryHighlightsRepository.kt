package com.ugallery.core.data

import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.model.TimelineMedia
import com.ugallery.core.selection.MediaQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest
import java.time.Instant
import java.time.ZoneId

enum class GalleryHighlightKind { YearsAgo, FeaturedVideo, Selfies }

data class GalleryHighlight(
    val id: String,
    val kind: GalleryHighlightKind,
    val cover: TimelineMedia,
    val query: MediaQuery,
    val yearsAgo: Int? = null,
)

class GalleryHighlightsRepository(
    database: GalleryDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.libraryDao()

    fun highlights(zoneId: ZoneId): Flow<List<GalleryHighlight>> =
        dao.observeHighlightInvalidation().mapLatest { load(zoneId) }

    private suspend fun load(zoneId: ZoneId): List<GalleryHighlight> {
        val now = Instant.ofEpochMilli(nowMillis()).atZone(zoneId)
        val anniversaries = buildList {
            for (years in 1..10) {
                if (size == 2) break
                val date = now.toLocalDate().minusYears(years.toLong())
                val start = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val end = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
                if (dao.highlightCount(start, end) > 0) {
                    dao.highlightCover(start, end)?.let { cover ->
                        add(
                            GalleryHighlight(
                                id = "years:$years:$start",
                                kind = GalleryHighlightKind.YearsAgo,
                                cover = cover.toTimelineMedia(),
                                yearsAgo = years,
                                query = MediaQuery(
                                    fromTimelineMillisInclusive = start,
                                    toTimelineMillisExclusive = end,
                                    grouping = MediaQuery.Grouping.None,
                                ),
                            ),
                        )
                    }
                }
            }
        }
        val recentCutoff = now.minusDays(90).toInstant().toEpochMilli()
        val video = dao.featuredVideo(recentCutoff)?.let { cover ->
            GalleryHighlight(
                id = "featured-video",
                kind = GalleryHighlightKind.FeaturedVideo,
                cover = cover.toTimelineMedia(),
                query = MediaQuery(
                    kindFilter = MediaQuery.KindFilter.Videos,
                    fromTimelineMillisInclusive = recentCutoff,
                    grouping = MediaQuery.Grouping.None,
                ),
            )
        }
        val selfieFolders = dao.selfieFolders()
        val selfies = selfieFolders.firstOrNull()?.coverMediaStoreId?.let { coverId ->
            val folder = selfieFolders.first()
            dao.media(folder.volumeName, coverId)?.let { cover ->
                GalleryHighlight(
                    id = "selfies",
                    kind = GalleryHighlightKind.Selfies,
                    cover = cover.toTimelineMedia(),
                    query = MediaQuery(
                        folderMode = MediaQuery.FolderMode.OnlyIncluded,
                        includedFolders = selfieFolders.map { row ->
                            MediaQuery.PhysicalFolder(row.volumeName, row.bucketId)
                        }.toSet(),
                        grouping = MediaQuery.Grouping.None,
                    ),
                )
            }
        }
        return buildList {
            addAll(anniversaries)
            video?.let(::add)
            selfies?.let(::add)
        }
    }
}
