package com.ugallery.feature.collections

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.data.*
import com.ugallery.core.database.*
import com.ugallery.core.designsystem.UGalleryTheme
import com.ugallery.core.thumbnail.*
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SmartAlbumsContentDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val volume = "external_primary"

    /** Diagnostic evidence only: never replaces or retries the subsequent Compose capture. */
    private fun captureBeforePixelCopy(stem: String) {
        val directory = File(context.filesDir, "$stem-pre-pixelcopy-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        File(context.filesDir, "$stem-diagnostic-path.txt").writeText(directory.absolutePath)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        fun record(name: String, body: () -> String) {
            File(directory, name).writeText(
                runCatching(body).getOrElse { "DIAGNOSTIC ERROR: ${it.stackTraceToString()}" }
            )
        }
        record("semantics.txt") { compose.onRoot(useUnmergedTree = true).printToString() }
        record("geometry.txt") {
            listOf("smart-albums-screen", "smart-list", "smart-year", "smart-favorites-row").joinToString("\n") { tag ->
                "$tag: " + runCatching {
                    val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
                    "root=${node.boundsInRoot}; window=${node.boundsInWindow}; config=${node.config}"
                }.getOrElse { "unavailable: $it" }
            }
        }
        record("windows-focus-ime.txt") {
            buildString {
                appendLine("sdk=${android.os.Build.VERSION.SDK_INT}; uptime=${android.os.SystemClock.uptimeMillis()}")
                automation.windows.forEach { window ->
                    val bounds = android.graphics.Rect().also(window::getBoundsInScreen)
                    appendLine("window id=${window.id} type=${window.type} layer=${window.layer} active=${window.isActive} focused=${window.isFocused} bounds=$bounds ime=${window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD}")
                    val root = window.root
                    appendLine("inputFocus=${root?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)}")
                    appendLine("accessibilityFocus=${root?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)}")
                }
                instrumentation.runOnMainSync {
                    androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                        .forEach { activity ->
                            val decor = activity.window.decorView
                            appendLine("activity=${activity.javaClass.name}; windowFocus=${decor.hasWindowFocus()}; viewFocus=${decor.findFocus()}; shown=${decor.isShown}; size=${decor.width}x${decor.height}; flags=${activity.window.attributes.flags}; softInputMode=${activity.window.attributes.softInputMode}")
                            if (android.os.Build.VERSION.SDK_INT >= 30) {
                                appendLine("imeInsetsVisible=${decor.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())}; imeInsets=${decor.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.ime())}")
                            }
                        }
                }
            }
        }
        record("accessibility-tree.txt") {
            buildString {
                var remaining = 300
                fun visit(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int) {
                    if (remaining-- <= 0) return
                    val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                    appendLine("${"  ".repeat(depth)}id=${node.viewIdResourceName} class=${node.className} text=${node.text} description=${node.contentDescription} focused=${node.isFocused} accessibilityFocused=${node.isAccessibilityFocused} visible=${node.isVisibleToUser} bounds=$bounds")
                    for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it, depth + 1) }
                }
                automation.rootInActiveWindow?.let { visit(it, 0) } ?: appendLine("No active root")
                if (remaining <= 0) appendLine("Tree truncated at 300 nodes")
            }
        }
        record("uiautomation-capture.txt") {
            val bitmap = automation.takeScreenshot()
            if (bitmap == null) "UiAutomation.takeScreenshot returned null"
            else try {
                val written = File(directory, "uiautomation.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                "UiAutomation screenshot ${bitmap.width}x${bitmap.height}; PNG written=$written"
            } finally { bitmap.recycle() }
        }
    }

    private fun media(id: Long) =
        MediaItemEntity(
            volume,
            id,
            1,
            "image/jpeg",
            "fixture-$id.jpg",
            100,
            10,
            10,
            0,
            0,
            Instant.parse("2026-06-01T12:00:00Z").toEpochMilli(),
            1,
            1,
            Instant.parse("2026-06-01T12:00:00Z").toEpochMilli(),
            1,
            1,
            99,
            "Fixture",
            "DCIM/Fixture/",
            true,
            false,
            true,
            1,
        )

    private suspend fun seed(db: GalleryDatabase, id: Long) {
        db.libraryDao().upsertMedia(listOf(media(id)))
        db.libraryDao()
            .replaceLabelResult(
                MediaLabelRunEntity(volume, id, 1, "labels", 1),
                listOf(MediaLabelEntity(volume, id, "beach", "Beach", .9f, "labels")),
            )
        db.personDao()
            .upsertCluster(
                PersonClusterEntity(
                    "person",
                    "v1",
                    ByteArray(128),
                    2,
                    "Local person",
                    false,
                    true,
                    1,
                    1,
                )
            )
        db.libraryDao()
            .replaceFaceDetection(
                FaceDetectionRunEntity(volume, id, 1, "face", 1, 1),
                listOf(
                    DetectedFaceEntity(
                        volume,
                        id,
                        0,
                        "face",
                        100,
                        100,
                        500,
                        500,
                        50,
                        50,
                        550,
                        550,
                        0f,
                        0f,
                        0f,
                        .9f,
                        "[]",
                    )
                ),
            )
        db.libraryDao()
            .upsertFaceEmbeddings(
                listOf(FaceEmbeddingEntity(volume, id, 0, "face", "embedding", ByteArray(128), 1))
            )
        db.personDao()
            .upsertMembership(PersonMembershipEntity(volume, id, 0, "person", "v1", "user", .9f, 1))
    }

    private fun scroll(tag: String) {
        var failure: AssertionError? = null
        try {
            compose.waitUntil(10_000) {
                try {
                    val container =
                        if (compose.onAllNodesWithTag("smart-grid").fetchSemanticsNodes().isNotEmpty())
                            "smart-grid"
                        else "smart-list"
                    compose.onNodeWithTag(container).performScrollToNode(hasTestTag(tag))
                    compose.onNodeWithTag(tag).assertIsDisplayed()
                    true
                } catch (e: AssertionError) {
                    failure = e
                    false
                }
            }
        } catch (timeout: ComposeTimeoutException) {
            throw failure ?: timeout
        }
    }

    private fun click(tag: String) {
        scroll(tag)
        compose.onNodeWithTag(tag).assertIsEnabled().performClick()
    }

    private fun back() {
        compose.onNodeWithTag("smart-back").performClick()
    }

    private fun count(n: Int) {
        scroll("smart-count")
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("smart-count").fetchSemanticsNodes().any {
                it.config
                    .getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) {
                        emptyList()
                    }
                    .any { text -> text.text == "Matching photos: $n" }
            }
        }
    }

    @Test fun compactLightCompleteRuleFlow(): Unit = exercise(false, 360, 1f)

    @Test fun compactDarkLargeTextCompleteRuleFlow(): Unit = exercise(true, 360, 1.6f)

    @Test fun expandedDynamicCompleteRuleFlow(): Unit = exercise(false, 840, 1f)

    private fun exercise(dark: Boolean, width: Int, scale: Float): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        val repo = GallerySmartAlbumRepository(db)
        seed(db, 1)
        seed(db, 2)
        db.libraryDao().upsertMedia(listOf(media(3).copy(isFavorite = false)))
        val bitmaps = java.util.concurrent.ConcurrentHashMap<ThumbnailRequest, Bitmap>()
        val hashes = java.util.concurrent.ConcurrentHashMap<ThumbnailRequest, Int>()
        fun hash(b: Bitmap) =
            IntArray(b.width * b.height)
                .also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
                .contentHashCode()
        val loader =
            ThumbnailLoader(
                ThumbnailSource { r, _ ->
                    Bitmap.createBitmap(r.widthPx, r.heightPx, Bitmap.Config.ARGB_8888).also { b ->
                        b.eraseColor(
                            if (r.mediaKey.mediaStoreId == 1L) android.graphics.Color.BLUE
                            else android.graphics.Color.GREEN
                        )
                        bitmaps[r] = b
                        hashes[r] = hash(b)
                    }
                },
                16L * 1024 * 1024,
            )
        val ratios = java.util.concurrent.atomic.AtomicReference<List<Double>>(emptyList())
        try {
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    UGalleryTheme(darkTheme = dark) {
                        val c = MaterialTheme.colorScheme
                        SideEffect {
                            ratios.set(
                                listOf(
                                        c.onSurface to c.surface,
                                        c.onSurfaceVariant to c.surfaceVariant,
                                        c.onSecondaryContainer to c.secondaryContainer,
                                        c.onPrimary to c.primary,
                                    )
                                    .map { (fg, bg) ->
                                        (maxOf(fg.luminance(), bg.luminance()) + .05) /
                                            (minOf(fg.luminance(), bg.luminance()) + .05)
                                    }
                            )
                        }
                        SmartAlbumsContent(
                            repo,
                            loader,
                            {},
                            {},
                            Modifier.width(width.dp).height(720.dp),
                        )
                    }
                }
            }
            fun capture(label: String) {
                captureBeforePixelCopy("smart-$dark-$width-$scale-$label")
                java.io
                    .File(context.filesDir, "smart-$dark-$width-$scale-$label.png")
                    .outputStream()
                    .use {
                        compose
                            .onNodeWithTag("smart-albums-screen")
                            .captureToImage()
                            .asAndroidBitmap()
                            .compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                java.io
                    .File(context.filesDir, "smart-$dark-$width-$scale-$label.txt")
                    .writeText(compose.onRoot().printToString())
            }
            click("smart-create")
            scroll("smart-name")
            compose.onNodeWithTag("smart-name").performTextInput("Local trip")
            click("smart-topic")
            click("smart-topic-beach")
            click("smart-person")
            click("smart-person-person")
            scroll("smart-year")
            compose.onNodeWithTag("smart-year").performTextInput("2026")
            click("smart-favorites")
            val year = compose.onNodeWithTag("smart-year").getUnclippedBoundsInRoot()
            val favorites = compose.onNodeWithTag("smart-favorites-row").getUnclippedBoundsInRoot()
            val list = compose.onNodeWithTag("smart-list").getUnclippedBoundsInRoot()
            assertTrue(year.right - year.left >= list.right - list.left - 40.dp)
            assertTrue(favorites.top >= year.bottom)
            capture("editor")
            click("smart-preview")
            count(2)
            click("smart-exclude-$volume:1")
            count(1)
            click("smart-pending")
            click("smart-include-$volume:1")
            back()
            count(2)
            click("smart-exclude-$volume:1")
            back()
            click("smart-save")
            count(1)
            val source =
                db.smartAlbumDao()
                    .albums()
                    .load(androidx.paging.PagingSource.LoadParams.Refresh(null, 20, false))
                    as androidx.paging.PagingSource.LoadResult.Page
            val row = source.data.single()
            assertEquals("beach", row.topic)
            assertEquals("person", row.personClusterId)
            assertEquals(2026, row.year)
            assertTrue(row.favoritesOnly)
            seed(db, 4)
            count(2)
            scroll("smart-photo-$volume:2")
            compose.waitUntil(10000) {
                compose
                    .onAllNodesWithTag("smart-image-loaded-$volume:2")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            capture("matches")
            click("smart-exclude-$volume:2")
            count(1)
            click("smart-undo")
            count(2)
            click("smart-exclude-$volume:2")
            click("smart-manage-exclusions")
            click("smart-include-$volume:1")
            click("smart-include-$volume:2")
            back()
            count(3)
            click("smart-edit")
            scroll("smart-name")
            compose.onNodeWithTag("smart-name").performTextReplacement("Unsaved")
            back()
            compose.onNodeWithTag("smart-confirm").performClick()
            count(3)
            assertEquals("Local trip", db.smartAlbumDao().get(row.albumId)!!.name)
            click("smart-edit")
            val current = db.smartAlbumDao().get(row.albumId)!!
            repo.update(current.albumId, current.revision, "Remote edit", current.rule())
            compose.waitUntil(10000) {
                compose.onAllNodesWithTag("smart-reload").fetchSemanticsNodes().isNotEmpty()
            }
            scroll("smart-save")
            compose.onNodeWithTag("smart-save").assertIsNotEnabled()
            click("smart-reload")
            scroll("smart-name")
            compose.onNodeWithTag("smart-name").assertTextContains("Remote edit")
            scroll("smart-year")
            compose.onNodeWithTag("smart-year").performTextReplacement("2025")
            click("smart-preview")
            count(0)
            capture("empty")
            back()
            click("smart-save")
            count(0)
            click("smart-delete")
            compose.onNodeWithTag("smart-confirm").performClick()
            compose.waitUntil(10000) {
                compose.onAllNodesWithTag("smart-create").fetchSemanticsNodes().isNotEmpty()
            }
            assertNull(db.smartAlbumDao().get(row.albumId))
            assertEquals(4L, db.libraryDao().mediaCount())
            assertTrue(bitmaps.isNotEmpty())
            for ((r, b) in bitmaps) assertEquals(hashes[r], hash(b))
            assertTrue(ratios.get().all { it >= 4.5 })
            java.io
                .File(context.filesDir, "smart-$dark-$width-$scale-result.json")
                .writeText(
                    "{\"status\":\"PASS\",\"draft\":true,\"andRules\":true,\"liveArrival\":true,\"exclusionUndo\":true,\"staleReload\":true,\"sourceHashes\":true,\"contrast\":${ratios.get()}}"
                )
        } finally {
            db.close()
        }
    }

    @Test
    fun draftSurvivesSavedInstanceRestoreWithoutSavingAnAlbum(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        val repo = GallerySmartAlbumRepository(db)
        try {
            val restoration = StateRestorationTester(compose)
            restoration.setContent { UGalleryTheme { SmartAlbumsContent(repo, null, {}, {}) } }
            click("smart-create")
            scroll("smart-name")
            compose.onNodeWithTag("smart-name").performTextInput("Restored draft")
            scroll("smart-year")
            compose.onNodeWithTag("smart-year").performTextInput("2026")
            restoration.emulateSavedInstanceStateRestore()
            scroll("smart-name")
            compose.onNodeWithTag("smart-name").assertTextContains("Restored draft")
            scroll("smart-year")
            compose.onNodeWithTag("smart-year").assertTextContains("2026")
            val rows =
                db.smartAlbumDao()
                    .albums()
                    .load(androidx.paging.PagingSource.LoadParams.Refresh(null, 20, false))
                    as androidx.paging.PagingSource.LoadResult.Page
            assertTrue(rows.data.isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun spanishLargeTextKeepsYearAndFavoritesOnSeparateFullRows(): Unit = localizedEditor("es")

    @Test fun germanLargeTextKeepsYearAndFavoritesOnSeparateFullRows(): Unit = localizedEditor("de")

    private fun localizedEditor(language: String): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        val repo = GallerySmartAlbumRepository(db)
        val config =
            android.content.res.Configuration(context.resources.configuration).apply {
                setLocale(java.util.Locale.forLanguageTag(language))
            }
        val localized = context.createConfigurationContext(config)
        try {
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalContext provides localized,
                    androidx.compose.ui.platform.LocalConfiguration provides config,
                    LocalDensity provides Density(density.density, 1.6f),
                ) {
                    UGalleryTheme(darkTheme = language == "de") {
                        SmartAlbumsContent(
                            repo,
                            null,
                            {},
                            {},
                            Modifier.width(360.dp).height(720.dp),
                        )
                    }
                }
            }
            click("smart-create")
            scroll("smart-year")
            compose.onNodeWithTag("smart-year").performTextInput("2026")
            click("smart-favorites")
            compose
                .onNodeWithText(localized.getString(R.string.smart_favorites))
                .assertIsDisplayed()
            val year = compose.onNodeWithTag("smart-year").getUnclippedBoundsInRoot()
            val favorites = compose.onNodeWithTag("smart-favorites-row").getUnclippedBoundsInRoot()
            assertTrue(favorites.top >= year.bottom)
            captureBeforePixelCopy("smart-locale-$language")
            File(context.filesDir, "smart-locale-$language.png").outputStream().use {
                compose
                    .onNodeWithTag("smart-albums-screen")
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            File(context.filesDir, "smart-locale-$language.txt")
                .writeText(compose.onRoot().printToString())
        } finally {
            db.close()
        }
    }

    @Test
    fun sameFrameFieldEditsPreserveEveryValue(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            val repo = GallerySmartAlbumRepository(db)
            compose.setContent {
                UGalleryTheme {
                    SmartAlbumsContent(repo, null, {}, {}, Modifier.width(720.dp).height(900.dp))
                }
            }
            click("smart-create")
            scroll("smart-year")
            val year =
                compose
                    .onNodeWithTag("smart-year")
                    .fetchSemanticsNode()
                    .config[androidx.compose.ui.semantics.SemanticsActions.SetText]
                    .action!!
            scroll("smart-name")
            val name =
                compose
                    .onNodeWithTag("smart-name")
                    .fetchSemanticsNode()
                    .config[androidx.compose.ui.semantics.SemanticsActions.SetText]
                    .action!!
            // Both actions retain the same rendered draft; no recomposition may run between them.
            compose.runOnUiThread {
                assertTrue(name(androidx.compose.ui.text.AnnotatedString("Rapid input")))
                assertTrue(year(androidx.compose.ui.text.AnnotatedString("2026")))
            }
            scroll("smart-name")
            compose.onNodeWithTag("smart-name").assertTextContains("Rapid input")
            scroll("smart-year")
            compose.onNodeWithTag("smart-year").assertTextContains("2026")
        } finally {
            db.close()
        }
    }
}
