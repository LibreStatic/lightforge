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
}
