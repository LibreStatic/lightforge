package com.librestatic.lightforge.core.ml

import android.content.Context
import android.provider.MediaStore
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.librestatic.lightforge.core.database.DetectedFaceEntity
import com.librestatic.lightforge.core.database.FaceDetectionRunEntity
import com.librestatic.lightforge.core.database.FaceEmbeddingEntity
import com.librestatic.lightforge.core.database.GalleryDatabase
import com.librestatic.lightforge.core.database.MediaItemEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FaceEmbeddingScaleDeviceTest {
    @Test fun compactStoreHandlesOneHundredThousandFacesInBoundedBatches() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "embedding-scale.db"
        context.deleteDatabase(name)
        val database = Room.databaseBuilder(context, GalleryDatabase::class.java, name).build()
        val dao = database.libraryDao()
        val started = SystemClock.elapsedRealtime()
        try {
            dao.upsertMedia((0 until MediaCount).map(::media))
            repeat(MediaCount) { mediaIndex ->
                val faces = (0 until FacesPerMedia).map { faceIndex -> face(mediaIndex, faceIndex) }
                dao.replaceFaceDetection(
                    FaceDetectionRunEntity(Volume, mediaIndex.toLong(), 1, DetectionVersion, FacesPerMedia, 1),
                    faces,
                )
            }
            val beforeEmbeddingBytes = databaseBytes(database)
            val vector = ByteArray(128) { index -> (index - 64).toByte() }
            repeat(MediaCount) { mediaIndex ->
                dao.upsertFaceEmbeddings(
                    (0 until FacesPerMedia).map { faceIndex ->
                        FaceEmbeddingEntity(
                            Volume, mediaIndex.toLong(), faceIndex, DetectionVersion,
                            EmbeddingVersion, vector.copyOf(), 2,
                        )
                    },
                )
            }
            val afterEmbeddingBytes = databaseBytes(database)
            val elapsed = SystemClock.elapsedRealtime() - started
            assertEquals(TotalFaces.toLong(), dao.faceEmbeddingCount())
            assertEquals(TotalFaces * 128L, dao.faceEmbeddingPayloadBytes())
            assertTrue("Embedding table/index delta=${afterEmbeddingBytes - beforeEmbeddingBytes}", afterEmbeddingBytes - beforeEmbeddingBytes < 40L * 1024 * 1024)
            assertTrue("100k compact store took ${elapsed}ms", elapsed < 120_000)
            File(context.getExternalFilesDir(null), "m4-face-embedding-100k.json").writeText(
                JSONObject()
                    .put("schemaVersion", 1)
                    .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                    .put("api", android.os.Build.VERSION.SDK_INT)
                    .put("faces", TotalFaces)
                    .put("payloadBytes", dao.faceEmbeddingPayloadBytes())
                    .put("databaseDeltaBytes", afterEmbeddingBytes - beforeEmbeddingBytes)
                    .put("elapsedMillis", elapsed)
                    .toString(2),
            )
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private fun databaseBytes(database: GalleryDatabase): Long {
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
        fun pragma(name: String): Long = database.openHelper.writableDatabase.query("PRAGMA $name").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
        return pragma("page_count") * pragma("page_size")
    }

    private fun media(index: Int) = MediaItemEntity(
        Volume, index.toLong(), MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE, "image/jpeg", "scale-$index.jpg",
        1, 1024, 1024, 0, 0, 1, 1, 1, index.toLong(), 1, 1,
        1, "Scale", "Pictures/Scale/", false, false, true, 1,
    )

    private fun face(mediaIndex: Int, faceIndex: Int) = DetectedFaceEntity(
        Volume, mediaIndex.toLong(), faceIndex, DetectionVersion,
        100, 100, 300, 300, 80, 80, 320, 320,
        0f, 0f, 0f, .9f, "[]",
    )

    private companion object {
        const val Volume = "external_primary"
        const val DetectionVersion = "detector-scale-v1"
        const val EmbeddingVersion = "embedding-scale-v1"
        const val MediaCount = 1_000
        const val FacesPerMedia = 100
        const val TotalFaces = MediaCount * FacesPerMedia
    }
}
