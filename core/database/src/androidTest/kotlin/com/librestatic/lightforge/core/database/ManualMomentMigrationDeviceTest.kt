package com.librestatic.lightforge.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ManualMomentMigrationDeviceTest {
    @Test fun real29To30PreservesEditedStoryAndDefaultsExceptionOff() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "manual-migration-${UUID.randomUUID()}.db"
        val helper = MigrationTestHelper(instrumentation, GalleryDatabase::class.java)
        try {
            helper.createDatabase(name, 29).use { db ->
                for (id in 1..2) db.execSQL("INSERT INTO media_items(volumeName,mediaStoreId,mediaType,mimeType,sizeBytes,width,height,durationMillis,orientationDegrees,dateAddedSeconds,dateModifiedSeconds,timelineSortMillis,generationAdded,generationModified,isFavorite,isTrashed,isAccessible,lastSeenScanId) VALUES('fixture',$id,1,'image/jpeg',100,10,10,0,0,1,1,1000,1,2,1,0,1,1)")
                db.execSQL("INSERT INTO moments VALUES('saved','AUTO','SAVED','v1',1,2,'Owned title','USER',1,10,20)")
                db.execSQL("INSERT INTO moment_members VALUES('saved',0,'fixture',2,2,'USER',1.0)")
                db.execSQL("INSERT INTO moment_members VALUES('saved',1,'fixture',1,2,'USER',1.0)")
                db.execSQL("INSERT INTO moment_covers VALUES('saved','fixture',1,1)")
                db.execSQL("INSERT INTO moment_participant_state VALUES('saved','MANUAL',3)")
            }
            helper.runMigrationsAndValidate(name, 30, true, GalleryDatabaseFactory.Migration29To30).close()
            val db = GalleryDatabaseFactory.open(context, name)
            try {
                val row = requireNotNull(db.momentDao().moment("saved"))
                assertFalse(row.includeSpecialMedia)
                assertEquals("Owned title", row.title)
                assertEquals("v1", row.algorithmVersion)
                assertTrue(row.isUserEdited)
                assertEquals(listOf(2L,1L), db.momentDao().members("saved").map { it.member.mediaStoreId })
                assertEquals(1L, db.momentDao().summarySnapshot(1).single().coverMediaStoreId)
                assertEquals(3L, db.momentParticipantsDao().state("saved")!!.revision)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
