package com.librestatic.lightforge.core.data

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.database.*
import com.librestatic.lightforge.core.model.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class PortableOrganizationRepositoryDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val volume = "volume:with:colon"
    private val momentId = "saved-memory"

    private fun media(id: Long, v: String = volume, day: Int = id.toInt()) =
        MediaItemEntity(
            v,
            id,
            1,
            "image/jpeg",
            "photo-$id.jpg",
            100,
            120,
            80,
            0,
            0,
            Instant.parse("2026-06-${day.toString().padStart(2,'0')}T12:00:00Z").toEpochMilli(),
            1,
            1,
            Instant.parse("2026-06-${day.toString().padStart(2,'0')}T12:00:00Z").toEpochMilli(),
            1,
            1,
            1,
            "Camera",
            "DCIM/Camera/",
            false,
            false,
            true,
            1,
        )

    private suspend fun seed(db: GalleryDatabase) {
        db.libraryDao().upsertMedia((1L..3L).map { media(it) })
        db.momentDao()
            .upsertMoment(
                MomentEntity(
                    momentId,
                    "USER",
                    "SAVED",
                    "fixture",
                    1,
                    2,
                    "Keep title",
                    "USER",
                    true,
                    1,
                    2,
                )
            )
        db.momentDao()
            .insertMembers(
                (1L..3L).map {
                    MomentMemberEntity(momentId, it.toInt() - 1, volume, it, 1, "USER", 1f)
                }
            )
        db.momentDao().upsertCover(MomentCoverEntity(momentId, volume, 1, true))
    }

    private suspend fun person(
        db: GalleryDatabase,
        id: Long,
        cluster: String = "person",
        version: String = "v1",
        v: String = volume,
        generation: Long = 1,
    ) {
        db.personDao()
            .upsertCluster(
                PersonClusterEntity(
                    cluster,
                    version,
                    ByteArray(128),
                    1,
                    "Known person",
                    false,
                    true,
                    1,
                    1,
                )
            )
        db.libraryDao()
            .replaceFaceDetection(
                FaceDetectionRunEntity(v, id, generation, "face", 1, 1),
                listOf(
                    DetectedFaceEntity(
                        v,
                        id,
                        0,
                        "face",
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
                listOf(FaceEmbeddingEntity(v, id, 0, "face", "embedding", ByteArray(128), 1))
            )
        db.personDao()
            .upsertMembership(PersonMembershipEntity(v, id, 0, cluster, version, "user", .9f, 1))
    }

    private suspend fun seedAll(db: GalleryDatabase) {
        seed(db)
        person(db, 1)
        db.libraryDao().upsertArchived(ArchivedMediaEntity(volume, 1, 7))
        db.documentDao().put(DocumentAnnotationEntity(volume, 1, "Excluded", 8))
        db.documentArchiveDao().putHistory(DocumentArchiveHistoryEntity(volume, 1, 1, "keep", null))
        db.documentArchiveDao()
            .putRule(
                DocumentArchiveRuleEntity(enabled = true, category = "Receipt", minimumAgeDays = 30)
            )
        val album =
            db.libraryDao()
                .insertVirtualAlbum(
                    VirtualAlbumEntity(
                        name = "Existing album",
                        normalizedName = "existing album",
                        createdAtMillis = 1,
                        updatedAtMillis = 2,
                    )
                )
        db.libraryDao()
            .addVirtualAlbumMedia((1L..3L).map { VirtualAlbumMediaEntity(album, volume, it, it) })
        db.photoStackDao()
            .insert(PhotoStackEntity("stack", "Keep stack", volume, 2, "oldrevision", 1, 2))
        db.photoStackDao()
            .insertMembers(
                (1L..3L).map { PhotoStackMemberEntity(volume, it, "stack", it.toInt() - 1) }
            )
        db.smartAlbumDao()
            .insert(
                SmartAlbumEntity(
                    "smart",
                    "Old smart",
                    null,
                    "person",
                    "v1",
                    null,
                    "UTC",
                    null,
                    null,
                    false,
                    "oldrevision",
                    1,
                    2,
                )
            )
        db.smartAlbumDao().exclude(SmartAlbumExclusionEntity("smart", volume, 3, "oldtoken", 9))
        val rules = MemoryExclusionRepository(db)
        rules.addPerson("person", "v1")
        rules.addDate(
            MemoryDateRange(LocalDate.parse("2026-06-02"), LocalDate.parse("2026-06-02"), "UTC")
        )
    }

    private suspend fun snapshot(
        db: GalleryDatabase,
        ids: List<Long> = listOf(1, 2, 3),
    ): PortableOrganizationSnapshot {
        val bindings =
            ids.map {
                PortableSourceBinding(
                    UUID.randomUUID().toString(),
                    MediaKey(volume, it),
                    1,
                    "a".repeat(64),
                    100,
                )
            }
        return PortableOrganizationRepository(db).export(bindings, "{\"schemaVersion\":1}")
    }

    private suspend fun targets(
        db: GalleryDatabase,
        s: PortableOrganizationSnapshot,
        ns: UUID,
    ): List<PortableRestoredSource> {
        return s.sources.mapIndexed { i, f ->
            val id = 101L + i
            db.libraryDao().upsertMedia(listOf(media(id, day = i + 1)))
            PortableRestoredSource(
                f.sourceId,
                MediaKey(volume, id),
                1,
                f.sha256,
                f.sizeBytes,
                ns.toString(),
            )
        }
    }

    private fun options(
        s: PortableOrganizationSnapshot,
        global: Boolean = true,
        partial: Boolean = false,
    ) =
        PortableImportOptions(
            partial,
            global,
            s.albums.associate { it.entityId to "Restored ${it.name}" },
        )

    private fun fixture(block: suspend (GalleryDatabase) -> Unit) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            seedAll(db)
            block(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun photoRecipesRoundTripRebindsNewGenerationAndOmitsStaleHistory() = fixture { db ->
        val originals = (1L..2L).map { id ->
            EditRecipe.forSource(MediaKey(volume, id), 1).copy(
                revision = id.toInt() + 2,
                operations = listOf(EditOperation.Crop(100, 100, 900, 800),
                    EditOperation.Tone(brightness = id.toFloat() / 10f),
                    EditOperation.RawDevelop(RawDevelopmentSettings())),
            ).also { db.editRecipeDao().replace(it, 100) }
        }
        db.editRecipeDao().replace(EditRecipe.forSource(MediaKey(volume, 3), 99), 100)
        val exported = snapshot(db)
        assertEquals(2, exported.schemaVersion)
        assertEquals(2, exported.photoRecipes.size)
        assertTrue(exported.scope.omittedReferences > 0)
        val restored = PortableOrganizationCodec.decode(PortableOrganizationCodec.encode(exported))
        val ns = UUID.randomUUID()
        val mapping = targets(db, restored, ns).map { target ->
            val row = db.libraryDao().media(target.key.volumeName, target.key.mediaStoreId)!!
            db.libraryDao().upsertMedia(listOf(row.copy(generationModified = 7)))
            target.copy(generationModified = 7)
        }
        val result = PortableOrganizationRepository(db).importSnapshot(restored, mapping, options(restored, partial = true), ns)
        restored.photoRecipes.forEach { recipe ->
            val target = mapping.single { it.sourceId == recipe.sourceId }
            val id = EditRecipeIds.forSource(target.key, 7)
            val actual = db.editRecipeDao().load(id)!!
            assertEquals(target.key, actual.source)
            assertEquals(7L, actual.sourceGenerationModified)
            assertEquals(recipe.revision, actual.revision)
            assertEquals(recipe.operations, actual.operations.map(EditOperationCodec::encode))
            assertEquals(id, result.createdIds["photo-recipe:${recipe.sourceId}"])
        }
        originals.forEach { assertEquals(it, db.editRecipeDao().load(it.recipeId)) }
        assertNull(db.editRecipeDao().recipeForSource(volume, mapping.last().key.mediaStoreId))
    }

    @Test
    fun malformedPhotoOperationsAndExistingDestinationRecipeRejectBeforeWrites() = fixture { db ->
        db.editRecipeDao().replace(EditRecipe.forSource(MediaKey(volume, 1), 1).append(EditOperation.Flip(true)), 100)
        val exported = snapshot(db)
        val ns = UUID.randomUUID()
        val mapping = targets(db, exported, ns)
        val malformed = exported.copy(photoRecipes = exported.photoRecipes.map { it.copy(operations = listOf("flip,not-a-direction")) })
        try {
            PortableOrganizationRepository(db).importSnapshot(malformed, mapping, options(exported, partial = true), ns)
            fail("Malformed edit must not enter a transaction")
        } catch (_: IllegalArgumentException) { }
        assertNull(db.editRecipeDao().recipeForSource(volume, mapping.first().key.mediaStoreId))
        val existing = EditRecipe.forSource(mapping.first().key, 1).append(EditOperation.Rotate(90))
        db.editRecipeDao().replace(existing, 200)
        try {
            PortableOrganizationRepository(db).importSnapshot(exported, mapping, options(exported, partial = true), ns)
            fail("A pre-existing destination recipe must remain unchanged")
        } catch (error: PortableOrganizationConflict) { assertEquals("TargetAlreadyOrganized", error.kind) }
        assertEquals(existing, db.editRecipeDao().load(existing.recipeId))
    }

    @Test
    fun photoOperationInsertFailureRollsBackOrganizationAndRecipeTogether() = fixture { db ->
        val original = EditRecipe.forSource(MediaKey(volume, 1), 1)
            .append(EditOperation.Rotate(90)).append(EditOperation.Flip(true))
        db.editRecipeDao().replace(original, 100)
        val exported = snapshot(db)
        val ns = UUID.randomUUID()
        val mapping = targets(db, exported, ns)
        val targetId = EditRecipeIds.forSource(mapping.first().key, 1)
        val beforeAlbums = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM virtual_albums").use { it.moveToFirst(); it.getLong(0) }
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_portable_photo BEFORE INSERT ON edit_operations WHEN NEW.ordinal=1 AND NEW.recipeId!='${original.recipeId}' BEGIN SELECT RAISE(ABORT,'fixture'); END"
        )
        try {
            PortableOrganizationRepository(db).importSnapshot(exported, mapping, options(exported, partial = true), ns)
            fail("The second operation must reject the whole organization transaction")
        } catch (_: android.database.SQLException) { }
        assertNull(db.editRecipeDao().load(targetId))
        assertEquals(original, db.editRecipeDao().load(original.recipeId))
        assertEquals(beforeAlbums, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM virtual_albums").use { it.moveToFirst(); it.getLong(0) })
    }

    @Test
    fun rawSnapshotPreservesHiddenMembersAndIndependentEqualDigestSources() = fixture { db ->
        val s = snapshot(db)
        assertEquals(3, s.sources.size)
        assertEquals(1, s.sources.map { it.sha256 }.distinct().size)
        assertEquals(3, s.sources.map { it.sourceId }.distinct().size)
        assertEquals(3, s.memories.single().members.size)
        assertEquals(listOf(0, 1, 2), s.memories.single().members.map { it.ordinal })
        assertEquals(s.sources.first().sourceId, s.memories.single().coverSourceId)
        assertEquals(1, s.memoryPeople.single().knownSourceIds.size)
        assertEquals("Excluded", s.decisions.single().documentCategory)
        assertTrue(s.decisions.single().autoArchiveHistoryPresent)
        assertNull(s.decisions.single().autoArchiveWrittenAtMillis)
        assertTrue(s.autoArchiveReview!!.enabled)
        assertEquals(s, PortableOrganizationCodec.decode(PortableOrganizationCodec.encode(s)))
    }

    @Test
    fun specialMediaPermissionExportsAndRestoresWithoutOriginInference() = fixture { db ->
        assertEquals(2, snapshot(db).schemaVersion)
        val original = requireNotNull(db.momentDao().observeMoment(momentId).first())
        db.momentDao().upsertMoment(original.copy(origin = "AUTO", includeSpecialMedia = true))
        val manualId = "manual-without-special-media"
        db.momentDao().upsertMoment(original.copy(momentId = manualId, origin = "MANUAL",
            includeSpecialMedia = false))
        val snapshot = snapshot(db)
        assertEquals(3, snapshot.schemaVersion)
        val byId = snapshot.memories.associateBy { it.entityId }
        assertTrue(byId.getValue(momentId).includeSpecialMedia)
        assertFalse(byId.getValue(manualId).includeSpecialMedia)
        val decoded = PortableOrganizationCodec.decode(PortableOrganizationCodec.encode(snapshot))
        val namespace = UUID.randomUUID()
        val result = PortableOrganizationRepository(db).importSnapshot(decoded,
            targets(db, decoded, namespace), options(decoded), namespace)
        for (memory in decoded.memories) {
            val restoredId = result.createdIds.getValue("memory:${memory.entityId}")
            assertNotEquals(memory.entityId, restoredId)
            val restored = requireNotNull(db.momentDao().observeMoment(restoredId).first())
            assertEquals(memory.origin, restored.origin)
            assertEquals(memory.includeSpecialMedia, restored.includeSpecialMedia)
            assertEquals(memory.title, restored.title)
        }
        assertTrue(requireNotNull(db.momentDao().observeMoment(momentId).first()).includeSpecialMedia)
        assertFalse(requireNotNull(db.momentDao().observeMoment(manualId).first()).includeSpecialMedia)
        assertEquals(listOf(0, 1, 2), db.momentDao().allMembers(momentId).map { it.ordinal })
    }

    @Test
    fun importUsesNewNamespaceAndKnownPersonPhotosNeverAnotherLocalIdentity() = fixture { db ->
        val s = snapshot(db)
        val ns = UUID.randomUUID()
        val m = targets(db, s, ns)
        val originalDates = MemoryExclusionRepository(db).dates().first()
        val result = PortableOrganizationRepository(db).importSnapshot(s, m, options(s), ns)
        assertEquals(1, result.reusedDateCount)
        assertTrue(result.createdMemoryDateIds.isEmpty())
        assertEquals(originalDates, MemoryExclusionRepository(db).dates().first())
        val restored = result.createdIds.getValue("memory:$momentId")
        assertEquals(3, db.momentDao().allMembers(restored).size)
        assertEquals(
            listOf(101L, 102L, 103L),
            db.momentDao().allMembers(restored).map { it.mediaStoreId },
        )
        assertEquals(3, db.momentDao().allMembers(momentId).size)
        val rule =
            MemoryExclusionRepository(db).people().first().first {
                it.ruleId in result.createdMemoryPersonIds
            }
        assertTrue(rule.clusterId.startsWith("portable:$ns:"))
        assertNotEquals("person", rule.clusterId)
        assertEquals("Known person", rule.displayName)
        assertEquals(1L, MemoryExclusionRepository(db).knownSourceCount(rule.ruleId).first())
        assertEquals(
            listOf(103L),
            MomentRepository(db).members(restored).map { it.media.mediaStoreId },
        )
        val smart = db.smartAlbumDao().get(result.createdIds.getValue("smart:smart"))!!
        assertTrue(smart.personClusterId!!.startsWith("portable:$ns:"))
        assertNotEquals("oldrevision", smart.revision)
        assertEquals("oldrevision", db.smartAlbumDao().get("smart")!!.revision)
        assertEquals("Keep title", db.momentDao().moment(restored)!!.title)
    }

    @Test
    fun globalRulesDefaultOffAndReviewOnlySettingsNeverActivateAutomation() = fixture { db ->
        val s = snapshot(db)
        val ns = UUID.randomUUID()
        val m = targets(db, s, ns)
        val before = db.documentArchiveDao().rule()
        val result =
            PortableOrganizationRepository(db).importSnapshot(s, m, options(s, global = false), ns)
        assertEquals(2, result.skippedGlobalRules)
        assertTrue(result.createdMemoryPersonIds.isEmpty())
        assertEquals(2, result.reviewOnlyItems)
        assertEquals(before, db.documentArchiveDao().rule())
    }

    @Test
    fun partialImportAndNameConflictsRequireExplicitReviewBeforeWrites() = fixture { db ->
        val s = snapshot(db, listOf(1))
        assertTrue(s.scope.partial)
        assertTrue(s.scope.omittedReferences > 0)
        val ns = UUID.randomUUID()
        val m = targets(db, s, ns)
        var failure: Throwable? = null
        try {
            PortableOrganizationRepository(db).importSnapshot(s, m, options(s), ns)
        } catch (e: Throwable) {
            failure = e
        }
        assertTrue(failure is PortableOrganizationConflict)
        failure = null
        try {
            PortableOrganizationRepository(db)
                .importSnapshot(s, m, PortableImportOptions(allowPartial = true), ns)
        } catch (e: Throwable) {
            failure = e
        }
        assertEquals("VirtualAlbumNameExists", (failure as PortableOrganizationConflict).kind)
        val result =
            PortableOrganizationRepository(db).importSnapshot(s, m, options(s, partial = true), ns)
        assertEquals(
            1,
            db.momentDao().allMembers(result.createdIds.getValue("memory:$momentId")).size,
        )
    }

    @Test
    fun outerReceiptTransactionFailureRollsBackEveryOrganizationWrite() = fixture { db ->
        val s = snapshot(db)
        val ns = UUID.randomUUID()
        val m = targets(db, s, ns)
        var imported: String? = null
        try {
            db.withTransaction {
                imported =
                    PortableOrganizationRepository(db)
                        .importSnapshot(s, m, options(s), ns)
                        .createdIds
                        .getValue("memory:$momentId")
                error("Receipt commit failed")
            }
        } catch (_: IllegalStateException) {}
        assertNotNull(imported)
        assertNull(db.momentDao().moment(imported!!))
        assertEquals(1, MemoryExclusionRepository(db).people().first().size)
        assertEquals(3, db.momentDao().allMembers(momentId).size)
        assertNull(db.photoStackDao().membership(volume, 101))
    }

    @Test
    fun changedTargetOrExistingDecisionAbortsWithoutOverwriting() = fixture { db ->
        val s = snapshot(db)
        val ns = UUID.randomUUID()
        val m = targets(db, s, ns)
        db.documentDao().put(DocumentAnnotationEntity(volume, 101, "Note", 77))
        var failure: Throwable? = null
        try {
            PortableOrganizationRepository(db).importSnapshot(s, m, options(s), ns)
        } catch (e: Throwable) {
            failure = e
        }
        assertEquals("TargetAlreadyOrganized", (failure as PortableOrganizationConflict).kind)
        assertEquals("Note", db.documentDao().get(volume, 101)!!.category)
        db.documentDao().clear(volume, 101)
        failure = null
        try {
            PortableOrganizationRepository(db)
                .importSnapshot(s, m.map { it.copy(generationModified = 2) }, options(s), ns)
        } catch (e: Throwable) {
            failure = e
        }
        assertTrue(failure is IllegalArgumentException)
        assertEquals(1, MemoryExclusionRepository(db).people().first().size)
    }

    @Test
    fun importedRowsInvalidateExistingLiveMemoryObserver() = fixture { db ->
        val s = snapshot(db)
        val ns = UUID.randomUUID()
        val m = targets(db, s, ns)
        coroutineScope {
            val expected = UUID.nameUUIDFromBytes("$ns|memory|$momentId".toByteArray()).toString()
            val observed =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(10000) {
                        MomentRepository(db).summaries().first { list ->
                            list.any { it.moment.momentId == expected }
                        }
                    }
                }
            yield()
            PortableOrganizationRepository(db).importSnapshot(s, m, options(s, global = false), ns)
            assertTrue(observed.await().any { it.moment.momentId == expected })
        }
    }
}
