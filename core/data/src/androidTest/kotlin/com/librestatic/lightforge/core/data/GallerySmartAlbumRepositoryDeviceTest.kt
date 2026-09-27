package com.librestatic.lightforge.core.data

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.database.*
import com.librestatic.lightforge.core.model.MediaKey
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class GallerySmartAlbumRepositoryDeviceTest {
    private val volume = "external_primary"

    private fun key(id: Long, v: String = volume) = MediaKey(v, id)

    private fun media(
        id: Long,
        v: String = volume,
        time: Long = Instant.parse("2026-06-01T12:00:00Z").toEpochMilli(),
    ) =
        MediaItemEntity(
            v,
            id,
            1,
            "image/jpeg",
            "photo-$id.jpg",
            100,
            10,
            10,
            0,
            0,
            time,
            1,
            1,
            time,
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

    private suspend fun fixture(
        block: suspend (GalleryDatabase, GallerySmartAlbumRepository) -> Unit
    ) {
        val db =
            Room.inMemoryDatabaseBuilder(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    GalleryDatabase::class.java,
                )
                .build()
        try {
            db.libraryDao().upsertMedia((1L..4L).map { media(it) })
            block(db, GallerySmartAlbumRepository(db))
        } finally {
            db.close()
        }
    }

    private suspend fun label(
        db: GalleryDatabase,
        id: Long,
        confidence: Float = .9f,
        runGeneration: Long = 1,
        runModel: String = "labels-v1",
        labelModel: String = "labels-v1",
    ) {
        db.libraryDao()
            .replaceLabelResult(
                MediaLabelRunEntity(volume, id, runGeneration, runModel, 1),
                listOf(MediaLabelEntity(volume, id, "beach", "Beach", confidence, labelModel)),
            )
    }

    private suspend fun person(db: GalleryDatabase, id: Long) {
        db.personDao()
            .upsertCluster(
                PersonClusterEntity("person", "v1", ByteArray(128), 1, "Fixture", false, true, 1, 1)
            )
        db.libraryDao()
            .replaceFaceDetection(
                FaceDetectionRunEntity(volume, id, 1, "face-v1", 1, 1),
                listOf(
                    DetectedFaceEntity(
                        volume,
                        id,
                        0,
                        "face-v1",
                        100,
                        100,
                        500,
                        500,
                        50,
                        50,
                        550,
                        550,
                        0f,
                        0f,
                        0f,
                        .9f,
                        "[]",
                    )
                ),
            )
        db.libraryDao()
            .upsertFaceEmbeddings(
                listOf(
                    FaceEmbeddingEntity(volume, id, 0, "face-v1", "embedding-v1", ByteArray(128), 1)
                )
            )
        db.personDao()
            .upsertMembership(PersonMembershipEntity(volume, id, 0, "person", "v1", "user", .9f, 1))
    }

    private suspend fun count(db: GalleryDatabase, rule: SmartAlbumRule) =
        db.smartAlbumDao().countNow(SmartAlbumQuery.build(rule, count = true))

    private suspend fun album(db: GalleryDatabase, id: String) =
        requireNotNull(db.smartAlbumDao().get(id))

    private suspend fun savedCount(repo: GallerySmartAlbumRepository, id: String) =
        withTimeout(10000) { repo.count(id).first() }

    private suspend fun changed(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected stale state rejection")
        } catch (_: SmartAlbumChanged) {}
    }

    private suspend fun <T : Any> page(source: PagingSource<Int, T>) =
        (source.load(PagingSource.LoadParams.Refresh(null, 20, false))
                as PagingSource.LoadResult.Page)
            .data

    @Test
    fun previewIsReadOnlyAndAllFourRulesCombineWithAnd(): Unit = runBlocking {
        fixture { db, repo ->
            for (id in 1L..4L) label(db, id)
            person(db, 1)
            person(db, 2)
            db.libraryDao()
                .upsertMedia(
                    listOf(media(1).copy(isFavorite = true), media(3).copy(isFavorite = true))
                )
            val rule = SmartAlbumRule("beach", "person", "v1", 2026, "UTC", true)
            assertEquals(1L, count(db, rule))
            assertEquals(0L, repo.previewCount(rule, listOf(key(1))).first())
            assertTrue(page(db.smartAlbumDao().albums()).isEmpty())
            val id = repo.create("  My   favorites ", rule)
            assertEquals("My favorites", album(db, id).name)
            assertEquals(1L, savedCount(repo, id))
        }
    }

    @Test
    fun newMatchingMediaJoinsWithoutResavingAndCountInvalidates(): Unit = runBlocking {
        fixture { db, repo ->
            val id = repo.create("Live", SmartAlbumRule(favoritesOnly = true))
            assertEquals(0L, savedCount(repo, id))
            coroutineScope {
                val next =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(10000) { repo.count(id).first { it == 1L } }
                    }
                db.libraryDao().upsertMedia(listOf(media(5).copy(isFavorite = true)))
                assertEquals(1L, next.await())
            }
            assertEquals(
                5L,
                page(
                        db.smartAlbumDao()
                            .media(
                                SmartAlbumQuery.build(
                                    album(db, id).rule(),
                                    id,
                                    album(db, id).revision,
                                )
                            )
                    )
                    .single()
                    .mediaStoreId,
            )
        }
    }

    @Test
    fun eligibilityRejectsArchiveTrashInaccessibleAndVideo(): Unit = runBlocking {
        fixture { db, repo ->
            val id = repo.create("Images", SmartAlbumRule())
            db.libraryDao().upsertArchived(ArchivedMediaEntity(volume, 1, 1))
            db.libraryDao()
                .upsertMedia(
                    listOf(
                        media(2).copy(isTrashed = true),
                        media(3).copy(isAccessible = false),
                        media(4).copy(mediaType = 3, mimeType = "video/mp4"),
                    )
                )
            assertEquals(0L, savedCount(repo, id))
            assertEquals(4L, db.libraryDao().mediaCount())
        }
    }

    @Test
    fun labelsRequireCurrentGenerationMatchingModelConfidenceAndNoSuppression(): Unit =
        runBlocking {
            fixture { db, _ ->
                label(db, 1, confidence = .65f)
                label(db, 2, confidence = .64f)
                label(db, 3, runGeneration = 2)
                label(db, 4, labelModel = "wrong")
                val rule = SmartAlbumRule(topic = " BEAch ")
                assertEquals(1L, count(db, rule))
                db.libraryDao().suppressLabel(LabelSuppressionEntity("beach", 1))
                assertEquals(0L, count(db, rule))
                db.libraryDao().unsuppressLabel("beach")
                assertEquals(1L, count(db, rule))
                db.libraryDao().purgeLabelRuns()
                assertEquals(0L, count(db, rule))
            }
        }

    @Test
    fun missingOrRegeneratedPersonNeverBroadensAnExistingRule(): Unit = runBlocking {
        fixture { db, repo ->
            person(db, 1)
            val rule = SmartAlbumRule(personClusterId = "person", personAlgorithmVersion = "v1")
            val id = repo.create("Person", rule)
            assertEquals(1L, savedCount(repo, id))
            db.openHelper.writableDatabase.execSQL(
                "UPDATE person_clusters SET algorithmVersion='v2' WHERE clusterId='person'"
            )
            assertEquals(0L, savedCount(repo, id))
            db.openHelper.writableDatabase.execSQL("DELETE FROM person_clusters")
            repo.update(id, album(db, id).revision, "Renamed", rule)
            assertEquals("person", album(db, id).personClusterId)
            assertEquals(0L, savedCount(repo, id))
            changed { repo.create("Missing person", rule) }
        }
    }

    @Test
    fun personRequiresCurrentDetectionEmbeddingAndVisibility(): Unit = runBlocking {
        fixture { db, _ ->
            person(db, 1)
            val rule = SmartAlbumRule(personClusterId = "person", personAlgorithmVersion = "v1")
            assertEquals(1L, count(db, rule))
            db.openHelper.writableDatabase.execSQL("UPDATE person_clusters SET isHidden=1")
            assertEquals(0L, count(db, rule))
            db.openHelper.writableDatabase.execSQL("UPDATE person_clusters SET isHidden=0")
            db.libraryDao().upsertMedia(listOf(media(1).copy(generationModified = 2)))
            assertEquals(0L, count(db, rule))
            db.libraryDao().upsertMedia(listOf(media(1)))
            db.openHelper.writableDatabase.execSQL(
                "UPDATE face_embeddings SET detectionModelVersion='wrong'"
            )
            assertEquals(0L, count(db, rule))
        }
    }

    @Test
    fun yearIsHalfOpenAndSavedIntervalDoesNotDriftDuringRename(): Unit = runBlocking {
        fixture { db, repo ->
            val rule = SmartAlbumRule(year = 2026, zoneId = "America/Argentina/Buenos_Aires")
            val (from, until) = rule.bounds()
            db.libraryDao()
                .upsertMedia(
                    listOf(
                        media(1, time = from!! - 1),
                        media(2, time = from),
                        media(3, time = until!! - 1),
                        media(4, time = until),
                    )
                )
            val id = repo.create("Year", rule)
            assertEquals(2L, savedCount(repo, id))
            val current = album(db, id)
            // Simulate a stored civil interval originating from a different timezone database
            // release.
            db.smartAlbumDao().update(current.copy(fromMillis = from - 1))
            assertEquals(3L, savedCount(repo, id))
            repo.update(id, current.revision, "Renamed", rule)
            assertEquals(from - 1, album(db, id).fromMillis)
            assertEquals(3L, savedCount(repo, id))
        }
    }

    @Test
    fun exclusionsAreCompositeKeyedAndConditionalUndoKeepsNewerChoice(): Unit = runBlocking {
        fixture { db, repo ->
            db.libraryDao().upsertMedia(listOf(media(1, "sd")))
            val id = repo.create("Both volumes", SmartAlbumRule(), listOf(key(1)))
            assertEquals(4L, savedCount(repo, id))
            val revision = album(db, id).revision
            val first = repo.exclude(id, revision, key(1, "sd"))
            val second = repo.exclude(id, revision, key(1, "sd"))
            assertFalse(repo.undoExclude(first))
            assertEquals(3L, savedCount(repo, id))
            assertTrue(repo.undoExclude(second))
            assertFalse(repo.undoExclude(second))
            assertEquals(4L, savedCount(repo, id))
            assertTrue(repo.includeAgain(id, revision, key(1)))
            assertEquals(5L, savedCount(repo, id))
        }
    }

    @Test
    fun initialExclusionRevalidatesAtomicallyAndRejectsDuplicateKeys(): Unit = runBlocking {
        fixture { db, repo ->
            changed { repo.create("Rollback", SmartAlbumRule(), listOf(key(1), key(999))) }
            assertTrue(page(db.smartAlbumDao().albums()).isEmpty())
            try {
                repo.create("Duplicate", SmartAlbumRule(), listOf(key(1), key(1)))
                fail()
            } catch (_: IllegalArgumentException) {}
            assertTrue(page(db.smartAlbumDao().albums()).isEmpty())
        }
    }

    @Test
    fun staleRevisionsCannotEditExcludeDeleteOrExposeAnOldQuery(): Unit = runBlocking {
        fixture { db, repo ->
            val id = repo.create("All", SmartAlbumRule())
            val old = album(db, id)
            repo.update(id, old.revision, "Favorite", SmartAlbumRule(favoritesOnly = true))
            changed { repo.update(id, old.revision, "Old", SmartAlbumRule()) }
            changed { repo.exclude(id, old.revision, key(1)) }
            changed { repo.includeAgain(id, old.revision, key(1)) }
            changed { repo.delete(id, old.revision) }
            assertEquals(
                0L,
                db.smartAlbumDao()
                    .countNow(SmartAlbumQuery.build(old.rule(), id, old.revision, count = true)),
            )
            assertEquals("Favorite", album(db, id).name)
        }
    }

    @Test
    fun deletingAlbumOrSourceRemovesOnlyReferencesAndNeverAdoptsReusedIds(): Unit = runBlocking {
        fixture { db, repo ->
            val id = repo.create("Delete", SmartAlbumRule(), listOf(key(1)))
            val original =
                page(
                    db.libraryDao()
                        .rawTimelinePagingSource(
                            GalleryTimelineQuery.build(
                                com.librestatic.lightforge.core.preferences.LibrarySettings()
                            )
                        )
                )
            db.libraryDao().deleteMedia(volume, 1)
            db.libraryDao().upsertMedia(listOf(media(1)))
            assertTrue(page(db.smartAlbumDao().exclusions(id)).isEmpty())
            assertEquals(4L, savedCount(repo, id))
            repo.exclude(id, album(db, id).revision, key(2))
            repo.delete(id, album(db, id).revision)
            assertNull(db.smartAlbumDao().get(id))
            assertTrue(page(db.smartAlbumDao().exclusions(id)).isEmpty())
            assertEquals(
                original,
                page(
                    db.libraryDao()
                        .rawTimelinePagingSource(
                            GalleryTimelineQuery.build(
                                com.librestatic.lightforge.core.preferences.LibrarySettings()
                            )
                        )
                ),
            )
            assertEquals(0L, savedCount(repo, id))
        }
    }

    @Test
    fun rulesAndExclusionsSurviveClosingAndReopeningDatabase(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "smart-reopen-${UUID.randomUUID()}.db"
        var db = GalleryDatabaseFactory.open(context, name)
        try {
            db.libraryDao().upsertMedia(listOf(media(1), media(2)))
            val id =
                GallerySmartAlbumRepository(db)
                    .create(
                        "Persisted",
                        SmartAlbumRule(year = 2026, zoneId = "UTC"),
                        listOf(key(1)),
                    )
            val versionBeforeClose = db.openHelper.readableDatabase.version
            db.close()
            db = GalleryDatabaseFactory.open(context, name)
            assertEquals(versionBeforeClose, db.openHelper.readableDatabase.version)
            assertEquals("Persisted", album(db, id).name)
            assertEquals(1L, savedCount(GallerySmartAlbumRepository(db), id))
            assertEquals(1L, page(db.smartAlbumDao().exclusions(id)).single().mediaStoreId)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun pagingHasDeterministicVolumeTiesAndInvalidatesAfterExclusion(): Unit = runBlocking {
        fixture { db, repo ->
            db.libraryDao().upsertMedia(listOf(media(4, "volume-z"), media(4, "volume-a")))
            val id = repo.create("Paged", SmartAlbumRule())
            val a = album(db, id)
            val source = db.smartAlbumDao().media(SmartAlbumQuery.build(a.rule(), id, a.revision))
            val first =
                source.load(PagingSource.LoadParams.Refresh(null, 2, false))
                    as PagingSource.LoadResult.Page
            val second =
                source.load(PagingSource.LoadParams.Append(first.nextKey!!, 2, false))
                    as PagingSource.LoadResult.Page
            assertEquals(
                listOf("volume-z", "volume-a", volume),
                (first.data + second.data).take(3).map { it.volumeName },
            )
            repo.exclude(id, a.revision, key(4, "volume-z"))
            withTimeout(10000) { while (!source.invalid) delay(10) }
            assertEquals(5L, savedCount(repo, id))
        }
    }

    @Test
    fun maximumPreviewExclusionsRemainBoundAndHostileTopicsStayLiteral(): Unit = runBlocking {
        fixture { db, repo ->
            db.libraryDao().upsertMedia((5L..220L).map { media(it) })
            val exclusions = (1L..200L).map { key(it) }
            val id = repo.create("Bounded", SmartAlbumRule(), exclusions)
            assertEquals(20L, savedCount(repo, id))
            assertEquals(0L, count(db, SmartAlbumRule(topic = "x' OR 1=1 --")))
            assertEquals(20, page(db.smartAlbumDao().exclusions(id)).size)
        }
    }

    @Test
    fun topicPickerUsesOnlyCurrentEligibleUnsuppressedLabels(): Unit = runBlocking {
        fixture { db, repo ->
            label(db, 1, confidence = .65f)
            label(db, 2, runGeneration = 9)
            assertEquals(
                listOf("beach"),
                page(db.smartAlbumDao().topics(SmartAlbumQuery.MinimumLabelConfidence)),
            )
            db.libraryDao().upsertArchived(ArchivedMediaEntity(volume, 1, 1))
            assertTrue(
                page(db.smartAlbumDao().topics(SmartAlbumQuery.MinimumLabelConfidence)).isEmpty()
            )
            db.libraryDao().deleteArchived(volume, 1)
            db.libraryDao().suppressLabel(LabelSuppressionEntity("beach", 1))
            assertTrue(
                page(db.smartAlbumDao().topics(SmartAlbumQuery.MinimumLabelConfidence)).isEmpty()
            )
            assertNotNull(repo.source(key(1)).first())
            db.libraryDao().upsertMedia(listOf(media(1).copy(isTrashed = true)))
            assertNull(repo.source(key(1)).first())
        }
    }

    @Test
    fun editingPreviewRetainsSavedExclusionsAndUpdatesNewExclusionsAtomically(): Unit =
        runBlocking {
            fixture { db, repo ->
                val id = repo.create("Original", SmartAlbumRule(), listOf(key(1)))
                val row = album(db, id)
                assertEquals(2L, repo.previewCount(row.rule(), listOf(key(2)), row).first())
                changed {
                    repo.update(id, row.revision, "Not saved", row.rule(), listOf(key(2), key(999)))
                }
                assertEquals(row, album(db, id))
                assertEquals(3L, savedCount(repo, id))
                repo.update(id, row.revision, "Saved", row.rule(), listOf(key(2)))
                assertEquals(2L, savedCount(repo, id))
                assertEquals("Saved", album(db, id).name)
                assertEquals(
                    setOf(1L, 2L),
                    page(db.smartAlbumDao().exclusions(id)).map { it.mediaStoreId }.toSet(),
                )
            }
        }

    @Test
    fun personPickerCoverUsesMatchingCurrentPhotoAndMissingIdentityStaysNull(): Unit = runBlocking {
        fixture { db, repo ->
            person(db, 1)
            assertEquals("Fixture", repo.person("person", "v1").first()?.displayName)
            assertEquals(1L, repo.personCover("person", "v1").first()?.mediaStoreId)
            db.libraryDao().upsertMedia(listOf(media(1).copy(generationModified = 2)))
            assertNull(repo.personCover("person", "v1").first())
            db.openHelper.writableDatabase.execSQL("UPDATE person_clusters SET isHidden=1")
            assertNull(repo.person("person", "v1").first())
        }
    }
}
