package com.librestatic.lightforge.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MomentParticipantsMigrationDeviceTest {
    @Test
    fun real28To29PreservesStoriesAndAddsEmptyCascadingLocalParticipantState() =
        runBlocking<Unit> {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val name = "participants-migration-${UUID.randomUUID()}.db"
            val helper = MigrationTestHelper(instrumentation, GalleryDatabase::class.java)
            try {
                helper.createDatabase(name, 28).use { db ->
                    for (id in 1..2) db.execSQL(
                        "INSERT INTO media_items(volumeName,mediaStoreId,mediaType,mimeType,sizeBytes,width,height,durationMillis,orientationDegrees,dateAddedSeconds,dateModifiedSeconds,timelineSortMillis,generationAdded,generationModified,isFavorite,isTrashed,isAccessible,lastSeenScanId) VALUES('fixture',$id,1,'image/jpeg',100,10,10,0,0,1,1,1000,1,2,1,0,1,1)"
                    )
                    for (id in listOf("saved", "other")) db.execSQL(
                        "INSERT INTO moments VALUES('$id','AUTO','SAVED','v1',1,2,'Owned title','USER',1,10,20)"
                    )
                    db.execSQL(
                        "INSERT INTO moment_members VALUES('saved',0,'fixture',2,2,'USER',1.0)"
                    )
                    db.execSQL(
                        "INSERT INTO moment_members VALUES('saved',1,'fixture',1,2,'USER',1.0)"
                    )
                    db.execSQL("INSERT INTO moment_covers VALUES('saved','fixture',1,1)")
                    db.execSQL(
                        "INSERT INTO gallery_restore_receipts VALUES('operation','snapshot',1,2,0,20)"
                    )
                    db.execSQL(
                        "INSERT INTO person_clusters VALUES('person','v1',X'0102',1,'Local name',0,1,10,20)"
                    )
                }
                helper
                    .runMigrationsAndValidate(
                        name,
                        29,
                        true,
                        GalleryDatabaseFactory.Migration28To29,
                    )
                    .close()
                val db = GalleryDatabaseFactory.open(context, name)
                try {
                    assertEquals("Owned title", db.momentDao().moment("saved")!!.title)
                    assertEquals(
                        listOf(2L, 1L),
                        db.momentDao().allMembers("saved").map { it.mediaStoreId },
                    )
                    db.openHelper.readableDatabase
                        .query(
                            "SELECT mediaStoreId,isUserSelected FROM moment_covers WHERE momentId='saved'"
                        )
                        .use {
                            assertTrue(it.moveToFirst())
                            assertEquals(1L, it.getLong(0))
                            assertEquals(1, it.getInt(1))
                        }
                    db.openHelper.readableDatabase
                        .query("SELECT snapshotId FROM gallery_restore_receipts")
                        .use {
                            assertTrue(it.moveToFirst())
                            assertEquals("snapshot", it.getString(0))
                        }
                    assertNull(db.momentParticipantsDao().state("saved"))
                    assertTrue(db.momentParticipantsDao().selected("saved").isEmpty())
                    val dao = db.momentParticipantsDao()
                    dao.initialize(MomentParticipantStateEntity("saved", "MANUAL", 3))
                    dao.initialize(MomentParticipantStateEntity("other", "MANUAL", 1))
                    dao.insert(
                        listOf(
                            MomentParticipantEntity("saved", "person"),
                            MomentParticipantEntity("other", "person"),
                        )
                    )
                    assertEquals(listOf("person"), dao.selected("saved"))
                    db.momentDao().delete("saved")
                    assertNull(dao.state("saved"))
                    assertTrue(dao.selected("saved").isEmpty())
                    assertNotNull(dao.state("other"))
                    assertEquals(listOf("person"), dao.selected("other"))
                    db.personDao().deleteCluster("person")
                    assertTrue(dao.selected("other").isEmpty())
                    assertEquals("MANUAL", dao.state("other")!!.mode)
                    db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use {
                        assertFalse(it.moveToFirst())
                    }
                } finally {
                    db.close()
                }
                val reopened = GalleryDatabaseFactory.open(context, name)
                try {
                    assertEquals(1L, reopened.momentParticipantsDao().state("other")!!.revision)
                } finally {
                    reopened.close()
                }
            } finally {
                context.deleteDatabase(name)
            }
        }
}
