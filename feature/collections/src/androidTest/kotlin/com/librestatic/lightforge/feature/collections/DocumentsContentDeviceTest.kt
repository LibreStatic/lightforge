package com.librestatic.lightforge.feature.collections

import android.content.ClipboardManager
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.data.*
import com.librestatic.lightforge.core.database.*
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.model.MediaKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DocumentsContentDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun text(id: Int) = context.getString(id)

    @Test fun compactLightClassifiesCopiesArchivesUndoesAndCreatesPdf() = exercise(false, 360, 1f)

    @Test fun compactDarkLargeTextKeepsActionsReachable() = exercise(true, 360, 1.6f)

    @Test fun wideLayoutShowsFiltersAndDocumentGridTogether(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        val repo = GalleryDocumentRepository(db)
        val key = MediaKey("external_primary", 992)
        val media = MediaItemEntity(
            key.volumeName, key.mediaStoreId, 1, "image/jpeg", "Wide grid fixture", 100, 10, 10, 0, 0, 1000,
            1, 1, 1000, 1, 1, 1, "Fixture", "DCIM/Fixture/", false, false, true, 1,
        )
        db.libraryDao().upsertMedia(listOf(media))
        db.libraryDao().upsertOcr(MediaOcrEntity(key.volumeName, key.mediaStoreId, 1, "fixture", "TOTAL 7", "total 7", "[]", 1))
        try {
            compose.setContent {
                val configuration = android.content.res.Configuration(androidx.compose.ui.platform.LocalConfiguration.current)
                    .apply { screenWidthDp = 1000 }
                CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalConfiguration provides configuration,
                    LocalDensity provides Density(1f, 1f),
                ) {
                    LightforgeTheme(darkTheme = false) {
                        DocumentsContent(repo, null, onPdf = {}, onPdfStudio = {}, onBack = {}, modifier = Modifier.width(1000.dp).height(600.dp))
                    }
                }
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Wide grid fixture").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(text(R.string.documents_studio)).assertIsDisplayed()
            compose.onNodeWithText("Wide grid fixture").assertIsDisplayed()
        } finally {
            db.close()
        }
    }

    private fun exercise(dark: Boolean, width: Int, scale: Float): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        val repo = GalleryDocumentRepository(db)
        val key = MediaKey("external_primary", 991)
        val media =
            MediaItemEntity(
                key.volumeName,
                key.mediaStoreId,
                1,
                "image/jpeg",
                "Local receipt fixture",
                100,
                10,
                10,
                0,
                0,
                1000,
                1,
                1,
                1000,
                1,
                1,
                1,
                "Fixture",
                "DCIM/Fixture/",
                false,
                false,
                true,
                1,
            )
        db.libraryDao().upsertMedia(listOf(media))
        db.libraryDao()
            .upsertOcr(
                MediaOcrEntity(
                    key.volumeName,
                    key.mediaStoreId,
                    1,
                    "fixture",
                    "TOTAL 42",
                    "total 42",
                    "[]",
                    1,
                )
            )
        var pdfKeys: List<MediaKey>? = null
        val visible = androidx.compose.runtime.mutableStateOf(true)
        val contrasts = java.util.concurrent.atomic.AtomicReference<List<Double>>(emptyList())
        try {
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    if (visible.value)
                        LightforgeTheme(darkTheme = dark) {
                            val colors = androidx.compose.material3.MaterialTheme.colorScheme
                            val ratios =
                                listOf(
                                        colors.onSurface to colors.surface,
                                        colors.onSurfaceVariant to colors.surfaceVariant,
                                        colors.onPrimary to colors.primary,
                                        colors.onSecondaryContainer to colors.secondaryContainer,
                                    )
                                    .map { (fg, bg) ->
                                        (maxOf(fg.luminance(), bg.luminance()) + 0.05) /
                                            (minOf(fg.luminance(), bg.luminance()) + 0.05)
                                    }
                            androidx.compose.runtime.SideEffect { contrasts.set(ratios) }

                            DocumentsContent(
                                repo,
                                null,
                                onPdf = { pdfKeys = it },
                                onPdfStudio = {},
                                onBack = {},
                                modifier = Modifier.width(width.dp).height(640.dp),
                            )
                        }
                }
            }
            compose.waitUntil(10_000) {
                runCatching {
                        compose
                            .onNodeWithTag("documents-list")
                            .performScrollToNode(hasText("Local receipt fixture"))
                    }
                    .isSuccess
            }
            assertEquals(4, contrasts.get().size)
            assertTrue(contrasts.get().all { it >= 4.5 })
            android.util.Log.i("DocumentsUi", "contrast dark=$dark ratios=${contrasts.get()}")
            compose.onNodeWithText("Local receipt fixture").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("TOTAL 42").fetchSemanticsNodes().isNotEmpty()
            }
            compose
                .onNodeWithText(text(R.string.documents_receipts))
                .performScrollTo()
                .performClick()
            compose.waitUntil(10_000) {
                runBlocking {
                    db.documentDao().get(key.volumeName, key.mediaStoreId)?.category == "Receipt"
                }
            }
            compose.onNodeWithText(text(R.string.documents_copy)).performScrollTo().performClick()
            compose.runOnIdle {
                assertEquals(
                    "TOTAL 42",
                    context
                        .getSystemService(ClipboardManager::class.java)
                        .primaryClip!!
                        .getItemAt(0)
                        .text
                        .toString(),
                )
            }
            compose.waitUntil(10_000) {
                compose
                    .onAllNodesWithText(text(R.string.documents_copied))
                    .fetchSemanticsNodes()
                    .isEmpty()
            }
            compose
                .onNodeWithText(text(R.string.documents_archive))
                .performScrollTo()
                .assertIsEnabled()
                .performClick()
            compose.waitUntil(10_000) {
                runBlocking {
                    db.documentDao().get(key.volumeName, key.mediaStoreId)?.archived == true
                }
            }
            compose.waitUntil(10_000) {
                compose
                    .onAllNodesWithText(text(R.string.documents_undo))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText(text(R.string.documents_undo)).performClick()
            compose.waitUntil(10_000) {
                runBlocking {
                    db.documentDao().get(key.volumeName, key.mediaStoreId)?.archived == false
                }
            }
            val pdfLabel = context.getString(R.string.documents_prepare_pdf, 1)
            val action = compose.onNodeWithText(pdfLabel).performScrollTo()
            action.assertIsDisplayed().assertHasClickAction()
            val button = action.getUnclippedBoundsInRoot()
            val screen = compose.onNodeWithTag("documents-screen").getUnclippedBoundsInRoot()
            assertTrue(button.left >= screen.left && button.right <= screen.right)
            action.performClick()
            compose.waitUntil(10_000) { pdfKeys != null }
            assertEquals(listOf(key), pdfKeys)
            assertEquals(media, db.documentDao().get(key.volumeName, key.mediaStoreId)!!.media)
            android.util.Log.i(
                "DocumentsUi",
                "PASS dark=$dark fontScale=$scale width=$width classify/copy/archive/undo/pdf; original row preserved",
            )
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            db.close()
        }
    }
}
