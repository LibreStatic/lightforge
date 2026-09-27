package com.librestatic.lightforge

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.database.*
import com.librestatic.lightforge.core.mediastore.MediaStoreReader
import com.librestatic.lightforge.core.mediastore.MediaStoreUriFactory
import com.librestatic.lightforge.core.model.*
import com.librestatic.lightforge.feature.settings.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GalleryOrganizationBackupAdapterDeviceTest {
    @Test
    fun exactNewCopiesPreserveDifferentChoicesForEqualBytesAndSurviveRescan(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.librestatic.lightforge.pdfacceptance")
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        val resolver = context.contentResolver
        val reader = MediaStoreReader(resolver)
        val label = "Portable-${UUID.randomUUID()}"
        val originalMoment = "original-$label"
        val sourceUris = mutableListOf<Uri>()
        val restoredKeys = mutableListOf<MediaKey>()
        var session: LocalRestoreGallerySession? = null
        var testFailure: Throwable? = null
        val evidence =
            File(context.filesDir, "portable-adapter-${UUID.randomUUID()}").apply { mkdirs() }
        fun hash(bytes: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it.toInt() and 255)
            }
        fun bytes(uri: Uri) = resolver.openInputStream(uri)!!.use { it.readBytes() }
        try {
            val bitmap =
                Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).apply {
                    for (y in 0 until height) for (x in 0 until width) {
                        setPixel(x, y, android.graphics.Color.rgb(x * 5, y * 7, (x + y) * 3))
                    }
                }
            val jpeg =
                try {
                    ByteArrayOutputStream().use { out ->
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out))
                        out.toByteArray()
                    }
                } finally {
                    bitmap.recycle()
                }
            repeat(2) { index ->
                val uri =
                    resolver.insert(
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                        ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, "$label-$index.jpg")
                            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$label/")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        },
                    )!!
                sourceUris += uri
                resolver.openOutputStream(uri, "w")!!.use { it.write(jpeg) }
                assertEquals(
                    1,
                    resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                        null,
                        null,
                    ),
                )
                if (index == 0)
                    assertEquals(
                        1,
                        resolver.update(
                            uri,
                            ContentValues().apply { put(MediaStore.MediaColumns.IS_FAVORITE, 1) },
                            null,
                            null,
                        ),
                    )
                val key = MediaKey(MediaStore.VOLUME_EXTERNAL_PRIMARY, ContentUris.parseId(uri))
                val record = reader.readOne(key)!!
                assertEquals(index == 0, record.isFavorite)
                val entity = record.portableEntity()
                db.libraryDao().upsertMedia(listOf(entity))
                // The original has no EXIF capture time. Its explicit civil timeline is a durable
                // per-instance decision.
                db.portableTimelineOverrideDao()
                    .put(
                        PortableTimelineOverrideEntity(
                            key.volumeName,
                            key.mediaStoreId,
                            record.generationAdded,
                            946684800000L + index * 86400000L,
                            null,
                        )
                    )
                db.libraryDao().upsertMedia(listOf(entity))
            }
            val keys =
                sourceUris.map {
                    MediaKey(MediaStore.VOLUME_EXTERNAL_PRIMARY, ContentUris.parseId(it))
                }
            db.momentDao()
                .upsertMoment(
                    MomentEntity(
                        originalMoment,
                        "USER",
                        "SAVED",
                        "fixture",
                        946684800000,
                        946771200000,
                        label,
                        "USER",
                        true,
                        1,
                        2,
                    )
                )
            db.momentDao()
                .insertMembers(
                    keys.reversed().mapIndexed { ordinal, key ->
                        MomentMemberEntity(
                            originalMoment,
                            ordinal,
                            key.volumeName,
                            key.mediaStoreId,
                            reader.readOne(key)!!.generationModified,
                            "USER",
                            1f,
                        )
                    }
                )
            db.momentDao()
                .upsertCover(
                    MomentCoverEntity(
                        originalMoment,
                        keys[1].volumeName,
                        keys[1].mediaStoreId,
                        true,
                    )
                )
            val album =
                db.libraryDao()
                    .insertVirtualAlbum(
                        VirtualAlbumEntity(
                            name = label,
                            normalizedName = label.lowercase(),
                            createdAtMillis = 1,
                            updatedAtMillis = 2,
                        )
                    )
            db.libraryDao()
                .addVirtualAlbumMedia(
                    keys.map { VirtualAlbumMediaEntity(album, it.volumeName, it.mediaStoreId, 1) }
                )
            val originalRows = keys.map { db.libraryDao().media(it.volumeName, it.mediaStoreId)!! }
            val originalRecipes = keys.mapIndexed { index, key ->
                EditRecipe.forSource(key, originalRows[index].generationModified).copy(
                    revision = index + 4,
                    operations = listOf(EditOperation.Crop(100, 100, 900, 800),
                        EditOperation.Tone(brightness = 0.1f * (index + 1)),
                        EditOperation.Rotate(index * 90)),
                ).also { db.editRecipeDao().replace(it, 100) }
            }
            val entries =
                sourceUris.mapIndexed { index, uri ->
                    BackupManifest.Entry(
                        BackupManifest.path(index),
                        "$label-$index.jpg",
                        "image/jpeg",
                        jpeg.size.toLong(),
                        hash(bytes(uri)),
                        UUID.randomUUID().toString(),
                    )
                }
            assertEquals(entries[0].sha256, entries[1].sha256)
            assertNotEquals(entries[0].sourceId, entries[1].sourceId)
            val adapter = GalleryOrganizationBackupAdapter(context, db)
            val sidecar =
                adapter.export(
                    entries.mapIndexed { i, entry ->
                        LocalBackupSourceRef(entry, sourceUris[i].toString())
                    }
                )
            val manifest =
                BackupManifest(
                    entries,
                    BackupManifest.Organization(sidecar.schemaVersion, sidecar.bytes.size.toLong(), hash(sidecar.bytes)),
                )
            val snapshot = PortableOrganizationCodec.decode(sidecar.bytes)
            // Preference review consumes only this manifest-bound sidecar, without restoring
            // files or applying settings as a side effect of inspecting the backup.
            val preferencesBefore = com.librestatic.lightforge.core.preferences.GallerySettingsRepository(context).exportJson().toString()
            assertArrayEquals(
                snapshot.preferencesJsonForReview!!.toByteArray(Charsets.UTF_8),
                adapter.preferencesForReview(sidecar.bytes, manifest),
            )
            val changedSidecar = sidecar.bytes.copyOf().also { it[it.lastIndex] = 0 }
            try {
                adapter.preferencesForReview(changedSidecar, manifest)
                fail("Changed sidecar must not authorize preference review")
            } catch (_: IllegalArgumentException) { /* Exact manifest digest rejects changed bytes. */ }
            assertEquals(preferencesBefore, com.librestatic.lightforge.core.preferences.GallerySettingsRepository(context).exportJson().toString())
            assertEquals(0L, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM gallery_restore_receipts").use {
                check(it.moveToFirst()); it.getLong(0)
            })
            assertEquals(2, snapshot.sources.size)
            assertEquals(2, sidecar.schemaVersion)
            assertEquals(2, snapshot.photoRecipes.size)
            assertEquals(listOf(true, false), snapshot.sources.map { it.isFavorite })
            assertEquals(
                listOf(946684800000L, 946771200000L),
                snapshot.sources.map { it.timelineSortMillis },
            )
            val review = adapter.review(sidecar.bytes, manifest)
            assertEquals(2, review.sourceCount)
            assertTrue(review.canRestore)
            assertEquals(2, review.counts.single { it.kind == LocalBackupOrganizationKind.PhotoEdits }.count)
            session =
                adapter.beginRestore(
                    sidecar.bytes,
                    manifest,
                    LocalRestoreOrganizationOptions(allowPartial = true),
                )
            entries.forEach { entry -> session!!.stage(entry, ByteArrayInputStream(jpeg)) }
            val result = session!!.commit()
            assertEquals(2, result.files)
            assertEquals(
                2,
                db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM virtual_albums").use {
                    it.moveToFirst()
                    it.getInt(0)
                },
            )
            val restoredMoment =
                db.openHelper.readableDatabase
                    .query(
                        "SELECT momentId FROM moments WHERE title=? AND momentId!=?",
                        arrayOf(label, originalMoment),
                    )
                    .use {
                        assertTrue(it.moveToFirst())
                        val id = it.getString(0)
                        assertFalse(it.moveToNext())
                        id
                    }
            restoredKeys +=
                db.momentDao().allMembers(restoredMoment).map {
                    MediaKey(it.volumeName, it.mediaStoreId)
                }
            assertEquals(2, restoredKeys.distinct().size)
            assertTrue(restoredKeys.none { it in keys })
            // Story order is source 2 then source 1; same digest must not collapse those choices.
            restoredKeys.forEachIndexed { index, key ->
                val record = reader.readOne(key)!!
                assertEquals(index == 1, record.isFavorite)
                assertArrayEquals(jpeg, bytes(MediaStoreUriFactory.uriFor(key)))
                db.libraryDao().upsertMedia(listOf(record.portableEntity()))
                val reindexed = db.libraryDao().media(key.volumeName, key.mediaStoreId)!!
                assertEquals(
                    if (index == 0) 946771200000L else 946684800000L,
                    reindexed.timelineSortMillis,
                )
                assertNull(reindexed.dateTakenMillis)
                assertEquals(index == 1, reindexed.isFavorite)
                val originalIndex = 1 - index // The restored story keeps its reversed source order.
                val originalRecipe = originalRecipes[originalIndex]
                val restoredRecipe = db.editRecipeDao().load(EditRecipeIds.forSource(key, record.generationModified))!!
                assertEquals(originalRecipe.operations, restoredRecipe.operations)
                assertEquals(originalRecipe.revision, restoredRecipe.revision)
                assertEquals(key, restoredRecipe.source)
                assertEquals(record.generationModified, restoredRecipe.sourceGenerationModified)
                val renderer = com.librestatic.lightforge.core.editing.image.PhotoImageRenderer(resolver)
                val before = renderer.renderPreview(sourceUris[originalIndex], originalRecipe, 256)
                val after = renderer.renderPreview(MediaStoreUriFactory.uriFor(key), restoredRecipe, 256)
                try { assertTrue("Restored editor preview must retain the exact recipe", before.sameAs(after)) }
                finally { before.recycle(); after.recycle() }
                assertEquals(originalRecipe, db.editRecipeDao().load(originalRecipe.recipeId))
            }
            val receipt =
                db.openHelper.readableDatabase
                    .query(
                        "SELECT operationId FROM gallery_restore_receipts WHERE snapshotId=?",
                        arrayOf(snapshot.snapshotId),
                    )
                    .use {
                        assertTrue(it.moveToFirst())
                        it.getString(0)
                    }
            assertEquals(2, db.galleryRestoreReceiptDao().get(receipt)!!.files)
            session!!.abort() // Committed receipt must prevent rollback of successful copies.
            assertTrue(restoredKeys.all { reader.readOne(it) != null })
            assertEquals(
                originalRows,
                keys.map { db.libraryDao().media(it.volumeName, it.mediaStoreId)!! },
            )
            assertTrue(sourceUris.all { bytes(it).contentEquals(jpeg) })
            File(evidence, "result.json")
                .writeText(
                    """{"status":"PASS","sameDigestDistinctInstances":true,"newKeys":true,"orderedMembers":true,"favoriteReadback":true,"timelineAfterRescan":true,"originalsUnchanged":true,"receiptProtectsAbort":true,"photoRecipesRebound":true,"nativePreviewPixelsEqual":true,"files":2}"""
                )
        } catch (error: Throwable) {
            testFailure = error
            File(evidence, "failure.txt").writeText(error.stackTraceToString())
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            fun failed(error: Throwable) {
                if (cleanupFailure == null) cleanupFailure = error
                else cleanupFailure!!.addSuppressed(error)
            }
            try {
                session?.abort()
            } catch (error: Throwable) {
                failed(error)
            }
            try {
                db.openHelper.readableDatabase
                    .query(
                        "SELECT mm.volumeName,mm.mediaStoreId FROM moment_members mm JOIN moments m ON m.momentId=mm.momentId WHERE m.title=? AND m.momentId!=?",
                        arrayOf(label, originalMoment),
                    )
                    .use { c ->
                        while (c.moveToNext()) restoredKeys +=
                            MediaKey(c.getString(0), c.getLong(1))
                    }
            } catch (error: Throwable) {
                failed(error)
            }
            // Only IDs obtained from this isolated import and exact fixture originals are removed.
            restoredKeys.distinct().forEach {
                try {
                    resolver.delete(MediaStoreUriFactory.uriFor(it), null, null)
                } catch (error: Throwable) {
                    failed(error)
                }
            }
            sourceUris.forEach {
                try {
                    resolver.delete(it, null, null)
                } catch (error: Throwable) {
                    failed(error)
                }
            }
            try {
                db.close()
            } catch (error: Throwable) {
                failed(error)
            }
            cleanupFailure?.let {
                if (testFailure != null) testFailure!!.addSuppressed(it) else throw it
            }
        }
    }

    @Test
    fun manifestMismatchIsRejectedBeforeCreatingAnyRestoreSession(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.librestatic.lightforge.pdfacceptance")
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            val source = UUID.randomUUID().toString()
            val snapshot =
                com.librestatic.lightforge.core.model.PortableOrganizationSnapshot(
                    snapshotId = UUID.randomUUID().toString(),
                    originNamespace = UUID.randomUUID().toString(),
                    createdAtMillis = 1,
                    scope = com.librestatic.lightforge.core.model.PortableOrganizationScope(1, 1, 0),
                    sources =
                        listOf(
                            com.librestatic.lightforge.core.model.PortableSourceFacts(
                                source,
                                "a".repeat(64),
                                1,
                                com.librestatic.lightforge.core.model.PortableMediaKind.Image,
                                1,
                                null,
                                false,
                                null,
                            )
                        ),
                )
            val bytes = PortableOrganizationCodec.encode(snapshot)
            val descriptor =
                BackupManifest.Organization(
                    1,
                    bytes.size.toLong(),
                    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                        "%02x".format(it.toInt() and 255)
                    },
                )
            val manifest =
                BackupManifest(
                    listOf(
                        BackupManifest.Entry(
                            BackupManifest.path(0),
                            "fixture.jpg",
                            "image/jpeg",
                            1,
                            "b".repeat(64),
                            source,
                        )
                    ),
                    descriptor,
                )
            var error: Throwable? = null
            try {
                GalleryOrganizationBackupAdapter(context, db).review(bytes, manifest)
            } catch (e: Throwable) {
                error = e
            }
            assertTrue(error is IllegalArgumentException)
            assertEquals(
                0,
                db.openHelper.readableDatabase
                    .query("SELECT COUNT(*) FROM gallery_restore_receipts")
                    .use {
                        it.moveToFirst()
                        it.getInt(0)
                    },
            )
        } finally {
            db.close()
        }
    }
}
