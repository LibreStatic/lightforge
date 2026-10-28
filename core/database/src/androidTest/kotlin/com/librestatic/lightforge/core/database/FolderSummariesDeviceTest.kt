package com.librestatic.lightforge.core.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FolderSummariesDeviceTest {
    private fun insert(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        id: Long,
        type: Int,
        size: Long,
        sort: Long,
        path: String?,
        bucket: Long?,
        trashed: Int = 0,
        accessible: Int = 1,
        volume: String = "external_primary",
    ) {
        db.execSQL(
            "INSERT INTO media_items(volumeName,mediaStoreId,mediaType,sizeBytes,width,height,durationMillis," +
                "orientationDegrees,dateAddedSeconds,dateModifiedSeconds,timelineSortMillis,generationAdded," +
                "generationModified,bucketId,bucketDisplayName,relativePath,isFavorite,isTrashed,isAccessible,lastSeenScanId) " +
                "VALUES(?,?,?,?,1,1,0,0,1,1,?,1,1,?,?,?,0,?,?,1)",
            arrayOf<Any?>(volume, id, type, size, sort, bucket, path?.trimEnd('/')?.substringAfterLast('/'), path, trashed, accessible),
        )
    }

    @Test
    fun foldersGroupByExactPathAndMirrorPhysicalAlbumVisibility() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            GalleryDatabase::class.java,
        ).build()
        try {
            val db = database.openHelper.writableDatabase
            insert(db, 1, 1, 100, 1000, "DCIM/Camera/", 10)
            insert(db, 2, 3, 300, 3000, "DCIM/Camera/", 10)
            insert(db, 3, 1, 50, 9000, "DCIM/Camera/", 10, trashed = 1)
            insert(db, 4, 1, 10, 500, "DCIM/", 11)
            insert(db, 5, 1, 20, 700, null, 77)
            insert(db, 6, 1, 30, 800, null, 78)
            insert(db, 7, 1, 40, 900, "DCIM/Camera/", 10, volume = "1234-ABCD")
            // A folder of only inaccessible rows (hidden or emptied) is reported with no accessible rows,
            // and its inaccessible rows never inflate the counts of a folder that still has visible ones.
            insert(db, 8, 1, 999, 100, "Hidden/", 12, accessible = 0)
            insert(db, 9, 1, 999, 100, "DCIM/Camera/", 10, accessible = 0)
            val rows = database.libraryDao().folderSummaries().first()
            assertEquals(6, rows.size)
            val hiddenOnly = rows.single { it.relativePath == "Hidden/" }
            assertEquals(0L, hiddenOnly.accessibleCount)
            assertEquals(0L, hiddenOnly.directCount)
            assertNull(hiddenOnly.coverMediaStoreId)
            assertTrue(!hiddenOnly.isAvailable)
            val camera = rows.single { it.volumeName == "external_primary" && it.relativePath == "DCIM/Camera/" }
            assertEquals(2L, camera.directCount)
            assertEquals(2L, camera.accessibleCount)
            assertEquals(1L, camera.imageCount)
            assertEquals(1L, camera.videoCount)
            assertEquals(400L, camera.totalBytes)
            assertEquals(1000L, camera.minSortMillis)
            assertEquals(3000L, camera.maxSortMillis)
            assertEquals(2L, camera.coverMediaStoreId)
            assertTrue(camera.isAvailable)
            assertEquals(1L, rows.single { it.relativePath == "DCIM/" }.directCount)
            assertEquals(setOf(77L, 78L), rows.filter { it.relativePath == null }.map { it.bucketId }.toSet())
            assertEquals(1L, rows.single { it.volumeName == "1234-ABCD" }.directCount)
            // Counts agree with the album query for the same buckets.
            assertNull(rows.single { it.bucketId == 77L }.relativePath)
        } finally {
            database.close()
        }
    }

    @Test
    fun thirtyOneToThirtyTwoAddsRelativePathIndexAndKeepsRows() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "folder-index-migration-${UUID.randomUUID()}.db"
        val helper = MigrationTestHelper(instrumentation, GalleryDatabase::class.java)
        try {
            helper.createDatabase(name, 31).use { db ->
                insert(db, 701, 1, 100, 1000, "DCIM/Camera/", 10)
            }
            helper.runMigrationsAndValidate(name, 32, true, GalleryDatabaseFactory.Migration31To32).use { db ->
                db.query("SELECT relativePath FROM media_items WHERE mediaStoreId=701").use {
                    assertTrue(it.moveToFirst()); assertEquals("DCIM/Camera/", it.getString(0))
                }
                db.query("SELECT name FROM sqlite_master WHERE type='index' AND name='index_media_volume_relative_path'").use {
                    assertTrue(it.moveToFirst())
                }
            }
        } finally {
            check(context.deleteDatabase(name))
        }
    }

    @Test
    fun thirtyTwoToThirtyThreeAddsAnimatedFlagAndMarksGifs() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "animated-migration-${UUID.randomUUID()}.db"
        val helper = MigrationTestHelper(instrumentation, GalleryDatabase::class.java)
        try {
            helper.createDatabase(name, 32).use { db ->
                insert(db, 801, 1, 100, 1000, "Pictures/", 10)
                insert(db, 802, 1, 100, 1000, "Pictures/", 10)
                db.execSQL("UPDATE media_items SET mimeType='image/gif' WHERE mediaStoreId=801")
                db.execSQL("UPDATE media_items SET mimeType='image/webp' WHERE mediaStoreId=802")
            }
            helper.runMigrationsAndValidate(name, 33, true, GalleryDatabaseFactory.Migration32To33).use { db ->
                db.query("SELECT mediaStoreId, isAnimated FROM media_items ORDER BY mediaStoreId").use {
                    assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(1))
                    assertTrue(it.moveToNext()); assertEquals(0, it.getInt(1))
                }
            }
        } finally {
            check(context.deleteDatabase(name))
        }
    }

    @Test
    fun thirtyThreeToThirtyFourAddsProbeFlagDefaultingToDone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "probe-migration-${UUID.randomUUID()}.db"
        val helper = MigrationTestHelper(instrumentation, GalleryDatabase::class.java)
        try {
            helper.createDatabase(name, 33).use { db -> insert(db, 901, 1, 100, 1000, "Pictures/", 10) }
            helper.runMigrationsAndValidate(name, 34, true, GalleryDatabaseFactory.Migration33To34).use { db ->
                db.query("SELECT needsProbe FROM media_items WHERE mediaStoreId=901").use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                }
            }
        } finally {
            check(context.deleteDatabase(name))
        }
    }

    @Test
    fun thirtyFourToThirtyFiveCreatesJournalAndTriggers() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "journal-migration-${UUID.randomUUID()}.db"
        val helper = MigrationTestHelper(instrumentation, GalleryDatabase::class.java)
        try {
            helper.createDatabase(name, 34).use { db -> insert(db, 1, 1, 100, 1000, "Pictures/", 10) }
            helper.runMigrationsAndValidate(name, 35, true, GalleryDatabaseFactory.Migration34To35).use { db ->
                // Existing rows are not journalled; the trigger fires for later changes only.
                assertEquals(emptyList<Long>(), journalIds(db))
                db.execSQL("UPDATE media_items SET isFavorite=1 WHERE mediaStoreId=1")
                assertEquals(listOf(1L), journalIds(db))
            }
        } finally {
            check(context.deleteDatabase(name))
        }
    }

    @Test
    fun thirtyFiveToThirtySixCreatesScanMarks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "scan-marks-migration-${UUID.randomUUID()}.db"
        val helper = MigrationTestHelper(instrumentation, GalleryDatabase::class.java)
        try {
            helper.createDatabase(name, 35).use { db -> insert(db, 1, 1, 100, 1000, "Pictures/", 10) }
            helper.runMigrationsAndValidate(name, 36, true, GalleryDatabaseFactory.Migration35To36).use { db ->
                db.execSQL("INSERT INTO media_scan_marks(volumeName,mediaStoreId,scanId) VALUES('external_primary',1,7)")
                db.query("SELECT COUNT(*) FROM media_items").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
                db.query("SELECT scanId FROM media_scan_marks").use { assertTrue(it.moveToFirst()); assertEquals(7, it.getInt(0)) }
            }
        } finally {
            check(context.deleteDatabase(name))
        }
    }

    private fun mediaEntity(id: Long, scanId: Long, accessible: Boolean = true) = MediaItemEntity(
        volumeName = "external_primary", mediaStoreId = id, mediaType = 1, mimeType = "image/jpeg", displayName = "a.jpg",
        dateTakenMillis = null, bucketId = 10, bucketDisplayName = "Pictures", relativePath = "Pictures/", sizeBytes = 100,
        width = 10, height = 10, durationMillis = 0, orientationDegrees = 0, dateAddedSeconds = 1,
        dateModifiedSeconds = 1, timelineSortMillis = 1000, generationAdded = 1, generationModified = 2,
        isFavorite = false, isTrashed = false, isAccessible = accessible, lastSeenScanId = scanId,
    )

    private fun checkpoint(scanId: Long?) =
        MediaStoreCheckpointEntity("external_primary", "v1", 3, 2, scanId, "Running", null)

    @Test
    fun reconcilingUnchangedRowsDoesNotWriteMediaItemsAndCompletionUsesMarks() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            GalleryDatabase::class.java,
        ).build()
        try {
            val dao = database.libraryDao()
            val db = database.openHelper.writableDatabase
            fun changes(): Long = db.query("SELECT total_changes()").use { it.moveToFirst(); it.getLong(0) }
            // 1, 2 and 3 are indexed by an earlier scan (lastSeenScanId = 1); 4 is hidden.
            (1L..3L).forEach { insert(db, it, 1, 100, 1000, "Pictures/", 10) }
            insert(db, 4, 1, 100, 1000, "Pictures/", 10, accessible = 0)
            insert(db, 5, 1, 100, 1000, "Pictures/", 10)
            // A stale mark of an abandoned scan must not keep row 5 visible.
            dao.upsertScanMarks(listOf(MediaScanMarkEntity("external_primary", 5, 8)))

            // `insert` rows have generationModified 1; a page at that generation is unchanged.
            fun seen(id: Long) = mediaEntity(id, 9).copy(generationModified = 1)
            // Unchanged rows 1 and 2: only marks are written.
            val before = changes()
            dao.reconcileMediaPage(listOf(seen(1), seen(2)), checkpoint(9))
            db.query("SELECT COUNT(*) FROM media_items WHERE lastSeenScanId=9").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM media_scan_marks WHERE scanId=9").use {
                assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0))
            }
            // 2 mark inserts + 1 checkpoint upsert, nothing on media_items.
            assertEquals(3L, changes() - before)

            // Row 4 reappears (hidden row, unchanged generation); row 6 is new and stamped by upsert.
            dao.reconcileMediaPage(listOf(seen(4), mediaEntity(6, 9)), checkpoint(9))
            dao.completeVolumeScan(checkpoint(null).copy(scanState = "Complete"), 9)

            fun accessible(id: Long): Int = db.query("SELECT isAccessible FROM media_items WHERE mediaStoreId=$id").use {
                assertTrue(it.moveToFirst()); it.getInt(0)
            }
            assertEquals(1, accessible(1))
            assertEquals(1, accessible(2))
            assertEquals(0, accessible(3)) // neither upserted nor marked
            assertEquals(1, accessible(4)) // reappeared
            assertEquals(0, accessible(5)) // stale mark of another scan
            assertEquals(1, accessible(6)) // new row
            db.query("SELECT COUNT(*) FROM media_scan_marks").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun startingANewScanClearsOnlyMarksOfOtherScans() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            GalleryDatabase::class.java,
        ).build()
        try {
            val dao = database.libraryDao()
            val db = database.openHelper.writableDatabase
            dao.upsertScanMarks(
                listOf(
                    MediaScanMarkEntity("external_primary", 1, 8),
                    MediaScanMarkEntity("external_primary", 2, 9),
                    MediaScanMarkEntity("1234-ABCD", 1, 8),
                ),
            )
            dao.resetVolumeForScan(checkpoint(9))
            db.query("SELECT volumeName, mediaStoreId FROM media_scan_marks ORDER BY volumeName, mediaStoreId").use {
                val rows = buildList { while (it.moveToNext()) add(it.getString(0) to it.getLong(1)) }
                assertEquals(listOf("1234-ABCD" to 1L, "external_primary" to 2L), rows)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun searchJournalRecordsOnlyChangesThatAlterADocument() {
        val database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            GalleryDatabase::class.java,
        ).build()
        try {
            val db = database.openHelper.writableDatabase
            SearchIndexJournalSchema.installTriggers(db)
            SearchIndexJournalSchema.installTriggers(db) // idempotent
            fun take(): List<Long> = journalIds(db).also { db.execSQL("DELETE FROM search_index_journal") }

            insert(db, 1, 1, 100, 1000, "Pictures/", 10)
            assertEquals(listOf(1L), take())
            // Scan bookkeeping and probe state are not part of a document.
            db.execSQL("UPDATE media_items SET lastSeenScanId=9, needsProbe=1, sizeBytes=5 WHERE mediaStoreId=1")
            assertEquals(emptyList<Long>(), take())
            db.execSQL("UPDATE media_items SET displayName='a.jpg' WHERE mediaStoreId=1")
            assertEquals(listOf(1L), take())
            db.execSQL("UPDATE media_items SET isTrashed=1 WHERE mediaStoreId=1")
            assertEquals(listOf(1L), take())
            db.execSQL("UPDATE media_items SET isTrashed=0 WHERE mediaStoreId=1")
            take()

            db.execSQL("INSERT INTO media_labels(volumeName,mediaStoreId,canonicalLabel,rawLabel,confidence,modelVersion) VALUES('external_primary',1,'dog','Dog',0.9,'1.0.0')")
            assertEquals(listOf(1L), take())
            db.execSQL("INSERT INTO label_suppressions(canonicalLabel,suppressedAtMillis) VALUES('dog',1)")
            assertEquals(listOf(1L), take())
            db.execSQL("DELETE FROM label_suppressions WHERE canonicalLabel='dog'")
            assertEquals(listOf(1L), take())
            db.execSQL("INSERT INTO label_suppressions(canonicalLabel,suppressedAtMillis) VALUES('cat',1)")
            assertEquals(emptyList<Long>(), take())

            // Room's @Upsert updates in place: an unchanged row journals nothing.
            val row = runBlocking { database.libraryDao().searchRebuildRows("external_primary", listOf(1L)).single().media }
            runBlocking { database.libraryDao().upsertMediaRows(listOf(row)) }
            assertEquals(emptyList<Long>(), take())

            db.execSQL("DELETE FROM media_items WHERE mediaStoreId=1")
            assertEquals(listOf(1L), take())
        } finally {
            database.close()
        }
    }

    private fun journalIds(db: androidx.sqlite.db.SupportSQLiteDatabase): List<Long> =
        db.query("SELECT mediaStoreId FROM search_index_journal ORDER BY seq").use { c ->
            generateSequence { if (c.moveToNext()) c.getLong(0) else null }.toList()
        }

    @Test
    fun physicalAlbumsDropGhostFoldersOfMountedVolumesAndCountOnlyAccessibleRows() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            GalleryDatabase::class.java,
        ).build()
        try {
            val db = database.openHelper.writableDatabase
            insert(db, 1, 1, 10, 1000, "DCIM/Camera/", 10)
            insert(db, 2, 1, 10, 900, "DCIM/Camera/", 10)
            insert(db, 3, 1, 10, 800, "DCIM/Camera/", 10, accessible = 0)
            // Hidden, moved or renamed folder on a mounted volume: a ghost.
            insert(db, 4, 1, 10, 700, "Ghost/", 20, accessible = 0)
            // An unmounted volume has no accessible row at all, so its folder stays listed as unavailable.
            insert(db, 5, 1, 10, 600, "DCIM/Camera/", 10, accessible = 0, volume = "1234-ABCD")
            val rows = database.libraryDao().physicalAlbumOptions().first()
            assertEquals(2, rows.size)
            val camera = rows.single { it.volumeName == "external_primary" }
            assertEquals(2L, camera.itemCount)
            assertTrue(camera.isAvailable)
            val card = rows.single { it.volumeName == "1234-ABCD" }
            assertTrue(!card.isAvailable)
            assertTrue(rows.none { it.displayName == "Ghost" })
        } finally {
            database.close()
        }
    }
}
