package com.ugallery.core.database

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryDatabaseDeviceTest {
    private lateinit var database: GalleryDatabase
    private lateinit var dao: LibraryDao

    @Before
    fun createDatabase() {
        database =
            Room.inMemoryDatabaseBuilder(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    GalleryDatabase::class.java,
                )
                .build()
        dao = database.libraryDao()
    }

    @After fun closeDatabase() = database.close()

    @Test
    fun migration25To26PreservesChoicesAndInvalidatesOnlyChangedDiscoveryInputs() {
        val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GalleryDatabase::class.java)
        val name = "migration-v25-live-memories.db"
        helper.createDatabase(name, 25).apply {
            execSQL("INSERT INTO media_items(volumeName,mediaStoreId,mediaType,mimeType,sizeBytes,width,height,durationMillis,orientationDegrees,dateAddedSeconds,dateModifiedSeconds,timelineSortMillis,generationAdded,generationModified,isFavorite,isTrashed,isAccessible,lastSeenScanId) VALUES('external_primary',701,1,'image/jpeg',100,10,10,0,0,1,1,1000,1,2,0,0,1,1)")
            execSQL("INSERT INTO moments VALUES('saved','AUTO','SAVED','v1',1,2,'Edited trip','USER',1,10,20)")
            execSQL("INSERT INTO moment_runs VALUES('v1','run','COMPLETE',1000,701,'external_primary',NULL,NULL,NULL,0,1,20)")
            execSQL("INSERT INTO motion_key_frames VALUES('external_primary',701,2,1000,'frame.jpg','hash',100,'cover-revision',20)")
            execSQL("INSERT INTO gallery_restore_receipts VALUES('operation','snapshot',1,2,0,20)")
            close()
        }
        helper.runMigrationsAndValidate(name, 26, true, GalleryDatabaseFactory.Migration25To26).use { db ->
            fun revision(): Long = db.query("SELECT revision FROM moment_discovery_revision WHERE id=1").use {
                assertTrue(it.moveToFirst()); it.getLong(0)
            }
            assertEquals(0L, revision())
            db.query("SELECT title,isUserEdited FROM moments WHERE momentId='saved'").use {
                assertTrue(it.moveToFirst()); assertEquals("Edited trip",it.getString(0)); assertEquals(1,it.getInt(1))
            }
            db.query("SELECT inputRevision,openLastLatitude,openLastLongitude FROM moment_runs").use {
                assertTrue(it.moveToFirst()); assertEquals(-1L,it.getLong(0)); assertTrue(it.isNull(1)); assertTrue(it.isNull(2))
            }
            db.query("SELECT revision FROM motion_key_frames").use { assertTrue(it.moveToFirst()); assertEquals("cover-revision",it.getString(0)) }
            db.query("SELECT snapshotId FROM gallery_restore_receipts").use { assertTrue(it.moveToFirst()); assertEquals("snapshot",it.getString(0)) }
            db.execSQL("UPDATE media_items SET lastSeenScanId=99, width=10 WHERE mediaStoreId=701")
            assertEquals(0L,revision())
            db.execSQL("UPDATE media_items SET timelineSortMillis=-1000 WHERE mediaStoreId=701")
            assertEquals(1L,revision())
            db.execSQL("UPDATE media_items SET isFavorite=1 WHERE mediaStoreId=701")
            assertEquals(2L,revision())
            db.execSQL("UPDATE media_items SET isFavorite=1 WHERE mediaStoreId=701")
            assertEquals(2L,revision())
            db.execSQL("DELETE FROM media_items WHERE mediaStoreId=701")
            assertEquals(3L,revision())
            db.query("SELECT title FROM moments WHERE momentId='saved'").use { assertTrue(it.moveToFirst()); assertEquals("Edited trip",it.getString(0)) }
        }
    }

    @Test
    fun migration24To25PreservesOriginalsAndAddsOnlyLocalChoices() {
        val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GalleryDatabase::class.java)
        val name = "migration-v24-motion-portable.db"
        helper.createDatabase(name, 24).apply {
            execSQL("INSERT INTO media_items(volumeName,mediaStoreId,mediaType,mimeType,sizeBytes,width,height,durationMillis,orientationDegrees,dateAddedSeconds,dateModifiedSeconds,timelineSortMillis,generationAdded,generationModified,isFavorite,isTrashed,isAccessible,lastSeenScanId) VALUES('external_primary',701,1,'image/jpeg',100,10,10,0,0,1,1,1000,1,2,0,0,1,1)")
            close()
        }
        helper.runMigrationsAndValidate(name, 25, true, GalleryDatabaseFactory.Migration24To25).use { db ->
            db.query("SELECT generationModified,timelineSortMillis FROM media_items WHERE mediaStoreId=701").use {
                assertTrue(it.moveToFirst()); assertEquals(2L,it.getLong(0)); assertEquals(1000L,it.getLong(1))
            }
            for (table in listOf("motion_key_frames", "gallery_restore_receipts", "portable_timeline_overrides")) {
                db.query("SELECT COUNT(*) FROM $table").use { assertTrue(it.moveToFirst()); assertEquals(0,it.getInt(0)) }
            }
        }
    }

    @Test
    fun compositeIdentityKeepsSameIdOnDifferentVolumes() = runBlocking {
        dao.upsertMedia(
            listOf(
                media("external_primary", id = 42, sort = 1_000),
                media("1234-5678", id = 42, sort = 1_000),
            )
        )

        assertEquals(2, dao.mediaCount())
        assertNotNull(dao.media("external_primary", 42))
        assertNotNull(dao.media("1234-5678", 42))
    }

    @Test
    fun keysetUsesVolumeAsFinalTieBreakerWithoutSkippingRows() = runBlocking {
        val rows =
            listOf(
                media("volume-c", id = 7, sort = 2_000),
                media("volume-b", id = 7, sort = 2_000),
                media("volume-a", id = 7, sort = 2_000),
                media("external_primary", id = 99, sort = 1_000),
            )
        dao.upsertMedia(rows)

        val first = dao.firstTimelinePage(2)
        val cursor = first.last()
        val second =
            dao.timelinePageAfter(
                cursor.timelineSortMillis,
                cursor.mediaStoreId,
                cursor.volumeName,
                2,
            )

        assertEquals(
            rows.size,
            (first + second).map { it.volumeName to it.mediaStoreId }.distinct().size,
        )
        assertEquals(
            listOf("volume-c", "volume-b", "volume-a", "external_primary"),
            (first + second).map(MediaItemEntity::volumeName),
        )
    }

    @Test
    fun mediaPageAndCheckpointCommitTogether() = runBlocking {
        val checkpoint =
            MediaStoreCheckpointEntity(
                volumeName = "external_primary",
                providerVersion = "v1",
                generation = 10,
                lastScannedId = 42,
                activeScanId = 1,
                scanState = "RUNNING",
                lastSuccessfulSyncMillis = null,
            )
        dao.commitMediaPage(listOf(media("external_primary", 42, 1_000)), checkpoint)

        assertEquals(1, dao.mediaCount())
        assertEquals(checkpoint, dao.checkpoint("external_primary"))
    }

    @Test
    fun timelineQueryUsesKeysetIndex() {
        val cursor =
            database.openHelper.readableDatabase.query(
                "EXPLAIN QUERY PLAN SELECT * FROM media_items " +
                    "WHERE isAccessible = 1 AND isTrashed = 0 " +
                    "ORDER BY timelineSortMillis DESC, mediaStoreId DESC, volumeName DESC LIMIT 100"
            )
        val details = cursor.use { buildList { while (it.moveToNext()) add(it.getString(3)) } }
        val plan = details.joinToString()
        assertTrue(
            plan.contains("index_media_timeline_keyset") || plan.contains("index_media_moment_scan")
        )
    }

    @Test
    fun pagingSourceUsesBoundedCompositeKeysetPages() = runBlocking {
        dao.upsertMedia(
            listOf(
                media("volume-c", 7, 2_000),
                media("volume-b", 7, 2_000),
                media("volume-a", 7, 2_000),
                media("external_primary", 99, 1_000),
            )
        )
        val source = TimelinePagingSource(database)
        val first =
            source.load(
                PagingSource.LoadParams.Refresh(
                    key = null,
                    loadSize = 2,
                    placeholdersEnabled = false,
                )
            ) as PagingSource.LoadResult.Page
        val second =
            source.load(
                PagingSource.LoadParams.Append(
                    key = requireNotNull(first.nextKey),
                    loadSize = 2,
                    placeholdersEnabled = false,
                )
            ) as PagingSource.LoadResult.Page

        assertEquals(
            listOf("volume-c", "volume-b", "volume-a", "external_primary"),
            (first.data + second.data).map(MediaItemEntity::volumeName),
        )
    }

    @Test
    fun version23PreservesMemoryAndSmartAlbumChoicesWhileAddingEmptyExclusions() {
        val name="migration-v23-memory-exclusions.db"
        val helper=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),GalleryDatabase::class.java)
        helper.createDatabase(name,23).apply {
            execSQL("INSERT INTO moments VALUES ('memory','USER','SAVED','v1',1,2,'Keep story','USER',1,1,2)")
            execSQL("INSERT INTO smart_albums VALUES ('album','Keep album',NULL,NULL,NULL,2026,'UTC',1767225600000,1798761600000,1,'keep-revision',1,2)")
            close()
        }
        helper.runMigrationsAndValidate(name,24,true,GalleryDatabaseFactory.Migration23To24).use { db ->
            db.query("SELECT title,state,isUserEdited FROM moments").use { assertTrue(it.moveToFirst());assertEquals("Keep story",it.getString(0));assertEquals("SAVED",it.getString(1));assertEquals(1,it.getInt(2)) }
            db.query("SELECT name,revision,fromMillis,untilMillis FROM smart_albums").use { assertTrue(it.moveToFirst());assertEquals("Keep album",it.getString(0));assertEquals("keep-revision",it.getString(1));assertEquals(1767225600000L,it.getLong(2));assertEquals(1798761600000L,it.getLong(3)) }
            for(table in listOf("memory_date_exclusions","memory_person_exclusions","memory_person_sources")) db.query("SELECT COUNT(*) FROM $table").use { assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0)) }
        }
    }

    @Test
    fun version22PreservesOrganizationWhileAddingEmptySmartAlbumTables() {
        val name = "migration-v22-smart-albums.db"
        val helper =
            MigrationTestHelper(
                InstrumentationRegistry.getInstrumentation(),
                GalleryDatabase::class.java,
            )
        helper.createDatabase(name, 22).apply {
            execSQL(
                "INSERT INTO photo_stacks VALUES ('stack','Keep title','external_primary',42,'keep-revision',1,2)"
            )
            execSQL(
                "INSERT INTO document_archive_rule (id,enabled,category,minimumAgeDays,revision,lastRunCount,afterSortMillis,afterMediaStoreId,afterVolumeName) VALUES (1,1,'Receipt',30,'keep-rule',4,1000,42,'external_primary')"
            )
            close()
        }
        helper
            .runMigrationsAndValidate(name, 23, true, GalleryDatabaseFactory.Migration22To23)
            .use { migrated ->
                migrated.query("SELECT title,coverMediaStoreId,revision FROM photo_stacks").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("Keep title", it.getString(0))
                    assertEquals(42L, it.getLong(1))
                    assertEquals("keep-revision", it.getString(2))
                }
                migrated.query("SELECT revision,afterSortMillis FROM document_archive_rule").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("keep-rule", it.getString(0))
                    assertEquals(1000L, it.getLong(1))
                }
                for (table in listOf("smart_albums", "smart_album_exclusions")) migrated
                    .query("SELECT COUNT(*) FROM $table")
                    .use {
                        assertTrue(it.moveToFirst())
                        assertEquals(0, it.getInt(0))
                    }
            }
    }

    @Test
    fun version21PreservesDocumentRuleCursorWhileAddingEmptyStackTables() {
        val name = "migration-v21-stacks.db"
        val helper =
            MigrationTestHelper(
                InstrumentationRegistry.getInstrumentation(),
                GalleryDatabase::class.java,
            )
        helper.createDatabase(name, 21).apply {
            execSQL(
                "INSERT INTO document_archive_rule (id,enabled,category,minimumAgeDays,revision,lastRunCount,afterSortMillis,afterMediaStoreId,afterVolumeName) VALUES (1,1,'Receipt',30,'keep-rule',4,1000,42,'external_primary')"
            )
            close()
        }
        helper
            .runMigrationsAndValidate(name, 22, true, GalleryDatabaseFactory.Migration21To22)
            .apply {
                query(
                        "SELECT revision,afterSortMillis,afterMediaStoreId,afterVolumeName FROM document_archive_rule"
                    )
                    .use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals("keep-rule", cursor.getString(0))
                        assertEquals(1000L, cursor.getLong(1))
                        assertEquals(42L, cursor.getLong(2))
                        assertEquals("external_primary", cursor.getString(3))
                    }
                for (table in
                    listOf("photo_stacks", "photo_stack_members", "photo_stack_exclusions")) query(
                        "SELECT COUNT(*) FROM $table"
                    )
                    .use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(0, cursor.getInt(0))
                    }
                close()
            }
    }

    @Test
    fun version20RuleSurvivesCursorMigration() {
        val name = "migration-v20-document-cursor.db"
        val helper =
            MigrationTestHelper(
                InstrumentationRegistry.getInstrumentation(),
                GalleryDatabase::class.java,
            )
        helper.createDatabase(name, 20).apply {
            execSQL(
                "INSERT INTO document_archive_rule VALUES (1,1,'Note',7,'fixture-revision','fixture-run',123,4)"
            )
            close()
        }
        helper
            .runMigrationsAndValidate(name, 21, true, GalleryDatabaseFactory.Migration20To21)
            .apply {
                query(
                        "SELECT enabled,category,minimumAgeDays,revision,lastRunId,lastRunMillis,lastRunCount,afterSortMillis,afterMediaStoreId,afterVolumeName FROM document_archive_rule"
                    )
                    .use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(1, cursor.getInt(0))
                        assertEquals("Note", cursor.getString(1))
                        assertEquals(7, cursor.getInt(2))
                        assertEquals("fixture-revision", cursor.getString(3))
                        assertEquals("fixture-run", cursor.getString(4))
                        assertEquals(123L, cursor.getLong(5))
                        assertEquals(4, cursor.getInt(6))
                        assertTrue(cursor.isNull(7))
                        assertTrue(cursor.isNull(8))
                        assertTrue(cursor.isNull(9))
                    }
                close()
            }
    }

    @Test
    fun version19PreservesDocumentsAndStartsAutomaticArchiveDisabled() {
        val name = "migration-v19-documents.db"
        val helper =
            MigrationTestHelper(
                InstrumentationRegistry.getInstrumentation(),
                GalleryDatabase::class.java,
            )
        helper.createDatabase(name, 19).apply {
            execSQL(
                """INSERT INTO media_items (volumeName,mediaStoreId,mediaType,mimeType,displayName,sizeBytes,width,height,
                durationMillis,orientationDegrees,dateTakenMillis,dateAddedSeconds,dateModifiedSeconds,timelineSortMillis,
                generationAdded,generationModified,bucketId,bucketDisplayName,relativePath,isFavorite,isTrashed,isAccessible,lastSeenScanId)
                VALUES ('external_primary',991,1,'image/jpeg','Preserved receipt',100,10,10,0,0,1000,1,1,1000,1,1,1,'Fixture','Pictures/',0,0,1,1)"""
            )
            execSQL(
                "INSERT INTO document_annotations VALUES ('external_primary',991,'Receipt',123)"
            )
            execSQL("INSERT INTO archived_media VALUES ('external_primary',991,456)")
            close()
        }
        helper
            .runMigrationsAndValidate(
                name,
                21,
                true,
                GalleryDatabaseFactory.Migration19To20,
                GalleryDatabaseFactory.Migration20To21,
            )
            .apply {
                query("SELECT category FROM document_annotations WHERE mediaStoreId=991").use {
                    cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("Receipt", cursor.getString(0))
                }
                query("SELECT archivedAtMillis FROM archived_media WHERE mediaStoreId=991").use {
                    cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(456L, cursor.getLong(0))
                }
                query("SELECT COUNT(*) FROM document_archive_rule").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
                close()
            }
    }

    @Test
    fun exportedVersionOneMigratesToCurrentSchemaAndValidates() {
        val name = "migration-v1.db"
        val helper =
            MigrationTestHelper(
                InstrumentationRegistry.getInstrumentation(),
                GalleryDatabase::class.java,
            )
        helper.createDatabase(name, 1).close()
        helper
            .runMigrationsAndValidate(
                name,
                27,
                true,
                GalleryDatabaseFactory.Migration1To2,
                GalleryDatabaseFactory.Migration2To3,
                GalleryDatabaseFactory.Migration3To4,
                GalleryDatabaseFactory.Migration4To5,
                GalleryDatabaseFactory.Migration5To6,
                GalleryDatabaseFactory.Migration6To7,
                GalleryDatabaseFactory.Migration7To8,
                GalleryDatabaseFactory.Migration8To9,
                GalleryDatabaseFactory.Migration9To10,
                GalleryDatabaseFactory.Migration10To11,
                GalleryDatabaseFactory.Migration11To12,
                GalleryDatabaseFactory.Migration12To13,
                GalleryDatabaseFactory.Migration13To14,
                GalleryDatabaseFactory.Migration14To15,
                GalleryDatabaseFactory.Migration15To16,
                GalleryDatabaseFactory.Migration16To17,
                GalleryDatabaseFactory.Migration17To18,
                GalleryDatabaseFactory.Migration18To19,
                GalleryDatabaseFactory.Migration19To20,
                GalleryDatabaseFactory.Migration20To21,
                GalleryDatabaseFactory.Migration21To22,
                GalleryDatabaseFactory.Migration22To23,
                GalleryDatabaseFactory.Migration23To24,
                GalleryDatabaseFactory.Migration24To25,
                GalleryDatabaseFactory.Migration25To26,
                GalleryDatabaseFactory.Migration26To27,
            )
            .close()
    }

    @Test
    fun version17AddsArchiveAndActivityTables() {
        val name = "migration-v17-library-shell.db"
        val helper =
            MigrationTestHelper(
                InstrumentationRegistry.getInstrumentation(),
                GalleryDatabase::class.java,
            )
        helper.createDatabase(name, 17).close()
        helper
            .runMigrationsAndValidate(name, 18, true, GalleryDatabaseFactory.Migration17To18)
            .use { migrated ->
                migrated.query("SELECT COUNT(*) FROM archived_media").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
                migrated.query("SELECT COUNT(*) FROM activity_events").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            }
    }

    @Test
    fun archivedMediaLeavesTimelineAndAppearsInArchive() = runBlocking {
        dao.upsertMedia(
            listOf(
                media("external_primary", id = 1, sort = 2_000),
                media("external_primary", id = 2, sort = 1_000),
            )
        )
        dao.upsertArchived(ArchivedMediaEntity("external_primary", 1, archivedAtMillis = 3_000))

        assertEquals(listOf(2L), dao.firstTimelinePage(10).map(MediaItemEntity::mediaStoreId))
        assertEquals(listOf(1L), dao.firstArchivePage(10).map(MediaItemEntity::mediaStoreId))

        dao.deleteArchived("external_primary", 1)
        assertEquals(listOf(1L, 2L), dao.firstTimelinePage(10).map(MediaItemEntity::mediaStoreId))
    }

    @Test
    fun version16AddsParallelSemanticIndexTables() {
        val name = "migration-v16-semantic.db"
        val helper =
            MigrationTestHelper(
                InstrumentationRegistry.getInstrumentation(),
                GalleryDatabase::class.java,
            )
        helper.createDatabase(name, 16).close()
        helper
            .runMigrationsAndValidate(name, 17, true, GalleryDatabaseFactory.Migration16To17)
            .use { database ->
                database.query("SELECT COUNT(*) FROM semantic_indexes").use { cursor ->
                    assert(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
                database.query("SELECT COUNT(*) FROM semantic_embeddings").use { cursor ->
                    assert(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            }
    }

    @Test
    fun petLabelsBecomeMutuallyExclusiveWhenMigratingFromVersion15() {
        val name = "migration-v15-pets.db"
        val helper =
            MigrationTestHelper(
                InstrumentationRegistry.getInstrumentation(),
                GalleryDatabase::class.java,
            )
        helper.createDatabase(name, 15).apply {
            execSQL(
                "INSERT INTO media_labels VALUES " +
                    "('external_primary',1,'dog','Dog',0.95,'old')," +
                    "('external_primary',1,'cat','Cat',0.80,'old')," +
                    "('external_primary',2,'dog','Dog',0.70,'old')," +
                    "('external_primary',2,'cat','Cat',0.90,'old')," +
                    "('external_primary',3,'dog','Dog',0.85,'old')," +
                    "('external_primary',3,'cat','Cat',0.85,'old')"
            )
            close()
        }

        helper
            .runMigrationsAndValidate(name, 16, true, GalleryDatabaseFactory.Migration15To16)
            .use { database ->
                database
                    .query(
                        "SELECT mediaStoreId, canonicalLabel FROM media_labels ORDER BY mediaStoreId"
                    )
                    .use { cursor ->
                        val labels = buildList {
                            while (cursor.moveToNext()) add(
                                cursor.getLong(0) to cursor.getString(1)
                            )
                        }
                        assertEquals(listOf(1L to "dog", 2L to "cat", 3L to "dog"), labels)
                    }
            }
    }

    @Test
    fun corruptRebuildableIndexIsRecovered() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "corrupt-recovery.db"
        context.deleteDatabase(name)
        File(context.getDatabasePath(name).absolutePath).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(4_096) { 0x5a })
        }

        val recovered = GalleryDatabaseFactory.open(context, name)
        try {
            assertEquals(0, recovered.libraryDao().mediaCount())
        } finally {
            recovered.close()
            context.deleteDatabase(name)
        }
    }

    private fun media(volume: String, id: Long, sort: Long) =
        MediaItemEntity(
            volumeName = volume,
            mediaStoreId = id,
            mediaType = 1,
            mimeType = "image/jpeg",
            displayName = "$volume-$id.jpg",
            sizeBytes = 100,
            width = 10,
            height = 10,
            durationMillis = 0,
            orientationDegrees = 0,
            dateTakenMillis = sort,
            dateAddedSeconds = sort / 1_000,
            dateModifiedSeconds = sort / 1_000,
            timelineSortMillis = sort,
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
}
