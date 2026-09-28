package com.librestatic.lightforge.core.data

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.database.GalleryDatabase
import com.librestatic.lightforge.core.database.MediaItemEntity
import com.librestatic.lightforge.core.database.TimelineKeyset
import com.librestatic.lightforge.core.database.TimelinePagingSource
import com.librestatic.lightforge.core.preferences.LibrarySettings
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class TimelineScrubberDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: GalleryDatabase

    @Before fun open() {
        database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
    }

    @After fun close() = database.close()

    private fun media(id: Long, sortMillis: Long) =
        MediaItemEntity(
            "external_primary", id, 1, "image/jpeg", "photo-$id.jpg", 100, 10, 10, 0, 0,
            sortMillis, id, id, sortMillis, 1, 1, 99, "Fixture", "DCIM/Fixture/", false, false, true, 1,
        )

    private fun noon(day: LocalDate) =
        day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun dayIndexCountsDisplayedRowsPerLocalDay() = runBlocking {
        val a = LocalDate.of(2024, 1, 10)
        val b = LocalDate.of(2025, 6, 2)
        val c = LocalDate.of(2026, 3, 30)
        val rows = (1L..3L).map { media(it, noon(a) + it) } +
            (4L..8L).map { media(it, noon(b) + it) } +
            media(9L, noon(c))
        database.libraryDao().upsertMedia(rows)

        val index = GalleryTimelineRepository(database).timelineIndex(LibrarySettings(), collapseStacks = true).first()

        assertNotNull(index)
        assertEquals(listOf(c, b, a).map(LocalDate::toEpochDay), index!!.days.map { it.epochDay })
        assertEquals(listOf(1, 5, 3), index.days.map { it.count })
        assertEquals(9, index.total)
    }

    @Test fun keysetSourceCanPageTowardTheNewestRowFromAnAnchor() = runBlocking {
        database.libraryDao().upsertMedia((1L..300L).map { media(it, it * 1_000) })
        val source = TimelinePagingSource(database)
        val anchor = TimelineKeyset(150_500, Long.MAX_VALUE, "￿")

        val start = source.load(PagingSource.LoadParams.Refresh(anchor, 50, false))
            as PagingSource.LoadResult.Page
        assertEquals((101L..150L).reversed().toList(), start.data.map { it.mediaStoreId })

        val above = source.load(PagingSource.LoadParams.Prepend(start.prevKey!!, 50, false))
            as PagingSource.LoadResult.Page
        assertEquals((151L..200L).reversed().toList(), above.data.map { it.mediaStoreId })

        val top = source.load(PagingSource.LoadParams.Prepend(TimelineKeyset(290_000, 290, "external_primary"), 50, false))
            as PagingSource.LoadResult.Page
        assertEquals((291L..300L).reversed().toList(), top.data.map { it.mediaStoreId })
        assertNull(top.prevKey)
    }

    @Test fun newestPageHasNothingAbove() = runBlocking {
        database.libraryDao().upsertMedia((1L..10L).map { media(it, it * 1_000) })
        val page = TimelinePagingSource(database)
            .load(PagingSource.LoadParams.Refresh(null, 5, false)) as PagingSource.LoadResult.Page
        assertNull(page.prevKey)
        assertEquals((6L..10L).reversed().toList(), page.data.map { it.mediaStoreId })
    }
}
