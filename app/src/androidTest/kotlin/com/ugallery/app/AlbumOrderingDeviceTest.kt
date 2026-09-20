package com.ugallery.app

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.database.*
import com.ugallery.core.data.GalleryAlbumRepository
import com.ugallery.core.data.RoomViewerMediaSource
import com.ugallery.core.model.TimelineMedia
import com.ugallery.core.model.MediaKind
import com.ugallery.core.model.MediaKey
import com.ugallery.core.selection.MediaQuery
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Small real Room queries only; no MediaStore, app DB or 100k fixture. */
@RunWith(AndroidJUnit4::class)
class AlbumOrderingDeviceTest {
    private lateinit var database: GalleryDatabase
    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
            GalleryDatabase::class.java).build()
    }
    @After fun cleanup() = database.close()

    @Test fun physicalAndVirtualTwoRowPagesMatchNameSizeOrderAndViewerNeighbors() = runBlocking {
        val rows = fixture()
        database.libraryDao().upsertMedia(rows)
        val repository = GalleryAlbumRepository(database) { 1L }
        val album = repository.createVirtualAlbum("Ordering fixture")
        repository.addToVirtualAlbum(album, rows.map { MediaKey(it.volumeName, it.mediaStoreId) })
        for (target in listOf(AlbumTarget.Physical("external_primary", 10), AlbumTarget.Virtual(album))) {
            for (filter in AlbumMediaFilter.entries) {
                val candidates = rows.filter { row -> row.isAccessible && !row.isTrashed &&
                    (target !is AlbumTarget.Physical || row.volumeName == target.volumeName && row.bucketId == target.bucketId) &&
                    (filter == AlbumMediaFilter.All || row.mediaType == filter.mediaStoreType) }
                for (sort in listOf(AlbumSort.NameAscending, AlbumSort.NameDescending,
                    AlbumSort.SizeAscending, AlbumSort.SizeDescending)) {
                    val expected = candidates.sortedWith(comparator(sort))
                    val source = AlbumPagingSource(database, target, filter, sort)
                    val actual = try { pages(source) } finally { source.invalidate() }
                    assertEquals("$target / $filter / $sort", expected.map(::key), actual.map(::key))
                    assertEquals(actual.size, actual.map(::key).distinct().size)
                    val query = MediaQuery(
                        scope = when (target) {
                            is AlbumTarget.Physical -> MediaQuery.Scope.PhysicalAlbum(target.volumeName, target.bucketId)
                            is AlbumTarget.Virtual -> MediaQuery.Scope.VirtualAlbum(target.albumId)
                        },
                        kindFilter = when (filter) {
                            AlbumMediaFilter.All -> MediaQuery.KindFilter.ImagesAndVideos
                            AlbumMediaFilter.Images -> MediaQuery.KindFilter.Images
                            AlbumMediaFilter.Videos -> MediaQuery.KindFilter.Videos
                        },
                        sort = if (sort == AlbumSort.NameAscending || sort == AlbumSort.SizeAscending)
                            MediaQuery.Sort.OldestFirst else MediaQuery.Sort.NewestFirst,
                        sortField = if (sort == AlbumSort.NameAscending || sort == AlbumSort.NameDescending)
                            MediaQuery.SortField.Name else MediaQuery.SortField.Size,
                        grouping = MediaQuery.Grouping.None,
                        archiveMode = MediaQuery.ArchiveMode.Include,
                    )
                    for (index in expected.indices) {
                        val window = RoomViewerMediaSource(database).window(query, timeline(expected[index]), radius = 1)
                        assertEquals("viewer $target / $filter / $sort / $index",
                            expected.subList(maxOf(0, index - 1), minOf(expected.size, index + 2)).map(::key),
                            window.items.map { it.key })
                        assertEquals(index > 1, window.hasPrevious)
                        assertEquals(index + 2 < expected.size, window.hasNext)
                    }
                    println("ALBUM_ORDER target=$target filter=$filter sort=$sort rows=${actual.map(::key)} viewer=matched pageSize=2")
                }
            }
        }
    }

    @Test fun changedNameAndMembershipInvalidateSourcesAndFreshPagesUseNewOrder() = runBlocking {
        val rows = fixture().take(4)
        database.libraryDao().upsertMedia(rows)
        val repository = GalleryAlbumRepository(database) { 1L }
        val album = repository.createVirtualAlbum("Invalidation fixture")
        repository.addToVirtualAlbum(album, rows.map(::key))
        val physical = AlbumPagingSource(database, AlbumTarget.Physical("external_primary", 10), AlbumMediaFilter.All, AlbumSort.NameAscending)
        pages(physical)
        val changed = rows.first().copy(displayName = "zzzz")
        database.libraryDao().upsertMedia(listOf(changed))
        withTimeout(5_000) { while (!physical.invalid) delay(20) }
        val virtual = AlbumPagingSource(database, AlbumTarget.Virtual(album), AlbumMediaFilter.All, AlbumSort.NameAscending)
        pages(virtual)
        assertTrue(repository.removeFromVirtualAlbum(album, key(rows[1])))
        withTimeout(5_000) { while (!virtual.invalid) delay(20) }
        val fresh = AlbumPagingSource(database, AlbumTarget.Virtual(album), AlbumMediaFilter.All, AlbumSort.NameAscending)
        val actual = try { pages(fresh) } finally { fresh.invalidate() }
        val expected = (listOf(changed) + rows.drop(2)).sortedWith(comparator(AlbumSort.NameAscending))
        assertEquals(expected.map(::key), actual.map(::key))
        println("ALBUM_ORDER invalidation mediaName=true membership=true freshOrder=${actual.map(::key)}")
    }

    private suspend fun <K : Any> pages(source: PagingSource<K, MediaItemEntity>): List<MediaItemEntity> {
        var result = source.load(PagingSource.LoadParams.Refresh<K>(null, 2, false))
        val all = mutableListOf<MediaItemEntity>()
        var count = 0
        while (true) {
            check(++count <= 20) { "Unbounded pagination" }
            val page = result as? PagingSource.LoadResult.Page<K, MediaItemEntity>
                ?: error("Album page failed: $result")
            check(page.data.size <= 2)
            all += page.data
            val next = page.nextKey ?: break
            result = source.load(PagingSource.LoadParams.Append(next, 2, false))
        }
        return all
    }
    private fun timeline(row: MediaItemEntity) = TimelineMedia(
        key = key(row), kind = if (row.mediaType == 3) MediaKind.Video else MediaKind.Image,
        generationModified = row.generationModified, timelineSortMillis = row.timelineSortMillis,
        width = row.width, height = row.height, durationMillis = row.durationMillis,
        displayName = row.displayName, sizeBytes = row.sizeBytes, dateModifiedSeconds = row.dateModifiedSeconds,
    )
    private fun key(row: MediaItemEntity) = MediaKey(row.volumeName, row.mediaStoreId)
    private fun comparator(sort: AlbumSort): Comparator<MediaItemEntity> {
        // SQLite LOWER folds ASCII only; non-ASCII stays unchanged (default SQLite build).
        fun name(row: MediaItemEntity) = row.displayName.orEmpty().map { if (it in 'A'..'Z') it + 32 else it }.joinToString("")
        val base = if (sort == AlbumSort.NameAscending || sort == AlbumSort.NameDescending)
            compareBy<MediaItemEntity> { name(it) } else compareBy { it.sizeBytes }
        val tied = base.thenBy { it.mediaStoreId }.thenBy { it.volumeName }
        return if (sort == AlbumSort.NameDescending || sort == AlbumSort.SizeDescending) tied.reversed() else tied
    }
    private fun fixture(): List<MediaItemEntity> = listOf(
        media("external_primary", 1, null, 0), media("external_primary", 2, "", 0),
        media("external_primary", 3, "alpha", 10), media("external_primary", 4, "ALPHA", 10),
        media("external_primary", 5, "zeta", 20, 3), media("external_primary", 6, "Álbum", 20),
        media("test-volume", 3, "alpha", 10), media("test-volume", 8, "", 0, 3),
        media("external_primary", 9, "hidden", 1).copy(isAccessible = false),
        media("external_primary", 10, "trash", 2).copy(isTrashed = true),
        media("external_primary", 11, "other bucket", 3).copy(bucketId = 99),
    )
    private fun media(volume: String, id: Long, name: String?, size: Long, type: Int = 1) = MediaItemEntity(
        volumeName = volume, mediaStoreId = id, mediaType = type, mimeType = if (type == 3) "video/mp4" else "image/jpeg",
        displayName = name, sizeBytes = size, width = 1, height = 1, durationMillis = 0, orientationDegrees = 0,
        dateTakenMillis = id, dateAddedSeconds = id, dateModifiedSeconds = id, timelineSortMillis = id,
        generationAdded = 1, generationModified = 1, bucketId = 10, bucketDisplayName = "Fixture",
        relativePath = "Pictures/Fixture/", isFavorite = false, isTrashed = false, isAccessible = true, lastSeenScanId = 1,
    )
}
