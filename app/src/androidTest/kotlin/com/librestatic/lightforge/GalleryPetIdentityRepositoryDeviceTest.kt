package com.librestatic.lightforge

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.database.*
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.feature.petrecognition.*
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class GalleryPetIdentityRepositoryDeviceTest {
    private class Fixture : AutoCloseable {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        val repo = GalleryPetIdentityRepository(db)
        val fingerprint = "fixture-pet-model-v1"

        suspend fun enable() =
            repo.setAnalysisEnabled(repo.currentSummary().revision, true, fingerprint)

        suspend fun media(
            id: Long,
            added: Long = 10,
            modified: Long = 20,
            accessible: Boolean = true,
            trash: Boolean = false,
            type: Int = 1,
        ): PetMediaSource {
            db.libraryDao().upsertMedia(listOf(row(id, added, modified, accessible, trash, type)))
            return PetMediaSource(
                MediaKey("external_primary", id),
                modified,
                ContentUris.withAppendedId(
                    MediaStore.Images.Media.getContentUri("external_primary"),
                    id,
                ),
                added,
            )
        }

        suspend fun analyze(id: Long, kind: PetSpecies = PetSpecies.Cat): PetAnalyzedObservation {
            val source = media(id)
            val observation = observation(kind)
            assertTrue(repo.commitAnalysis(source, fingerprint, listOf(observation)))
            return observation
        }

        suspend fun edit(edit: PetEdit): PetEditResult =
            repo.edit(repo.currentSummary().revision, edit)

        override fun close() = db.close()
    }

    @Test
    fun keysetPagesExactReplayZeroDetectionAndCurrentEmbeddings() = runBlocking {
        Fixture().use { f ->
            assertTrue(f.enable())
            val observations = (1L..5L).map { f.analyze(it) }
            val first = f.repo.observationsPage(limit = 2)
            val second = f.repo.observationsPage(afterId = first.nextId, limit = 2)
            val third = f.repo.observationsPage(afterId = second.nextId, limit = 2)
            assertEquals(5, (first.items + second.items + third.items).map { it.id }.toSet().size)
            assertNull(third.nextId)
            val source = f.repo.eligibleSources(limit = 2)
            assertEquals(listOf(1L, 2L), source.items.map { it.key.mediaStoreId })
            assertEquals(
                3L,
                f.repo.eligibleSources(source.nextKey, 2).items.first().key.mediaStoreId,
            )
            val create =
                f.edit(
                    PetEdit.CreateIdentity(PetSpecies.Cat, "  Luna  ", setOf(observations[0].id))
                )
            assertTrue(create.applied)
            val before = f.repo.currentSummary()
            val exact = f.repo.eligibleSources(limit = 1).items.single()
            assertTrue(f.repo.commitAnalysis(exact, f.fingerprint, listOf(observation())))
            assertEquals(before, f.repo.currentSummary())
            assertEquals("Luna", f.repo.identitiesPage().items.single().name)
            assertEquals(
                1,
                f.repo.referenceEmbeddingsPage(PetSpecies.Cat, f.fingerprint).items.size,
            )
            assertEquals(512, f.repo.observationEmbedding(observations[0].id, f.fingerprint)!!.size)
            assertNull(f.repo.observationEmbedding(observations[0].id, "different-space"))
            val zero = f.media(6)
            assertTrue(f.repo.commitAnalysis(zero, f.fingerprint, emptyList()))
            assertTrue(f.repo.isAnalyzed(zero, f.fingerprint))
            assertEquals(5L, f.repo.currentSummary().observationCount)
        }
    }

    @Test
    fun createRenameSplitMergeUndoPreserveLinksAndExclusions() = runBlocking {
        Fixture().use { f ->
            f.enable()
            val a = f.analyze(1)
            val b = f.analyze(2)
            assertTrue(
                f.edit(PetEdit.CreateIdentity(PetSpecies.Cat, "Luna", setOf(a.id, b.id))).applied
            )
            val first = f.repo.identitiesPage().items.single().id
            assertTrue(f.edit(PetEdit.Rename(first, "Luna local")).applied)
            assertTrue(f.edit(PetEdit.Exclude(setOf(a.id))).applied)
            assertEquals(1L, f.repo.currentSummary().excludedCount)
            assertTrue(f.edit(PetEdit.Split(first, setOf(b.id), "Second")).applied)
            val second = f.repo.identitiesPage().items.single { it.id != first }.id
            val merge = f.edit(PetEdit.Merge(first, setOf(second)))
            assertTrue(merge.applied)
            assertEquals(1L, f.repo.currentSummary().identityCount)
            assertEquals(first, f.repo.observationsPage().items.single { it.id == b.id }.identityId)
            val undo = f.repo.undo(merge.revision, merge.undoToken!!)
            assertTrue(undo.applied)
            assertEquals(
                second,
                f.repo.observationsPage().items.single { it.id == b.id }.identityId,
            )
            assertEquals("Luna local", f.repo.identitiesPage().items.single { it.id == first }.name)
            assertTrue(f.repo.observationsPage().items.single { it.id == a.id }.excluded)
            assertFalse(f.repo.undo(undo.revision, merge.undoToken!!).applied)
            assertTrue(f.edit(PetEdit.Restore(setOf(a.id))).applied)
            assertEquals(0L, f.repo.currentSummary().excludedCount)
        }
    }

    @Test
    fun uncertainRequiresReviewAndDuplicatePhotoAssignmentIsAtomic() = runBlocking {
        Fixture().use { f ->
            f.enable()
            val source = f.media(1)
            val a = observation()
            val b = observation(PetSpecies.Uncertain)
            assertTrue(f.repo.commitAnalysis(source, f.fingerprint, listOf(a, b)))
            assertTrue(f.edit(PetEdit.CreateIdentity(PetSpecies.Cat, "Luna", setOf(a.id))).applied)
            val identity = f.repo.identitiesPage().items.single().id
            assertFalse(f.edit(PetEdit.AcceptSuggestion(identity, setOf(b.id))).applied)
            assertTrue(f.edit(PetEdit.SetSpecies(setOf(b.id), PetSpecies.Cat)).applied)
            val before = f.repo.currentSummary()
            assertFalse(f.edit(PetEdit.AcceptSuggestion(identity, setOf(b.id))).applied)
            assertEquals(before, f.repo.currentSummary())
            val c = f.analyze(2)
            assertTrue(f.edit(PetEdit.AcceptSuggestion(identity, setOf(c.id))).applied)
            // Exclusion does not permit a second observation of the same identity in one photo.
            assertTrue(f.edit(PetEdit.Exclude(setOf(a.id))).applied)
            assertFalse(f.edit(PetEdit.AcceptSuggestion(identity, setOf(b.id))).applied)
            assertTrue(f.edit(PetEdit.Restore(setOf(a.id))).applied)
            assertNull(f.repo.observationsPage().items.single { it.id == b.id }.identityId)
        }
    }

    @Test
    fun revisionCasLatestUndoAndNoOverwriteAcrossRepositories() = runBlocking {
        Fixture().use { f ->
            f.enable()
            val a = f.analyze(1)
            assertTrue(f.edit(PetEdit.CreateIdentity(PetSpecies.Cat, "First", setOf(a.id))).applied)
            val id = f.repo.identitiesPage().items.single().id
            val second = GalleryPetIdentityRepository(f.db)
            val before = f.repo.currentSummary()
            val outcomes = coroutineScope {
                listOf(
                        async { f.repo.edit(before.revision, PetEdit.Rename(id, "A")) },
                        async { second.edit(before.revision, PetEdit.Rename(id, "B")) },
                    )
                    .map { it.await() }
            }
            assertEquals(1, outcomes.count { it.applied })
            val latest = f.repo.currentSummary()
            assertFalse(f.repo.undo(latest.revision, before.undoToken!!).applied)
            assertTrue(f.repo.undo(latest.revision, latest.undoToken!!).applied)
            assertEquals("First", f.repo.identitiesPage().items.single().name)
            assertEquals(id, f.repo.observationsPage().items.single().identityId)
        }
    }

    @Test
    fun staleGenerationsRejectUndoMergeAndReanalysisNeverReassignsIdentity() = runBlocking {
        Fixture().use { f ->
            f.enable()
            val a = f.analyze(1)
            val b = f.analyze(2)
            f.edit(PetEdit.CreateIdentity(PetSpecies.Cat, "Keep A", setOf(a.id)))
            val aid = f.repo.identitiesPage().items.single().id
            val createB = f.edit(PetEdit.CreateIdentity(PetSpecies.Cat, "Keep B", setOf(b.id)))
            val bid = f.repo.identitiesPage().items.single { it.id != aid }.id
            val revised = f.media(2, modified = 21)
            assertFalse(f.repo.undo(createB.revision, createB.undoToken!!).applied)
            assertFalse(f.edit(PetEdit.Merge(aid, setOf(bid))).applied)
            assertEquals(2L, f.repo.currentSummary().identityCount)
            assertTrue(f.edit(PetEdit.Rename(bid, "Renamed stale")).applied)
            val new = observation()
            assertTrue(f.repo.commitAnalysis(revised, f.fingerprint, listOf(new)))
            assertNull(f.repo.observationsPage().items.single { it.id == new.id }.identityId)
            assertEquals(
                "Renamed stale",
                f.repo.identitiesPage().items.single { it.id == bid }.name,
            )
            assertEquals(
                bid,
                f.db.petIdentityDao().rawObservations(listOf(b.id)).single().identityId,
            )
            assertTrue(f.repo.observationsPage().items.none { it.id == b.id })
            val reused = f.media(2, added = 11, modified = 21)
            assertFalse(f.repo.isAnalyzed(reused, f.fingerprint))
            assertFalse(f.repo.isCurrent(revised))
            assertTrue(f.repo.observationsPage().items.none { it.id == new.id })
        }
    }

    @Test
    fun optOutErasesPetNamespaceButNeverMediaAndRejectsLateAnalysis() = runBlocking {
        Fixture().use { f ->
            f.enable()
            val a = f.analyze(1)
            f.edit(PetEdit.CreateIdentity(PetSpecies.Cat, "Owned", setOf(a.id)))
            val before = f.repo.currentSummary()
            val source = f.repo.eligibleSources().items.single()
            val mediaBefore = f.db.libraryDao().media("external_primary", 1)
            assertTrue(f.repo.setAnalysisEnabled(before.revision, false, null))
            val disabled = withTimeout(5000) { f.repo.summary.first { !it.enabled } }
            assertEquals(0L, disabled.identityCount)
            assertEquals(0L, disabled.observationCount)
            assertNull(disabled.undoToken)
            assertFalse(f.repo.commitAnalysis(source, f.fingerprint, listOf(observation())))
            assertFalse(f.repo.undo(disabled.revision, before.undoToken!!).applied)
            assertEquals(mediaBefore, f.db.libraryDao().media("external_primary", 1))
            assertTrue(f.enable())
            assertFalse(f.repo.isAnalyzed(source, f.fingerprint))
            assertEquals(0L, f.repo.currentSummary().observationCount)
        }
    }

    @Test
    fun invalidSelectionsCollidingIdsAndVisibilityLeaveNoPartialState() = runBlocking {
        Fixture().use { f ->
            f.enable()
            val a = f.analyze(1)
            val source2 = f.media(2)
            val before = f.repo.currentSummary()
            assertFalse(f.repo.commitAnalysis(source2, f.fingerprint, listOf(a)))
            assertFalse(f.repo.isAnalyzed(source2, f.fingerprint))
            assertEquals(before, f.repo.currentSummary())
            assertFalse(
                f.edit(PetEdit.CreateIdentity(PetSpecies.Cat, "x".repeat(81), setOf(a.id))).applied
            )
            assertFalse(
                f.edit(PetEdit.Exclude((1..2001).map { UUID.randomUUID().toString() }.toSet()))
                    .applied
            )
            assertEquals(before, f.repo.currentSummary())
            f.media(3, accessible = false)
            f.media(4, trash = true)
            f.media(5, type = 3)
            assertEquals(listOf(1L, 2L), f.repo.eligibleSources().items.map { it.key.mediaStoreId })
            val hidden = f.media(1, accessible = false)
            assertFalse(f.repo.isCurrent(hidden))
            assertEquals(0L, f.repo.currentSummary().observationCount)
            assertFalse(f.edit(PetEdit.Exclude(setOf(a.id))).applied)
            assertEquals(0L, f.repo.currentSummary().identityCount)
        }
    }

    @Test
    fun providerGenerationChangeRejectsAnalysisWhileRoomStillHasOldRow() = runBlocking {
        Fixture().use { f ->
            check(f.context.packageName == "com.librestatic.lightforge.pdfacceptance")
            val resolver = f.context.contentResolver
            val label = "lightforge-pet-adapter-${UUID.randomUUID()}.jpg"
            val uri =
                resolver.insert(
                    MediaStore.Images.Media.getContentUri("external_primary"),
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, label)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/LightforgePetAdapter/")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    },
                )!!
            try {
                val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                try {
                    resolver.openOutputStream(uri)!!.use {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
                    }
                } finally {
                    bitmap.recycle()
                }
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
                fun generations(): Pair<Long, Long> =
                    resolver
                        .query(
                            uri,
                            arrayOf(
                                MediaStore.MediaColumns.GENERATION_ADDED,
                                MediaStore.MediaColumns.GENERATION_MODIFIED,
                            ),
                            null,
                            null,
                            null,
                        )!!
                        .use {
                            check(it.moveToFirst())
                            it.getLong(0) to it.getLong(1)
                        }
                val (added, modified) = generations()
                val source = f.media(ContentUris.parseId(uri), added, modified)
                val production = GalleryPetIdentityRepository(f.db, resolver)
                assertTrue(production.setAnalysisEnabled(0, true, f.fingerprint))
                assertTrue(production.isCurrent(source))
                resolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "changed-$label")
                    },
                    null,
                    null,
                )
                assertNotEquals(modified, generations().second)
                assertEquals(
                    modified,
                    f.db
                        .libraryDao()
                        .media("external_primary", ContentUris.parseId(uri))!!
                        .generationModified,
                )
                assertFalse(production.isCurrent(source))
                assertFalse(production.commitAnalysis(source, f.fingerprint, listOf(observation())))
                assertNull(
                    f.db
                        .petIdentityDao()
                        .stamp("external_primary", ContentUris.parseId(uri), f.fingerprint)
                )
                assertEquals(0L, production.currentSummary().observationCount)
            } finally {
                resolver.delete(uri, null, null)
            }
        }
    }

    companion object {
        private fun observation(kind: PetSpecies = PetSpecies.Cat) =
            PetAnalyzedObservation(
                UUID.randomUUID().toString(),
                PetBox(.1f, .1f, .8f, .9f),
                kind,
                .95f,
                FloatArray(512).apply { this[0] = 1f },
            )

        private fun row(
            id: Long,
            added: Long,
            modified: Long,
            accessible: Boolean,
            trash: Boolean,
            type: Int,
        ) =
            MediaItemEntity(
                "external_primary",
                id,
                type,
                "image/jpeg",
                "pet-$id.jpg",
                100,
                20,
                20,
                0,
                0,
                1000,
                1,
                1,
                1000,
                added,
                modified,
                null,
                null,
                "Pictures/Fixture/",
                false,
                trash,
                accessible,
                1,
            )
    }
}
