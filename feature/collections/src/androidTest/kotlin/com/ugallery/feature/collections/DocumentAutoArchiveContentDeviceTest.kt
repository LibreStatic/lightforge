package com.ugallery.feature.collections

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.data.*
import com.ugallery.core.database.*
import com.ugallery.core.designsystem.UGalleryTheme
import com.ugallery.core.model.MediaKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DocumentAutoArchiveContentDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun media(id: Long, volume: String = "external_primary") =
        MediaItemEntity(
            volume,
            id,
            1,
            "image/jpeg",
            "photo-$id.jpg",
            100,
            10,
            10,
            0,
            0,
            id * 1000,
            id,
            id,
            id * 1000,
            1,
            1,
            99,
            "Fixture",
            "DCIM/Fixture/",
            false,
            false,
            true,
            1,
        )

    @Test fun lightPreviewKeepConfirmAndDurableUndo(): Unit = exercise(false, 1f)

    @Test fun darkLargeTextPreviewKeepConfirmAndDurableUndo(): Unit = exercise(true, 1.6f)

    private fun exercise(dark: Boolean, scale: Float): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        val repo = GalleryDocumentRepository(db)
        val visible = mutableStateOf(true)
        val ratios = java.util.concurrent.atomic.AtomicReference<List<Double>>(emptyList())
        db.libraryDao().upsertMedia(listOf(media(1), media(2)))
        repo.classify(
            listOf(MediaKey("external_primary", 1), MediaKey("external_primary", 2)),
            DocumentCategory.Receipt,
        )
        try {
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    UGalleryTheme(darkTheme = dark) {
                        val colors = androidx.compose.material3.MaterialTheme.colorScheme
                        SideEffect {
                            ratios.set(
                                listOf(
                                        colors.onSurface to colors.surface,
                                        colors.onSecondaryContainer to colors.secondaryContainer,
                                        colors.onPrimary to colors.primary,
                                    )
                                    .map { (fg, bg) ->
                                        (maxOf(fg.luminance(), bg.luminance()) + 0.05) /
                                            (minOf(fg.luminance(), bg.luminance()) + 0.05)
                                    }
                            )
                        }
                        if (visible.value)
                            DocumentsContent(
                                repo,
                                null,
                                {},
                                {},
                                {},
                                Modifier.width(360.dp).height(640.dp),
                            )
                    }
                }
            }
            fun capture(label: String) {
                val bitmap =
                    compose.onNodeWithTag("document-auto-screen").captureToImage().asAndroidBitmap()
                java.io
                    .File(context.filesDir, "document-auto-$dark-$scale-$label.png")
                    .outputStream()
                    .use {
                        check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                    }
                java.io
                    .File(context.filesDir, "document-auto-$dark-$scale-$label.txt")
                    .writeText(compose.onRoot().printToString())
            }
            fun scrollTag(list: String, tag: String) {
                compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag))
                compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().performClick()
            }
            compose.waitUntil(10000) {
                compose.onAllNodesWithTag("documents-list").fetchSemanticsNodes().isNotEmpty()
            }
            scrollTag("documents-list", "documents-auto-archive")
            compose.waitUntil(10000) {
                compose.onAllNodesWithTag("document-auto-list").fetchSemanticsNodes().isNotEmpty()
            }
            for (label in listOf(context.getString(R.string.documents_all), context.getString(R.string.document_auto_any_age))) {
                compose.onNodeWithTag("document-auto-list").performScrollToNode(hasText(label))
                compose.onNodeWithText(label).performClick()
            }
            capture("settings")
            scrollTag("document-auto-list", "document-auto-review")
            val proposed = context.getString(R.string.document_auto_preview_rule, context.getString(R.string.documents_all), 0)
            compose.onNodeWithTag("document-auto-list").performScrollToNode(hasText(proposed))
            compose.onNodeWithText(proposed).assertIsDisplayed()
            val keep = context.getString(R.string.document_auto_keep_named, "photo-1.jpg")
            compose
                .onNodeWithTag("document-auto-list")
                .performScrollToNode(hasContentDescription(keep))
            compose.onNodeWithContentDescription(keep).performClick().assertIsOn()
            capture("preview")
            val source = db.documentDao().get("external_primary", 1)!!.media
            assertNull(db.documentDao().archivedAt("external_primary", 2))
            scrollTag("document-auto-list", "document-auto-confirm")
            compose.waitUntil(10000) {
                runBlocking { db.documentArchiveDao().rule()?.enabled == true }
            }
            assertNull(db.documentDao().archivedAt("external_primary", 1))
            assertNotNull(db.documentDao().archivedAt("external_primary", 2))
            assertEquals("All", db.documentArchiveDao().rule()!!.category)
            assertEquals(0, db.documentArchiveDao().rule()!!.minimumAgeDays)
            // Destroy and recreate the content: the enabled rule and undo come from Room, not a
            // snackbar token.
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            compose.runOnIdle { visible.value = true }
            compose.waitForIdle()
            scrollTag("documents-list", "documents-auto-archive")
            compose.waitUntil(10000) {
                compose.onAllNodesWithTag("document-auto-list").fetchSemanticsNodes().isNotEmpty()
            }
            capture("enabled")
            scrollTag("document-auto-list", "document-auto-undo")
            compose.waitUntil(10000) {
                runBlocking { db.documentArchiveDao().rule()?.enabled == false }
            }
            assertNull(db.documentDao().archivedAt("external_primary", 2))
            assertEquals(source, db.documentDao().get("external_primary", 1)!!.media)
            assertTrue(ratios.get().all { it >= 4.5 })
            android.util.Log.i(
                "DocumentAutoArchiveUI",
                "PASS dark=$dark scale=$scale contrast=${ratios.get()}",
            )
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            db.close()
        }
    }
}
