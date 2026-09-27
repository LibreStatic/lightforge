package com.librestatic.lightforge.core.data

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.database.*
import com.librestatic.lightforge.core.model.MediaKey
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MomentDiscoveryRepositoryDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val base = Instant.parse("2010-01-01T12:00:00Z").toEpochMilli()
    private val day = 86_400_000L

    private fun open() =
        Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java)
            .addCallback(MomentDiscoverySchema.Callback)
            .build()

    private fun media(id: Long, time: Long) =
        MediaItemEntity(
            volumeName = "discovery:fixture",
            mediaStoreId = id,
            mediaType = 1,
            mimeType = "image/jpeg",
            displayName = "IMG_$id.jpg",
            sizeBytes = 1000,
            width = 4000,
            height = 3000,
            durationMillis = 0,
            orientationDegrees = 0,
            dateTakenMillis = time,
            dateAddedSeconds = time / 1000,
            dateModifiedSeconds = time / 1000,
            timelineSortMillis = time,
            generationAdded = 1,
            generationModified = 1,
            bucketId = 1,
            bucketDisplayName = "Camera",
            relativePath = "DCIM/Camera/",
            isFavorite = false,
            isTrashed = false,
            isAccessible = true,
            lastSeenScanId = 1,
        )

    private fun event(offset: Long, time: Long) =
        (0L..7L).map { media(offset + it, time + it * 1000) }

    @Test
    fun legacyCompletedPolicyDiscoversScandinaviaOnceAndPreservesSavedStory() = runBlocking {
        val db = open()
        try {
            val repo = MomentRepository(db)
            db.libraryDao().upsertMedia(event(1, base))
            repo.generateIfNeeded(64)
            val saved = repo.summaries().first().single().moment.momentId
            assertTrue(repo.save(saved)); assertTrue(repo.rename(saved, "Original saved trip"))
            val order = repo.members(saved).map { MediaKey(it.media.volumeName, it.media.mediaStoreId) }.reversed()
            assertTrue(repo.reorder(saved, order)); assertTrue(repo.setCover(saved, order.last()))
            val before = requireNotNull(db.momentDao().moment(saved))
            val members = db.momentDao().allMembers(saved)
            val scandinavia = event(101, base + 10 * day).map {
                it.copy(bucketDisplayName = "Scandinavia", relativePath = "Pictures/Scandinavia/", displayName = "Scandinavia-${it.mediaStoreId}.jpg")
            }
            db.libraryDao().upsertMedia(scandinavia)
            // Represent a v1 COMPLETE pass that discarded the Scandinavia candidates. No media
            // mutation occurs between this persisted legacy checkpoint and the new repository.
            val legacy = "moments-temporal-geo-v1"
            val oldRun = requireNotNull(db.momentDao().run(MomentRepository.AlgorithmVersion))
            db.momentDao().deleteRun(MomentRepository.AlgorithmVersion)
            db.momentDao().upsertMoment(before.copy(algorithmVersion = legacy))
            db.momentDao().upsertRun(oldRun.copy(algorithmVersion = legacy, inputRevision = db.momentDiscoveryDao().discoveryRevision()))
            // Portable import preserves AUTO provenance but creates a UUID identity. Both
            // old/current algorithm imports must survive cleanup and protect their membership.
            val importedMedia = event(201, base + 20 * day) + event(301, base + 30 * day)
            db.libraryDao().upsertMedia(importedMedia)
            val imported = listOf(legacy, MomentRepository.AlgorithmVersion).mapIndexed { index, version ->
                val id = java.util.UUID.randomUUID().toString()
                val rows = importedMedia.drop(index * 8).take(8)
                val story = before.copy(momentId = id, state = "SUGGESTED", isUserEdited = false,
                    title = "Imported $index", titleMode = "AUTO", algorithmVersion = version,
                    startMillis = rows.first().timelineSortMillis, endMillis = rows.last().timelineSortMillis)
                db.momentDao().upsertMoment(story)
                db.momentDao().insertMembers(rows.mapIndexed { ordinal, row ->
                    MomentMemberEntity(id, ordinal, row.volumeName, row.mediaStoreId, row.generationModified, "GENERATED", 1f)
                })
                db.momentDao().upsertCover(MomentCoverEntity(id, rows.first().volumeName, rows.first().mediaStoreId, false))
                story
            }
            db.momentDao().upsertRun(requireNotNull(db.momentDao().run(legacy)).copy(inputRevision = db.momentDiscoveryDao().discoveryRevision()))
            val importedMembers = imported.associate { it.momentId to db.momentDao().allMembers(it.momentId) }
            val originalRevision = db.momentDiscoveryDao().discoveryRevision()
            val upgraded = MomentRepository(db)
            assertTrue(requireNotNull(upgraded.generateIfNeeded(64)).complete)
            assertNull(db.momentDao().run(legacy))
            assertEquals(originalRevision, db.momentDiscoveryDao().discoveryRevision())
            assertEquals(before, db.momentDao().moment(saved))
            assertEquals(members, db.momentDao().allMembers(saved))
            val stories = upgraded.summaries().first()
            assertEquals(4, stories.size)
            imported.forEach {
                assertEquals(it, db.momentDao().moment(it.momentId))
                assertEquals(importedMembers[it.momentId], db.momentDao().allMembers(it.momentId))
            }
            assertEquals(order.last().mediaStoreId, stories.single { it.moment.momentId == saved }.coverMediaStoreId)
            val discovered = stories.single { it.moment.momentId != saved && it.moment.momentId !in importedMembers }.moment.momentId
            assertEquals(scandinavia.map { it.mediaStoreId }.toSet(), upgraded.members(discovered).map { it.media.mediaStoreId }.toSet())
            assertNull(upgraded.generateIfNeeded(64))
            assertEquals(4, upgraded.summaries().first().size)
        } finally { db.close() }
    }

    @Test
    fun newAndOlderImportsRefreshWithoutChangingSavedDecisionsOrRepeatingUnchangedScans() =
        runBlocking {
            val db = open()
            try {
                val repo = MomentRepository(db)
                val originals = event(1, base)
                db.libraryDao().upsertMedia(originals)
                repo.generateIfNeeded(64)
                val saved = repo.summaries().first().single().moment.momentId
                assertTrue(repo.save(saved))
                assertTrue(repo.rename(saved, "My preserved trip"))
                val order =
                    repo
                        .members(saved)
                        .map { MediaKey(it.media.volumeName, it.media.mediaStoreId) }
                        .reversed()
                assertTrue(repo.reorder(saved, order))
                assertTrue(repo.setCover(saved, order.last()))
                val before = db.momentDao().allMembers(saved)
                db.libraryDao()
                    .upsertMedia(event(101, base - 10 * day) + event(201, base + 10 * day))
                assertNotNull(repo.generateIfNeeded(64))
                assertEquals(3, repo.summaries().first().size)
                assertEquals("My preserved trip", db.momentDao().moment(saved)?.title)
                assertEquals(before, db.momentDao().allMembers(saved))
                val cover = repo.summaries().first().single { it.moment.momentId == saved }
                assertEquals(order.last().mediaStoreId, cover.coverMediaStoreId)
                val revision = db.momentDiscoveryDao().discoveryRevision()
                db.libraryDao().upsertMedia(originals.map { it.copy(lastSeenScanId = 2) })
                assertEquals(revision, db.momentDiscoveryDao().discoveryRevision())
                assertNull(repo.generateIfNeeded(64))
            } finally {
                db.close()
            }
        }

    @Test
    fun changedFirstPhotoReusesSuggestionIdentityAndDoesNotCloneProtectedStories() = runBlocking {
        val db = open()
        try {
            val repo = MomentRepository(db)
            db.libraryDao().upsertMedia(event(1, base))
            repo.generateIfNeeded(64)
            val id = repo.summaries().first().single().moment.momentId
            db.libraryDao().upsertMedia(listOf(media(99, base - 1000)))
            repo.generateIfNeeded(64)
            assertEquals(listOf(id), repo.summaries().first().map { it.moment.momentId })
            assertEquals(9, repo.members(id).size)
            assertTrue(repo.dismiss(id))
            db.libraryDao().upsertMedia(listOf(media(100, base - 2000)))
            repo.generateIfNeeded(64)
            assertTrue(repo.summaries().first().isEmpty())
            assertEquals("DISMISSED", db.momentDao().moment(id)?.state)
            assertEquals(9, db.momentDao().allMembers(id).size)
            repo.restartGeneration()
            repo.generateIfNeeded(64)
            assertTrue(repo.summaries().first().isEmpty())
            assertEquals("DISMISSED", db.momentDao().moment(id)?.state)
            assertEquals(9, db.momentDao().allMembers(id).size)
        } finally {
            db.close()
        }
    }

    @Test
    fun cancelledReconciliationKeepsUnseenSuggestionsAndResumesAfterOlderMutation() = runBlocking {
        val db = open()
        try {
            val repo = MomentRepository(db)
            db.libraryDao()
                .upsertMedia((0L..11L).flatMap { event(it * 100 + 1, base + it * 10 * day) })
            repo.generateIfNeeded(64)
            val before = repo.summaries(50).first().map { it.moment.momentId }.toSet()
            db.libraryDao().upsertMedia(event(2001, base - 20 * day))
            try {
                repo.generateIfNeeded(64) {
                    if (!it.complete) throw CancellationException("fixture interrupt")
                }
                fail("Expected interruption")
            } catch (_: CancellationException) {}
            assertTrue(repo.summaries(50).first().map { it.moment.momentId }.containsAll(before))
            db.libraryDao().upsertMedia(event(3001, base - 40 * day))
            repo.generateIfNeeded(64)
            val after = repo.summaries(50).first().map { it.moment.momentId }
            assertEquals(14, after.size)
            assertEquals(14, after.toSet().size)
            assertTrue(after.containsAll(before))
            assertNull(repo.generateIfNeeded(64))
        } finally {
            db.close()
        }
    }

    @Test
    fun exclusionReferencesSurviveDiscoveryRefreshAndAllHiddenSavedStoriesStayBrowsable() =
        runBlocking {
            val db = open()
            try {
                val repo = MomentRepository(db)
                db.libraryDao().upsertMedia(event(1, base))
                repo.generateIfNeeded(64)
                val id = repo.summaries().first().single().moment.momentId
                repo.save(id)
                val original = db.momentDao().allMembers(id)
                val rules = MemoryExclusionRepository(db)
                val date =
                    java.time.Instant.ofEpochMilli(base)
                        .atZone(java.time.ZoneOffset.UTC)
                        .toLocalDate()
                val receipt = rules.addDate(MemoryDateRange(date, date, "UTC"))
                db.libraryDao().upsertMedia(event(101, base + 10 * day))
                repo.generateIfNeeded(64)
                assertEquals(original, db.momentDao().allMembers(id))
                assertTrue(repo.members(id).isEmpty())
                val page =
                    db.momentDao()
                        .browser("SAVED")
                        .load(PagingSource.LoadParams.Refresh(null, 20, false))
                        as PagingSource.LoadResult.Page
                assertEquals(id, page.data.single().moment.momentId)
                assertEquals(0L, page.data.single().memberCount)
                rules.removeDate(receipt.rule.ruleId)
                assertEquals(original.size, repo.members(id).size)
            } finally {
                db.close()
            }
        }

    @Test
    fun replayAfterEventCommitBeforePageCheckpointDoesNotDuplicateMergedSuggestions() =
        runBlocking {
            val db = open()
            try {
                val repo = MomentRepository(db)
                db.libraryDao().upsertMedia(event(1, base) + event(101, base + 10 * day))
                repo.generateIfNeeded(64)
                assertEquals(2, repo.summaries().first().size)
                // Date correction merges two previously separate suggestions. Simulate interruption
                // after the event transaction, with the durable page cursor still at the beginning.
                db.libraryDao().upsertMedia(event(101, base + 60_000))
                val candidates = db.momentDao().candidatePage(null, null, null, 64)
                val accumulator = MomentAccumulator(MomentRepository.AlgorithmVersion)
                candidates.forEach { assertNull(accumulator.offer(it)) }
                val event = requireNotNull(accumulator.finish())
                val selected = MomentAccumulator.selectMembers(event)
                val id = MomentAccumulator.stableMomentId(event, selected)
                val run =
                    MomentRunEntity(
                        MomentRepository.AlgorithmVersion,
                        java.util.UUID.randomUUID().toString(),
                        "RUNNING",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        0,
                        0,
                        1,
                        inputRevision = db.momentDiscoveryDao().discoveryRevision(),
                    )
                db.momentDao().deleteRun(MomentRepository.AlgorithmVersion)
                db.momentDao().upsertRun(run)
                val proposed =
                    MomentEntity(
                        id,
                        "AUTO",
                        "SUGGESTED",
                        MomentRepository.AlgorithmVersion,
                        event.startMillis,
                        event.endMillis,
                        null,
                        "AUTO",
                        false,
                        2,
                        2,
                    )
                val members =
                    selected.mapIndexed { ordinal, x ->
                        MomentMemberEntity(
                            id,
                            ordinal,
                            x.volumeName,
                            x.mediaStoreId,
                            x.generationModified,
                            "GENERATED",
                            x.score,
                        )
                    }
                val best = selected.maxBy { it.score }
                assertTrue(
                    db.momentDiscoveryDao()
                        .reconcile(
                            run,
                            proposed,
                            members,
                            MomentCoverEntity(id, best.volumeName, best.mediaStoreId, false),
                            event.candidates,
                        )
                )
                assertNull(
                    db.momentDao().run(MomentRepository.AlgorithmVersion)?.afterTimelineSortMillis
                )
                assertEquals(
                    2,
                    repo.summaries().first().size,
                ) // Cleanup must wait for successful finish.
                assertTrue(MomentRepository(db).generateIfNeeded(64)!!.complete)
                val surviving = repo.summaries().first().single().moment.momentId
                assertEquals(
                    members.map { it.copy(momentId = surviving) },
                    db.momentDao().allMembers(surviving),
                )
                assertNull(repo.generateIfNeeded(64))
                try {
                    db.momentDiscoveryDao().assertCurrent(run)
                    fail("A completed run must reject a stale checkpoint")
                } catch (_: MomentDiscoveryChanged) {}
            } finally {
                db.close()
            }
        }

    @Test
    fun repositoryBrowserLoadsWithoutHoldingAnOuterRoomTransaction() = runBlocking {
        val db = open()
        try {
            db.libraryDao().upsertMedia(event(1, base))
            val repo = MomentRepository(db)
            repo.generateIfNeeded(64)
            val result =
                kotlinx.coroutines.withTimeout(5000) {
                    repo.browserSource().load(PagingSource.LoadParams.Refresh(null, 20, false))
                }
            assertTrue(result is PagingSource.LoadResult.Page)
            assertEquals(1, (result as PagingSource.LoadResult.Page).data.size)
        } finally {
            db.close()
        }
    }

    @Test
    fun twoRoomInstancesShareOneFileGenerationLease() = runBlocking {
        val name = "moment-discovery-${java.util.UUID.randomUUID()}.db"
        fun connect() =
            Room.databaseBuilder(context, GalleryDatabase::class.java, name)
                .addCallback(MomentDiscoverySchema.Callback)
                .enableMultiInstanceInvalidation()
                .build()
        val first = connect()
        val second = connect()
        val reached = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        try {
            first
                .libraryDao()
                .upsertMedia((0L..11L).flatMap { event(it * 100 + 1, base + it * 10 * day) })
            val one =
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async {
                    MomentRepository(first).generateIfNeeded(64) {
                        if (!it.complete && !reached.isCompleted) {
                            reached.complete(Unit)
                            runBlocking { release.await() }
                        }
                    }
                }
            kotlinx.coroutines.withTimeout(10000) { reached.await() }
            val two =
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async {
                    MomentRepository(second).generateIfNeeded(64)
                }
            try {
                kotlinx.coroutines.delay(250)
                assertFalse(
                    "Second Room instance must wait for the current generation",
                    two.isCompleted,
                )
                release.complete(Unit)
                assertTrue(kotlinx.coroutines.withTimeout(10000) { one.await() }!!.complete)
                assertNull(kotlinx.coroutines.withTimeout(10000) { two.await() })
                assertEquals(12, MomentRepository(first).summaries(50).first().size)
            } finally {
                release.complete(Unit)
                one.cancel()
                two.cancel()
            }
        } finally {
            first.close()
            second.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun browserPagesAllSixtyWithDeterministicTiesAndStateFilters() = runBlocking {
        val db = open()
        try {
            db.libraryDao().upsertMedia(listOf(media(1, base)))
            repeat(60) { n ->
                val id = "page-${n.toString().padStart(2,'0')}"
                db.momentDao()
                    .upsertMoment(
                        MomentEntity(
                            id,
                            "MANUAL",
                            if (n % 2 == 0) "SAVED" else "SUGGESTED",
                            "fixture",
                            base,
                            base,
                            null,
                            "AUTO",
                            n % 2 == 0,
                            1,
                            1,
                        )
                    )
                db.momentDao()
                    .insertMembers(
                        listOf(MomentMemberEntity(id, 0, "discovery:fixture", 1, 1, "MANUAL", 1f))
                    )
            }
            for ((filter, count) in listOf(null to 60, "SAVED" to 30, "SUGGESTED" to 30)) {
                val source = db.momentDao().browser(filter)
                val ids = mutableListOf<String>()
                var page =
                    source.load(PagingSource.LoadParams.Refresh(null, 20, false))
                        as PagingSource.LoadResult.Page
                ids += page.data.map { it.moment.momentId }
                while (page.nextKey != null) {
                    page =
                        source.load(
                            PagingSource.LoadParams.Append(requireNotNull(page.nextKey), 20, false)
                        ) as PagingSource.LoadResult.Page
                    ids += page.data.map { it.moment.momentId }
                }
                assertEquals(count, ids.size)
                assertEquals(ids.sorted(), ids)
                assertEquals(count, ids.toSet().size)
            }
        } finally {
            db.close()
        }
    }
}
