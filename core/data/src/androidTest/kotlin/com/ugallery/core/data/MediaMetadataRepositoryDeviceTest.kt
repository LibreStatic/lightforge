package com.ugallery.core.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.database.MediaItemEntity
import com.ugallery.core.model.ExifLoadResult
import com.ugallery.core.model.LocationAccessState
import com.ugallery.core.model.MediaKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MediaMetadataRepositoryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var database: GalleryDatabase
    private val published = mutableListOf<android.net.Uri>()

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
    }

    @After
    fun tearDown() {
        published.forEach { context.contentResolver.delete(it, null, null) }
        database.close()
    }

    @Test
    fun cheapFieldsAreImmediateAndExifIsLazyCachedAndLocationGated() = runBlocking {
        val uri = publishExifJpeg()
        val key = MediaKey(MediaStore.VOLUME_EXTERNAL_PRIMARY, ContentUris.parseId(uri))
        database.libraryDao().upsertMedia(listOf(entity(key, generation = 7)))
        val repository = MediaMetadataRepository(context.contentResolver, database)

        val cheap = requireNotNull(repository.cheapDetails(key))
        assertEquals("metadata.jpg", cheap.displayName)
        assertNull(database.libraryDao().exif(key.volumeName, key.mediaStoreId))

        val authorized = repository.exifDetails(key, allowUnredactedLocation = true)
            as ExifLoadResult.Ready
        assertFalse(authorized.fromCache)
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, authorized.details.orientation)
        assertEquals("2026:08:17 15:00:00", authorized.details.dateTimeOriginal)
        assertEquals("-03:00", authorized.details.offsetTimeOriginal)
        assertEquals("UGallery", authorized.details.make)
        assertEquals(LocationAccessState.Available, authorized.details.locationState)
        assertEquals(-34.6037, requireNotNull(authorized.details.location).latitude, 0.0001)

        val cachedButRedacted = repository.exifDetails(key, allowUnredactedLocation = false)
            as ExifLoadResult.Ready
        assertTrue(cachedButRedacted.fromCache)
        assertNull(cachedButRedacted.details.location)
        assertEquals(LocationAccessState.PermissionRequired, cachedButRedacted.details.locationState)

        assertEquals(1, repository.onLocationPermissionRevoked())
        val purged = requireNotNull(database.libraryDao().exif(key.volumeName, key.mediaStoreId))
        assertNull(purged.latitude)
        assertNull(purged.longitude)
        assertFalse(purged.locationReadWithPermission)
    }

    @Test
    fun corruptMetadataIsAnExplicitResult() = runBlocking {
        val uri = publish("corrupt-metadata.jpg", "not-a-jpeg".encodeToByteArray())
        val key = MediaKey(MediaStore.VOLUME_EXTERNAL_PRIMARY, ContentUris.parseId(uri))
        database.libraryDao().upsertMedia(listOf(entity(key, generation = 1)))

        assertEquals(
            ExifLoadResult.CorruptOrUnsupported,
            MediaMetadataRepository(context.contentResolver, database)
                .exifDetails(key, allowUnredactedLocation = false),
        )
    }

    @Test
    fun deletedMediaIsRemovedFromTheLocalIndexInsteadOfCrashingDetails() = runBlocking {
        val uri = publish("deleted-metadata.jpg", "gone".encodeToByteArray())
        val key = MediaKey(MediaStore.VOLUME_EXTERNAL_PRIMARY, ContentUris.parseId(uri))
        database.libraryDao().upsertMedia(listOf(entity(key, generation = 1)))
        context.contentResolver.delete(uri, null, null)
        published.remove(uri)

        assertEquals(
            ExifLoadResult.MediaUnavailable,
            MediaMetadataRepository(context.contentResolver, database)
                .exifDetails(key, allowUnredactedLocation = false),
        )
        assertNull(database.libraryDao().media(key.volumeName, key.mediaStoreId))
    }

    private fun publishExifJpeg(): android.net.Uri {
        val file = File(context.cacheDir, "metadata-source.jpg")
        Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888).also { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bitmap.recycle()
        }
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:08:17 15:00:00")
            setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "-03:00")
            setAttribute(ExifInterface.TAG_MAKE, "UGallery")
            setAttribute(ExifInterface.TAG_MODEL, "Test Camera")
            setLatLong(-34.6037, -58.3816)
            saveAttributes()
        }
        return publish("metadata.jpg", file.readBytes()).also { file.delete() }
    }

    private fun publish(name: String, bytes: ByteArray): android.net.Uri {
        val resolver = context.contentResolver
        val uri = checkNotNull(
            resolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/UGalleryMetadataTest")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                },
            ),
        )
        resolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        published += uri
        return uri
    }

    private fun entity(key: MediaKey, generation: Long) = MediaItemEntity(
        volumeName = key.volumeName, mediaStoreId = key.mediaStoreId, mediaType = 1,
        mimeType = "image/jpeg", displayName = "metadata.jpg", sizeBytes = 100,
        width = 16, height = 8, durationMillis = 0, orientationDegrees = 90,
        dateTakenMillis = 1_000, dateAddedSeconds = 1, dateModifiedSeconds = 1,
        timelineSortMillis = 1_000, generationAdded = 1, generationModified = generation,
        bucketId = 1, bucketDisplayName = "Test", relativePath = "Pictures/UGalleryMetadataTest/",
        isFavorite = false, isTrashed = false, isAccessible = true, lastSeenScanId = 1,
    )
}
