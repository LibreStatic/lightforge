package com.ugallery.core.database

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/** One bounded real-Room flow fixture; no device location permission or real library changes. */
class MomentLocationDeviceTest {
    @Test
    fun locationTracksCurrentAuthorizedVisibleMemberAndNeverLeaksOtherMemory() =
        runBlocking<Unit> {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
            val volume = "memory-location:fixture"
            fun media(id: Long) =
                MediaItemEntity(
                    volumeName = volume,
                    mediaStoreId = id,
                    mediaType = 1,
                    mimeType = "image/jpeg",
                    displayName = "IMG_$id.jpg",
                    sizeBytes = 1000,
                    width = 4000,
                    height = 3000,
                    durationMillis = 0,
                    orientationDegrees = 0,
                    dateTakenMillis = id * 1000,
                    dateAddedSeconds = id,
                    dateModifiedSeconds = id,
                    timelineSortMillis = id * 1000,
                    generationAdded = 1,
                    generationModified = 2,
                    bucketId = 1,
                    bucketDisplayName = "Camera",
                    relativePath = "DCIM/Camera/",
                    isFavorite = false,
                    isTrashed = false,
                    isAccessible = true,
                    lastSeenScanId = 1,
                )
            fun exif(
                id: Long,
                latitude: Double?,
                longitude: Double?,
                generation: Long = 2,
                authorized: Boolean = true,
            ) =
                MediaExifEntity(
                    volumeName = volume,
                    mediaStoreId = id,
                    generationModified = generation,
                    orientation = 1,
                    dateTimeOriginal = null,
                    offsetTimeOriginal = null,
                    make = null,
                    model = null,
                    lensModel = null,
                    focalLength = null,
                    aperture = null,
                    exposureTime = null,
                    iso = null,
                    latitude = latitude,
                    longitude = longitude,
                    locationReadWithPermission = authorized,
                    cachedAtMillis = 100,
                )
            val events = Channel<Pair<Double, Double>?>(Channel.UNLIMITED)
            try {
                db.libraryDao().upsertMedia((1L..3L).map(::media))
                listOf("selected", "unrelated").forEach { id ->
                    db.momentDao()
                        .upsertMoment(
                            MomentEntity(
                                id,
                                "MANUAL",
                                "SAVED",
                                "fixture",
                                0,
                                4000,
                                id,
                                "USER",
                                true,
                                1,
                                1,
                            )
                        )
                }
                db.momentDao()
                    .insertMembers(
                        listOf(
                            MomentMemberEntity("selected", 0, volume, 1, 2, "USER", 1f),
                            MomentMemberEntity("selected", 1, volume, 2, 2, "USER", 1f),
                            MomentMemberEntity("unrelated", 0, volume, 3, 2, "USER", 1f),
                        )
                    )
                db.libraryDao().upsertExif(exif(1, 10.0, 20.0, generation = 1))
                db.libraryDao().upsertExif(exif(2, 30.0, 40.0, authorized = false))
                db.libraryDao().upsertExif(exif(3, 50.0, 60.0))
                val watcher = launch {
                    db.momentDao()
                        .observeLocation("selected")
                        .map { it?.let { it.latitude to it.longitude } }
                        .collect { events.send(it) }
                }
                suspend fun expect(expected: Pair<Double, Double>?) =
                    withTimeout(10000) {
                        var actual = events.receive()
                        while (actual != expected) actual = events.receive()
                        assertEquals(expected, actual)
                        val current = db.momentDao().observeLocation("selected").first()
                        assertEquals(expected, current?.let { it.latitude to it.longitude })
                    }
                try {
                    expect(null) // stale and unauthorized EXIF cannot borrow unrelated memory GPS
                    db.libraryDao().upsertExif(exif(2, 30.0, 40.0))
                    expect(30.0 to 40.0)
                    db.libraryDao().upsertExif(exif(1, 10.0, 20.0))
                    expect(10.0 to 20.0)
                    db.documentDao().put(DocumentAnnotationEntity(volume, 1, "Receipt", 1))
                    expect(30.0 to 40.0)
                    db.libraryDao().upsertArchived(ArchivedMediaEntity(volume, 2, 1))
                    expect(null)
                    db.documentDao().clear(volume, 1)
                    expect(10.0 to 20.0)
                    db.libraryDao().upsertArchived(ArchivedMediaEntity(volume, 1, 1))
                    expect(null)
                    db.libraryDao().deleteArchived(volume, 2)
                    expect(30.0 to 40.0)
                    db.libraryDao().deleteArchived(volume, 1)
                    expect(10.0 to 20.0)
                    db.libraryDao().upsertExif(exif(1, 10.0, 20.0, authorized = false))
                    expect(30.0 to 40.0)
                    db.libraryDao().upsertExif(exif(2, 91.0, 40.0))
                    expect(null)
                    db.libraryDao().upsertExif(exif(2, 30.0, 181.0))
                    expect(null)
                    db.libraryDao().upsertExif(exif(2, null, 40.0))
                    expect(null)
                    db.libraryDao().upsertExif(exif(1, 10.0, 20.0))
                    expect(10.0 to 20.0)
                    db.memoryExclusionDao()
                        .insertDate(
                            MemoryDateExclusionEntity("own-date", 0, 1, "UTC", 1000, 2000, 1)
                        )
                    expect(null)
                    db.memoryExclusionDao().removeDate("own-date")
                    expect(10.0 to 20.0)
                    db.libraryDao().upsertMedia(listOf(media(1).copy(isAccessible = false)))
                    expect(null)
                    db.libraryDao().upsertMedia(listOf(media(1)))
                    expect(10.0 to 20.0)
                } finally {
                    watcher.cancel()
                }
            } finally {
                events.close()
                db.close()
            }
        }
}
