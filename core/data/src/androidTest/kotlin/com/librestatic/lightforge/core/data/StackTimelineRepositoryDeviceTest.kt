package com.librestatic.lightforge.core.data

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.database.*
import com.librestatic.lightforge.core.model.*
import com.librestatic.lightforge.core.preferences.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class StackTimelineRepositoryDeviceTest {
    private fun key(id: Long, volume: String = "external_primary") = MediaKey(volume, id)

    private fun media(id: Long, volume: String = "external_primary") =
        MediaItemEntity(
            volume,
            id,
            1,
            "image/jpeg",
            "photo-$id.jpg",
            100,
            10,
            10,
            0,
            0,
            id * 1000,
            id,
            id,
            id * 1000,
            1,
            1,
            99,
            "Fixture",
            "DCIM/Fixture/",
            false,
            false,
            true,
            1,
        )

    private suspend fun db(block: suspend (GalleryDatabase, GalleryPhotoStackRepository) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            db.libraryDao().upsertMedia((1L..4L).map(::media))
            block(db, GalleryPhotoStackRepository(db))
        } finally {
            db.close()
        }
    }

    private suspend fun rows(db: GalleryDatabase, settings: LibrarySettings = LibrarySettings()) =
        db.libraryDao().stackTimelinePage(GalleryTimelineQuery.stacked(settings))

    private suspend fun group(repo: GalleryPhotoStackRepository) = repo.create((1L..3L).map(::key))

    private suspend fun stack(db: GalleryDatabase, id: String, count: Int) =
        TimelineStack(id, db.photoStackDao().get(id)!!.revision, count)

    @Test
    fun representativeIsChosenCoverAndSingletonsRemainOrdinary(): Unit = runBlocking {
        db { db, repo ->
            val id = group(repo)
            val collapsed = rows(db)
            assertEquals(listOf(4L, 1L), collapsed.map { it.media.mediaStoreId })
            assertNull(collapsed[0].timelineStackId)
            assertEquals(id, collapsed[1].timelineStackId)
            assertEquals(3, collapsed[1].timelineStackCount)
            val plain =
                db.libraryDao()
                    .rawTimelinePagingSource(GalleryTimelineQuery.build(LibrarySettings()))
                    .load(PagingSource.LoadParams.Refresh(null, 20, false))
                    as PagingSource.LoadResult.Page
            assertEquals(4, plain.data.size)
        }
    }

    @Test
    fun filtersApplyBeforeCoverFallbackAndNeverExposeHiddenFolders(): Unit = runBlocking {
        db { db, repo ->
            val id = group(repo)
            db.libraryDao()
                .upsertMedia(listOf(media(2).copy(bucketId = 100), media(3).copy(bucketId = 100)))
            val settings =
                LibrarySettings(
                    folderSelectionMode = FolderSelectionMode.OnlyIncluded,
                    folderRules =
                        mapOf(FolderSelectionTarget.Bucket("external_primary", 100L) to true),
                )
            val visible = rows(db, settings).single()
            assertEquals(2L, visible.media.mediaStoreId)
            assertEquals(2, visible.timelineStackCount)
            assertEquals(1L, db.photoStackDao().get(id)!!.coverMediaStoreId)
            assertEquals(
                listOf(2L, 3L),
                GalleryTimelineRepository(db).selectStack(stack(db, id, 2), settings).map {
                    it.key.mediaStoreId
                },
            )
            assertTrue(
                rows(
                        db,
                        settings.copy(
                            folderRules =
                                mapOf(FolderSelectionTarget.Bucket("x' OR 1=1 --", 100L) to true)
                        ),
                    )
                    .isEmpty()
            )
        }
    }

    @Test
    fun archiveTrashAccessAndTypeChangesRecomputeOnlyEligibleMembers(): Unit = runBlocking {
        db { db, repo ->
            group(repo)
            db.libraryDao().upsertArchived(ArchivedMediaEntity("external_primary", 1, 100))
            assertEquals(2L, rows(db).last().media.mediaStoreId)
            assertEquals(2, rows(db).last().timelineStackCount)
            db.libraryDao().upsertMedia(listOf(media(2).copy(isTrashed = true)))
            val last = rows(db).last()
            assertEquals(3L, last.media.mediaStoreId)
            assertNull(last.timelineStackId)
            db.libraryDao().upsertMedia(listOf(media(3).copy(isAccessible = false)))
            assertEquals(listOf(4L), rows(db).map { it.media.mediaStoreId })
            db.libraryDao()
                .upsertMedia(listOf(media(2).copy(mediaType = 3, mimeType = "video/mp4")))
            assertEquals(listOf(4L, 2L), rows(db).map { it.media.mediaStoreId })
            assertTrue(rows(db).all { it.timelineStackId == null })
        }
    }

    @Test
    fun keysetPagesDoNotResurrectOlderMembersAcrossPageBoundaries(): Unit = runBlocking {
        db { db, repo ->
            db.libraryDao().upsertMedia((5L..310L).map(::media))
            repo.create(listOf(key(300), key(200), key(1)), cover = key(300))
            val source = StackTimelinePagingSource(db)
            val ids = mutableListOf<Long>()
            var cursor: TimelineKeyset? = null
            do {
                val result =
                    source.load(
                        if (cursor == null) PagingSource.LoadParams.Refresh(null, 7, false)
                        else PagingSource.LoadParams.Append(cursor!!, 7, false)
                    ) as PagingSource.LoadResult.Page
                ids += result.data.map { it.media.mediaStoreId }
                cursor = result.nextKey
            } while (cursor != null)
            source.invalidate()
            assertEquals((310L downTo 1L).filter { it != 200L && it != 1L }, ids)
            assertEquals(ids.size, ids.distinct().size)
        }
    }

    @Test
    fun compositeIdentityAndTieBreakersSurviveOneRowPages(): Unit = runBlocking {
        db { db, repo ->
            db.libraryDao().upsertMedia(listOf(media(1, "secondary"), media(4, "secondary")))
            repo.create(listOf(key(1), key(1, "secondary")), cover = key(1, "secondary"))
            val source = StackTimelinePagingSource(db)
            val keys = mutableListOf<MediaKey>()
            var cursor: TimelineKeyset? = null
            do {
                val page =
                    source.load(
                        if (cursor == null) PagingSource.LoadParams.Refresh(null, 1, false)
                        else PagingSource.LoadParams.Append(cursor!!, 1, false)
                    ) as PagingSource.LoadResult.Page
                keys += page.data.map { key(it.media.mediaStoreId, it.media.volumeName) }
                cursor = page.nextKey
            } while (cursor != null)
            source.invalidate()
            assertEquals(
                listOf(key(4, "secondary"), key(4), key(3), key(2), key(1, "secondary")),
                keys,
            )
        }
    }

    @Test
    fun orderingAndEveryMediaFilterUseTheSameLibraryPolicy(): Unit = runBlocking {
        db { db, repo ->
            group(repo)
            db.libraryDao()
                .upsertMedia(
                    listOf(
                        media(1).copy(displayName = "zzz", sizeBytes = 900),
                        media(3).copy(mimeType = "image/gif"),
                        media(4).copy(mimeType = "image/x-adobe-dng"),
                    )
                )
            for (sort in LibrarySort.entries) for (ascending in listOf(false, true)) {
                val settings =
                    LibrarySettings(
                        sort = sort,
                        ascending = ascending,
                        grouping = LibraryGrouping.None,
                    )
                val result = rows(db, settings)
                assertEquals(2, result.size)
                val expected =
                    when (sort) {
                        LibrarySort.Name -> listOf(4L, 1L)
                        LibrarySort.Size -> listOf(4L, 1L)
                        else -> listOf(1L, 4L)
                    }.let { if (ascending) it else it.reversed() }
                assertEquals("$sort/$ascending", expected, result.map { it.media.mediaStoreId })
            }
            assertTrue(rows(db, LibrarySettings(filter = LibraryFilter.Videos)).isEmpty())
            assertEquals(
                listOf(3L),
                rows(db, LibrarySettings(filter = LibraryFilter.Animated)).map {
                    it.media.mediaStoreId
                },
            )
            assertEquals(
                listOf(4L),
                rows(db, LibrarySettings(filter = LibraryFilter.Raw)).map { it.media.mediaStoreId },
            )
        }
    }

    @Test
    fun selectionRejectsChangedRevisionOrCountAndReturnsPlainCurrentSources(): Unit = runBlocking {
        db { db, repo ->
            val id = group(repo)
            val before = stack(db, id, 3)
            val timeline = GalleryTimelineRepository(db)
            val selected = timeline.selectStack(before, LibrarySettings())
            assertEquals(listOf(key(1), key(2), key(3)), selected.map { it.key })
            assertTrue(selected.all { it.stack == null })
            repo.setCover(id, before.revision, key(2))
            assertTrue(
                runCatching { timeline.selectStack(before, LibrarySettings()) }.exceptionOrNull()
                    is PhotoStackChanged
            )
            val current = stack(db, id, 3)
            db.libraryDao().upsertArchived(ArchivedMediaEntity("external_primary", 1, 100))
            assertTrue(
                runCatching { timeline.selectStack(current, LibrarySettings()) }.exceptionOrNull()
                    is PhotoStackChanged
            )
            assertEquals(
                listOf(key(2), key(3)),
                timeline.selectStack(current.copy(count = 2), LibrarySettings()).map { it.key },
            )
        }
    }

    @Test
    fun pagingInvalidatesForCoverMembershipAndArchiveChanges(): Unit = runBlocking {
        db { db, repo ->
            val id = group(repo)
            suspend fun observes(change: suspend () -> Unit) {
                delay(100)
                val source = StackTimelinePagingSource(db)
                source.load(PagingSource.LoadParams.Refresh(null, 2, false))
                change()
                withTimeout(5000) { while (!source.invalid) delay(20) }
            }
            observes { repo.setCover(id, db.photoStackDao().get(id)!!.revision, key(2)) }
            observes {
                db.libraryDao().upsertArchived(ArchivedMediaEntity("external_primary", 2, 100))
            }
            observes { repo.separate(id, db.photoStackDao().get(id)!!.revision, key(3)) }
            observes { repo.dissolve(id, db.photoStackDao().get(id)!!.revision) }
            assertEquals(listOf(4L, 3L, 1L), rows(db).map { it.media.mediaStoreId })
            delay(100)
            val expandedSource = TimelinePagingSource(db)
            expandedSource.load(PagingSource.LoadParams.Refresh(null, 20, false))
            db.libraryDao().upsertArchived(ArchivedMediaEntity("external_primary", 3, 101))
            withTimeout(5000) { while (!expandedSource.invalid) delay(20) }

        }
    }

    @Test
    fun maximumStackAndLargeLibraryStayBoundedAtTheQueryBoundary(): Unit = runBlocking {
        db { db, repo ->
            for (start in 1L..20000L step 500) db.libraryDao()
                .upsertMedia((start until start + 500).map(::media))
            val id = repo.create((1L..500L).map(::key))
            val start = System.nanoTime()
            val first =
                db.libraryDao()
                    .stackTimelinePage(GalleryTimelineQuery.stacked(LibrarySettings(), limit = 120))
            assertEquals(120, first.size)
            assertEquals(
                500,
                GalleryTimelineRepository(db)
                    .selectStack(stack(db, id, 500), LibrarySettings())
                    .size,
            )
            android.util.Log.i(
                "StackTimelineSQL",
                "PASS rows=20000 page=120 selection=500 elapsedMs=${(System.nanoTime()-start)/1000000}",
            )
        }
    }
    @Test
    fun factoryConnectionsPropagateUnstackAndWorkerArchiveToLivePaging(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "stack-invalidation-${java.util.UUID.randomUUID()}.db"
        val reader = GalleryDatabaseFactory.open(context, name)
        val writer = GalleryDatabaseFactory.open(context, name)
        try {
            reader.libraryDao().upsertMedia((1L..3L).map(::media))
            val repo = GalleryPhotoStackRepository(reader)
            val id = repo.create((1L..3L).map(::key))
            // Both Binder invalidation clients connect asynchronously during fixture setup.
            delay(500)
            val collapsed = StackTimelinePagingSource(reader)
            collapsed.load(PagingSource.LoadParams.Refresh(null, 20, false))
            GalleryPhotoStackRepository(writer).dissolve(id, writer.photoStackDao().get(id)!!.revision)
            withTimeout(5000) { while (!collapsed.invalid) delay(20) }
            assertEquals(3, rows(reader).size)
            delay(100)
            val expanded = TimelinePagingSource(reader)
            expanded.load(PagingSource.LoadParams.Refresh(null, 20, false))
            writer.libraryDao().upsertArchived(ArchivedMediaEntity("external_primary", 1, 10))
            withTimeout(5000) { while (!expanded.invalid) delay(20) }
            assertEquals(listOf(3L, 2L), rows(reader).map { it.media.mediaStoreId })
        } finally {
            reader.close(); writer.close(); context.deleteDatabase(name)
        }
    }

}
