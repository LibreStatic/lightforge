package com.librestatic.lightforge.core.database

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalysisProgressDeviceTest {
    private fun media(db: SupportSQLiteDatabase, id: Long, type: Int = 1, trashed: Int = 0, accessible: Int = 1, generation: Long = 1) {
        db.execSQL(
            "INSERT INTO media_items(volumeName,mediaStoreId,mediaType,sizeBytes,width,height,durationMillis," +
                "orientationDegrees,dateAddedSeconds,dateModifiedSeconds,timelineSortMillis,generationAdded," +
                "generationModified,bucketId,bucketDisplayName,relativePath,isFavorite,isTrashed,isAccessible,lastSeenScanId) " +
                "VALUES('external_primary',?,?,1,1,1,0,0,1,1,?,1,?,1,'a','DCIM/',0,?,?,1)",
            arrayOf<Any?>(id, type, id, generation, trashed, accessible),
        )
    }

    private fun faceRun(db: SupportSQLiteDatabase, id: Long, generation: Long = 1, version: String = "v1", at: Long = 0) {
        db.execSQL(
            "INSERT INTO face_detection_runs(volumeName,mediaStoreId,generationModified,modelVersion,acceptedFaceCount,completedAtMillis) " +
                "VALUES('external_primary',?,?,?,0,?)",
            arrayOf<Any?>(id, generation, version, at),
        )
    }

    private fun labelRun(db: SupportSQLiteDatabase, id: Long) {
        db.execSQL(
            "INSERT INTO media_label_runs(volumeName,mediaStoreId,generationModified,modelVersion,completedAtMillis) " +
                "VALUES('external_primary',?,1,'v1',500)",
            arrayOf<Any?>(id),
        )
    }

    @Test
    fun countsOnlyCurrentRunsOfEligiblePhotos() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            GalleryDatabase::class.java,
        ).build()
        try {
            val db = database.openHelper.writableDatabase
            media(db, 1)
            media(db, 2)
            media(db, 3)
            media(db, 4, type = 3)
            media(db, 5, trashed = 1)
            media(db, 6, accessible = 0)
            media(db, 7, generation = 2)
            faceRun(db, 1, at = 100)
            faceRun(db, 2, version = "old", at = 200)
            faceRun(db, 4, at = 300)
            faceRun(db, 5, at = 400)
            faceRun(db, 7, generation = 1, at = 500)
            labelRun(db, 1)
            labelRun(db, 3)
            labelRun(db, 6)
            val dao = database.libraryDao()
            assertEquals(4L, dao.observeAnalysisEligiblePhotoCount().first())
            assertEquals(1L, dao.observeFaceDetectionProcessedCount("v1").first())
            assertEquals(2L, dao.observeLabelProcessedCount("v1").first())
            assertEquals(listOf(300L, 400L, 500L), dao.faceDetectionCompletionTimes("v1", 250).sorted())
            assertEquals(listOf(500L, 500L, 500L), dao.labelCompletionTimes("v1", 250))
        } finally {
            database.close()
        }
    }
}
