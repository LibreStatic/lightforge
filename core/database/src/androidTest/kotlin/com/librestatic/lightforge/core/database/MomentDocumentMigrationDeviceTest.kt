package com.librestatic.lightforge.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MomentDocumentMigrationDeviceTest {
    private suspend fun withDatabase(context: android.content.Context, name: String, block: suspend (GalleryDatabase) -> Unit) {
        val db = GalleryDatabaseFactory.open(context, name)
        try { block(db) } finally { db.close() }
    }

    @Test fun actual27MigrationInvalidatesOldRunsOnceAndPreservesUserStories() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "document-memory-migration-${UUID.randomUUID()}.db"
        val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GalleryDatabase::class.java)
        try {
            helper.createDatabase(name, 27).use { db ->
                db.execSQL("INSERT INTO moment_discovery_revision VALUES(1,77)")
                for (id in 1..2) db.execSQL("INSERT INTO media_items(volumeName,mediaStoreId,mediaType,mimeType,sizeBytes,width,height,durationMillis,orientationDegrees,dateAddedSeconds,dateModifiedSeconds,timelineSortMillis,generationAdded,generationModified,isFavorite,isTrashed,isAccessible,lastSeenScanId) VALUES('external_primary',$id,1,'image/jpeg',100,10,10,0,0,1,1,1000,1,2,1,0,1,1)")
                db.execSQL("INSERT INTO document_annotations VALUES('external_primary',1,'Receipt',10)")
                db.execSQL("INSERT INTO moments VALUES('saved','AUTO','SAVED','v1',1,2,'Edited trip','USER',1,10,20)")
                db.execSQL("INSERT INTO moment_members VALUES('saved',0,'external_primary',1,2,'USER',1.0)")
                db.execSQL("INSERT INTO moment_members VALUES('saved',1,'external_primary',2,2,'USER',1.0)")
                db.execSQL("INSERT INTO moment_covers VALUES('saved','external_primary',1,1)")
                db.execSQL("INSERT INTO moment_runs(algorithmVersion,runId,status,openItemCount,processedItems,updatedAtMillis,inputRevision) VALUES('v1','old-run','COMPLETE',0,2,20,77)")
                db.execSQL("INSERT INTO gallery_restore_receipts VALUES('operation','snapshot',1,2,0,20)")
            }
            helper.runMigrationsAndValidate(name, 28, true, GalleryDatabaseFactory.Migration27To28).close()
            withDatabase(context, name) { db ->
                assertEquals(78L, db.momentDiscoveryDao().discoveryRevision())
                assertEquals(77L, db.momentDao().run("v1")!!.inputRevision)
                assertEquals(listOf(2L), db.momentDao().candidatePage(null, null, null, 10).map { it.media.mediaStoreId })
                assertEquals("Edited trip", db.momentDao().moment("saved")!!.title)
                assertEquals(2L, db.momentDao().summarySnapshot(10).single().coverMediaStoreId)
                db.openHelper.readableDatabase.query("SELECT mediaStoreId,isUserSelected FROM moment_covers").use {
                    assertTrue(it.moveToFirst()); assertEquals(1L, it.getLong(0)); assertEquals(1, it.getInt(1))
                }
                db.openHelper.readableDatabase.query("SELECT snapshotId FROM gallery_restore_receipts").use {
                    assertTrue(it.moveToFirst()); assertEquals("snapshot", it.getString(0))
                }
            }
            withDatabase(context, name) { db ->
                // Opening does not reinstall/write triggers; removing a classification does.
                assertEquals(78L, db.momentDiscoveryDao().discoveryRevision())
                db.documentDao().clear("external_primary", 1)
                assertEquals(79L, db.momentDiscoveryDao().discoveryRevision())
                val restored = db.momentDao().summarySnapshot(10).single()
                assertEquals(2L, restored.memberCount)
                assertEquals(1L, restored.coverMediaStoreId)
                assertEquals("Edited trip", restored.moment.title)
                assertTrue(restored.moment.isUserEdited)
            }
            withDatabase(context, name) { db ->
                assertEquals(79L, db.momentDiscoveryDao().discoveryRevision())
            }
        } finally { context.deleteDatabase(name) }
    }
}
