package com.librestatic.lightforge.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class AlbumCoverMigrationDeviceTest {
    @Test fun thirtyToThirtyOnePreservesAlbumAndMembershipWithAutomaticDefault() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "album-cover-migration-${UUID.randomUUID()}.db"
        val helper = MigrationTestHelper(instrumentation, GalleryDatabase::class.java)
        try {
            helper.createDatabase(name, 30).use { db ->
                db.execSQL("INSERT INTO virtual_albums VALUES(1,'Original','original',10,20)")
                db.execSQL("INSERT INTO virtual_album_media VALUES(1,'fixture',7,30)")
            }
            helper.runMigrationsAndValidate(name, 31, true, GalleryDatabaseFactory.Migration30To31).use { db ->
                db.query("SELECT name,createdAtMillis,updatedAtMillis,chosenCoverVolumeName,chosenCoverMediaStoreId FROM virtual_albums WHERE albumId=1").use { c ->
                    assertTrue(c.moveToFirst()); assertEquals("Original",c.getString(0)); assertEquals(10L,c.getLong(1)); assertEquals(20L,c.getLong(2)); assertTrue(c.isNull(3)); assertTrue(c.isNull(4))
                }
                db.query("SELECT volumeName,mediaStoreId,addedAtMillis FROM virtual_album_media WHERE albumId=1").use { c ->
                    assertTrue(c.moveToFirst()); assertEquals("fixture",c.getString(0)); assertEquals(7L,c.getLong(1)); assertEquals(30L,c.getLong(2))
                }
            }
        } finally { check(context.deleteDatabase(name)) }
    }
}
