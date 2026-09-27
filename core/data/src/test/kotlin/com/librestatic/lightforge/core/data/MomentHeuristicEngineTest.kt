package com.librestatic.lightforge.core.data

import com.librestatic.lightforge.core.database.MediaItemEntity
import com.librestatic.lightforge.core.database.MomentCandidateRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MomentHeuristicEngineTest {
    @Test
    fun `six hour gap splits without location`() {
        val accumulator = MomentAccumulator("test")
        accumulator.offer(row(1, 0))
        val closed = accumulator.offer(row(2, MomentAccumulator.DefaultMaximumGapMillis + 1))
        assertNotNull(closed)
        assertEquals(1, closed?.itemCount)
    }

    @Test
    fun `nearby location permits gap up to twenty four hours`() {
        val accumulator = MomentAccumulator("test")
        accumulator.offer(row(1, 0, -34.6037, -58.3816))
        assertNull(accumulator.offer(row(2, 12 * HOUR, -34.604, -58.382)))
        assertNotNull(accumulator.offer(row(3, 37 * HOUR, -34.604, -58.382)))
    }

    @Test
    fun `screenshot candidates are excluded but still count toward event`() {
        val accumulator = MomentAccumulator("test")
        repeat(6) { accumulator.offer(row(it.toLong(), it * 1_000L, name = "Screenshot_$it.png")) }
        val event = accumulator.finish()
        assertEquals(6, event?.itemCount)
        assertTrue(event?.candidates.orEmpty().isEmpty())
    }

    @Test
    fun `ordinary place names containing scan remain memory candidates in every source field`() {
        for (place in listOf("Scandinavia", "Tuscany", "Scandinavian coast")) {
            for (field in listOf("name", "bucket", "path")) {
                val accumulator = MomentAccumulator("test")
                repeat(6) { index ->
                    accumulator.offer(row(index.toLong(), index * 1_000L,
                        name = if (field == "name") "${place}_$index.jpg" else "IMG_$index.jpg",
                        bucket = if (field == "bucket") place else "Camera",
                        path = if (field == "path") "Pictures/$place/" else "DCIM/Camera/"))
                }
                val event = checkNotNull(accumulator.finish())
                assertEquals("$field: $place", 6, event.candidates.size)
                assertEquals("$field: $place", 6, MomentAccumulator.selectMembers(event).size)
            }
        }
    }

    @Test
    fun `delimited screenshot and scan names remain excluded in every source field`() {
        val names = listOf("Screenshots", "Screen shots", "Screencapture", "Screen captures",
            "Screenshot_20260906", "screen-shot-123", "SCREEN_CAPTURE_123", "Scan-123",
            "Scan123", "Scans", "Scans_2026")
        for (name in names) {
            for (field in listOf("name", "bucket", "path")) {
                val accumulator = MomentAccumulator("test")
                repeat(6) { index ->
                    accumulator.offer(row(index.toLong(), index * 1_000L,
                        name = if (field == "name") "$name-$index.png" else "IMG_$index.jpg",
                        bucket = if (field == "bucket") name else "Camera",
                        path = if (field == "path") "Pictures/$name/" else "DCIM/Camera/"))
                }
                val event = checkNotNull(accumulator.finish())
                assertEquals("Excluded inputs still count toward their event", 6, event.itemCount)
                assertTrue("$field: $name", event.candidates.isEmpty())
                assertTrue("$field: $name", MomentAccumulator.selectMembers(event).isEmpty())
            }
        }
    }

    @Test
    fun `staging and final members stay bounded for large events`() {
        val accumulator = MomentAccumulator("test")
        repeat(4_999) { index ->
            accumulator.offer(row(index.toLong(), index * 1_000L, width = 1000 + index % 100))
        }
        val event = checkNotNull(accumulator.finish())
        assertTrue(event.candidates.size <= MomentAccumulator.MaximumStagingCandidates)
        assertTrue(
            MomentAccumulator.selectMembers(event).size <= MomentAccumulator.MaximumMomentMembers
        )
    }

    @Test
    fun `stable id is fixed width lowercase hexadecimal`() {
        val accumulator = MomentAccumulator("test")
        repeat(6) { accumulator.offer(row(it.toLong(), it * 1_000L)) }
        val event = checkNotNull(accumulator.finish())
        val members = MomentAccumulator.selectMembers(event)
        val id = MomentAccumulator.stableMomentId(event, members)
        assertEquals(24, id.length)
        assertTrue(id.matches(Regex("[0-9a-f]{24}")))
    }

    @Test
    fun `restored checkpoint preserves location split and chosen members`() {
        val original = MomentAccumulator("test")
        repeat(8) { original.offer(row(it.toLong(), it * 1000L, -34.6037, -58.3816)) }
        val resumed =
            MomentAccumulator(
                "test",
                original.startMillis,
                original.endMillis,
                original.lastMillis,
                original.itemCount,
                original.staged(),
                original.lastLatitude,
                original.lastLongitude,
            )
        val next = row(100, 12 * HOUR, -34.604, -58.382)
        assertNull(original.offer(next))
        assertNull(resumed.offer(next))
        assertEquals(original.finish(), resumed.finish())
    }

    private fun row(
        id: Long,
        time: Long,
        latitude: Double? = null,
        longitude: Double? = null,
        name: String = "IMG_$id.jpg",
        width: Int = 4_000,
        bucket: String = "Camera",
        path: String = "DCIM/Camera/",
    ) =
        MomentCandidateRow(
            media =
                MediaItemEntity(
                    volumeName = "external_primary",
                    mediaStoreId = id,
                    mediaType = 1,
                    mimeType = "image/jpeg",
                    displayName = name,
                    sizeBytes = 1_000,
                    width = width,
                    height = 3_000,
                    durationMillis = 0,
                    orientationDegrees = 0,
                    dateTakenMillis = time,
                    dateAddedSeconds = time / 1_000,
                    dateModifiedSeconds = time / 1_000,
                    timelineSortMillis = time,
                    generationAdded = 1,
                    generationModified = 1,
                    bucketId = 1,
                    bucketDisplayName = bucket,
                    relativePath = path,
                    isFavorite = false,
                    isTrashed = false,
                    isAccessible = true,
                    lastSeenScanId = 1,
                ),
            latitude = latitude,
            longitude = longitude,
            blurScore = .7f,
            pHash = id shl 48,
            similarityClusterId = "cluster-$id",
        )

    private companion object {
        const val HOUR = 60L * 60 * 1_000
    }
}
