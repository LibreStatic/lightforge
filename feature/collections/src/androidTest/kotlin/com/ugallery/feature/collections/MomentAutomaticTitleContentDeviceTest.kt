package com.ugallery.feature.collections

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.database.*
import com.ugallery.core.designsystem.UGalleryTheme
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** One contract test: actual Room invalidation reaches the production moment header. */
class MomentAutomaticTitleContentDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun currentLocationFlowUpdatesHeaderWithoutChangingManualTitle(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        val timestamp = Instant.parse("2026-09-06T12:00:00Z").toEpochMilli()
        val moment = MomentEntity("title-contract", "heuristic", "ready", "moments-v1", timestamp,
            timestamp, null, "generated", false, timestamp, timestamp)
        val media = MediaItemEntity("external_primary", 1, 1, "image/jpeg", "photo.jpg", 100,
            10, 10, 0, 0, timestamp, 1, 1, timestamp, 1, 1, 99, "Fixture", "DCIM/Fixture/",
            false, false, true, 1)
        val exif = MediaExifEntity("external_primary", 1, 1, 0, null, null, null, null, null,
            null, null, null, null, -31.4, -64.2, true, timestamp)
        val allowed = MutableStateFlow(true)
        val labels: (String) -> Flow<String?> = { id ->
            combine(db.momentDao().observeLocation(id), allowed) { row, permission ->
                if (permission && row?.latitude == -31.4 && row.longitude == -64.2) "Córdoba" else null
            }
        }
        val date = momentAutomaticTitle(context, timestamp, timestamp, ZoneId.systemDefault(),
            context.resources.configuration.locales[0])
        val place = momentAutomaticTitle(context, timestamp, timestamp, ZoneId.systemDefault(),
            context.resources.configuration.locales[0], placeLabel = "Córdoba")
        fun header(expected: String) {
            compose.waitUntil(5000) { compose.onAllNodesWithText(expected).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText(expected).assertIsDisplayed()
        }
        try {
            db.libraryDao().upsertMedia(listOf(media))
            db.momentDao().upsertMoment(moment)
            db.momentDao().insertMembers(listOf(MomentMemberEntity(moment.momentId, 0,
                media.volumeName, media.mediaStoreId, 1, "heuristic", 1f)))
            db.libraryDao().upsertExif(exif)
            val moments = db.momentDao().observeMoment(moment.momentId)
            compose.setContent {
                val current by moments.collectAsState(initial = moment)
                UGalleryTheme {
                    MomentContent(current ?: moment, emptyList(), null, "", "", {}, {}, {}, {}, {}, {},
                        momentPlaceLabels = labels)
                }
            }
            header(place)
            db.libraryDao().deleteExif(media.volumeName, media.mediaStoreId)
            header(date)
            db.libraryDao().upsertExif(exif)
            header(place)
            db.libraryDao().upsertMedia(listOf(media.copy(isAccessible = false)))
            header(date)
            db.libraryDao().upsertMedia(listOf(media))
            header(place)
            allowed.value = false
            header(date)
            assertNull("Automatic display never writes a stored title", db.momentDao().moment(moment.momentId)!!.title)
            val manual = "Nuestro viaje — intacto"
            db.momentDao().upsertMoment(moment.copy(title = manual, titleMode = "manual", isUserEdited = true))
            header(manual)
            allowed.value = true
            db.libraryDao().deleteExif(media.volumeName, media.mediaStoreId)
            header(manual)
            assertEquals(manual, db.momentDao().moment(moment.momentId)!!.title)
        } finally { db.close() }
    }
}
