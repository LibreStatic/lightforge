package com.ugallery.feature.collections

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.data.GalleryDocumentRepository
import com.ugallery.core.database.DocumentAnnotationEntity
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.database.MediaItemEntity
import com.ugallery.core.designsystem.UGalleryTheme
import com.ugallery.core.model.MediaKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Documents hands selected identities to review; this entry does not create a memory itself. */
class DocumentsManualMomentDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectedReceiptOpensMemoryReviewWithoutChangingClassificationOrPersistingMemory(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        val repository = GalleryDocumentRepository(db)
        val key = MediaKey("external_primary", 991)
        val name = "Owned receipt for memory review"
        val media = MediaItemEntity(
            volumeName = key.volumeName, mediaStoreId = key.mediaStoreId,
            mediaType = 1, mimeType = "image/jpeg", displayName = name,
            sizeBytes = 100, width = 10, height = 10, durationMillis = 0,
            orientationDegrees = 0, dateTakenMillis = 1000, dateAddedSeconds = 1,
            dateModifiedSeconds = 1, timelineSortMillis = 1000,
            generationAdded = 1, generationModified = 1, bucketId = 1,
            bucketDisplayName = "Fixture", relativePath = "DCIM/Fixture/",
            isFavorite = false, isTrashed = false, isAccessible = true, lastSeenScanId = 1,
        )
        val annotation = DocumentAnnotationEntity(key.volumeName, key.mediaStoreId, "Receipt", 424242L)
        val visible = mutableStateOf(true)
        val delivered = mutableListOf<List<MediaKey>>()
        var otherCallbacks = 0
        fun memoryCount(): Long = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM moments").use {
            assertTrue(it.moveToFirst()); it.getLong(0)
        }
        try {
            db.libraryDao().upsertMedia(listOf(media))
            db.documentDao().put(annotation)
            val before = checkNotNull(db.documentDao().get(key.volumeName, key.mediaStoreId))
            assertEquals("Receipt", before.category)
            assertEquals(0L, memoryCount())
            compose.setContent {
                if (visible.value) UGalleryTheme {
                    DocumentsContent(repository = repository, thumbnailLoader = null,
                        onPdf = { otherCallbacks++ }, onPdfStudio = { otherCallbacks++ },
                        onBack = { otherCallbacks++ },
                        onCreateMemory = { delivered += it.toList() })
                }
            }
            compose.onNodeWithTag("documents-create-memory").assertDoesNotExist()
            compose.waitUntil(10_000) {
                runCatching {
                    compose.onNodeWithTag("documents-list")
                        .performScrollToNode(hasContentDescription(name))
                }.isSuccess
            }
            compose.onNodeWithContentDescription(name).assertIsOff().performClick().assertIsOn()
            compose.runOnIdle { assertTrue(delivered.isEmpty()); assertEquals(0, otherCallbacks) }
            compose.onNodeWithTag("documents-list")
                .performScrollToNode(hasTestTag("documents-create-memory"))
            compose.onNodeWithTag("documents-create-memory").assertIsDisplayed().assertIsEnabled().performClick()
            compose.runOnIdle {
                assertEquals(listOf(listOf(key)), delivered)
                assertEquals(0, otherCallbacks)
            }
            assertEquals(before, db.documentDao().get(key.volumeName, key.mediaStoreId))
            assertEquals(media, db.libraryDao().media(key.volumeName, key.mediaStoreId))
            db.openHelper.readableDatabase.query(
                "SELECT category,updatedAtMillis FROM document_annotations WHERE volumeName=? AND mediaStoreId=?",
                arrayOf<Any>(key.volumeName, key.mediaStoreId)).use {
                assertTrue(it.moveToFirst()); assertEquals(annotation.category, it.getString(0))
                assertEquals(annotation.updatedAtMillis, it.getLong(1))
            }
            assertEquals("Entry only opens review; no memory has been saved", 0L, memoryCount())
            compose.onNodeWithTag("documents-list").performScrollToNode(hasContentDescription(name))
            compose.onNodeWithContentDescription(name).performClick().assertIsOff()
            compose.onNodeWithTag("documents-create-memory").assertDoesNotExist()
            compose.runOnIdle { assertEquals(1, delivered.size); assertEquals(0, otherCallbacks) }
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            db.close()
        }
    }
}
