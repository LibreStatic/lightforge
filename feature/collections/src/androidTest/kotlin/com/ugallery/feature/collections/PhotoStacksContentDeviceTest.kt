package com.ugallery.feature.collections

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
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
import com.ugallery.core.thumbnail.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PhotoStacksContentDeviceTest {
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

    @Test fun compactLightCoverCompareAndSeparate(): Unit = exercise(false, 360, 1f)

    @Test fun compactDarkLargeTextCoverCompareAndSeparate(): Unit = exercise(true, 360, 1.6f)

    @Test fun expandedComparisonUsesTheSameOriginalFrames(): Unit = exercise(false, 800, 1f)

    private fun exercise(dark: Boolean, width: Int, scale: Float): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        val repo = GalleryPhotoStackRepository(db)
        db.libraryDao().upsertMedia((1L..3L).map(::media))
        val id = repo.create((1L..3L).map { MediaKey("external_primary", it) })
        val visible = mutableStateOf(true)
        val bitmaps = java.util.concurrent.ConcurrentHashMap<ThumbnailRequest, Bitmap>()
        val hashes = java.util.concurrent.ConcurrentHashMap<ThumbnailRequest, Long>()
        fun hash(bitmap: Bitmap): Long {
            val buffer = java.nio.ByteBuffer.allocate(bitmap.byteCount)
            bitmap.copyPixelsToBuffer(buffer)
            return java.util.zip.CRC32().apply { update(buffer.array()) }.value
        }
        val loader =
            ThumbnailLoader(
                ThumbnailSource { request, _ ->
                    val bitmap =
                        Bitmap.createBitmap(
                            request.widthPx,
                            request.heightPx,
                            Bitmap.Config.ARGB_8888,
                        )
                    val canvas = Canvas(bitmap)
                    val paint = Paint()
                    for (y in 0 until request.heightPx step 16) for (x in
                        0 until request.widthPx step 16) {
                        paint.color =
                            if ((x / 16 + y / 16) % 2 == 0)
                                android.graphics.Color.rgb(
                                    30,
                                    80 + request.mediaKey.mediaStoreId.toInt() * 30,
                                    170,
                                )
                            else android.graphics.Color.rgb(230, 235, 240)
                        canvas.drawRect(
                            x.toFloat(),
                            y.toFloat(),
                            (x + 16).toFloat(),
                            (y + 16).toFloat(),
                            paint,
                        )
                    }
                    bitmaps[request] = bitmap
                    hashes[request] = hash(bitmap)
                    bitmap
                },
                32L * 1024 * 1024,
            )
        val ratios = java.util.concurrent.atomic.AtomicReference<List<Double>>(emptyList())
        try {
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    UGalleryTheme(darkTheme = dark) {
                        val c = androidx.compose.material3.MaterialTheme.colorScheme
                        SideEffect {
                            ratios.set(
                                listOf(
                                        c.onSurface to c.surface,
                                        c.onSurfaceVariant to c.surfaceVariant,
                                        c.onSecondaryContainer to c.secondaryContainer,
                                        c.onPrimary to c.primary,
                                    )
                                    .map { (fg, bg) ->
                                        (maxOf(fg.luminance(), bg.luminance()) + 0.05) /
                                            (minOf(fg.luminance(), bg.luminance()) + 0.05)
                                    }
                            )
                        }
                        if (visible.value)
                            PhotoStacksContent(
                                repo,
                                loader,
                                {},
                                {},
                                id,
                                Modifier.width(width.dp).height(640.dp),
                            )
                    }
                }
            }
            fun scroll(tag: String) {
                compose.onNodeWithTag("photo-stacks-list").performScrollToNode(hasTestTag(tag))
                compose.onNodeWithTag(tag).assertIsDisplayed()
            }
            fun click(tag: String) {
                scroll(tag)
                compose.onNodeWithTag(tag).assertIsEnabled().performClick()
            }
            fun capture(label: String) {
                val image =
                    compose.onNodeWithTag("photo-stacks-screen").captureToImage().asAndroidBitmap()
                java.io
                    .File(context.filesDir, "stacks-$dark-$width-$scale-$label.png")
                    .outputStream()
                    .use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                java.io
                    .File(context.filesDir, "stacks-$dark-$width-$scale-$label.txt")
                    .writeText(compose.onRoot().printToString())
            }
            compose.waitUntil(10000) {
                compose.onAllNodesWithTag("stack-hero").fetchSemanticsNodes().isNotEmpty()
            }
            for (n in listOf(2L, 1L, 3L, 2L, 1L, 3L, 2L)) {
                scroll("stack-filmstrip")
                compose
                    .onNodeWithTag("stack-filmstrip")
                    .performScrollToNode(hasTestTag("stack-photo-external_primary:$n"))
                compose
                    .onNodeWithTag("stack-photo-external_primary:$n")
                    .performClick()
                    .assertIsSelected()
            }
            click("stack-set-cover")
            compose.waitUntil(10000) {
                runBlocking { db.photoStackDao().get(id)?.coverMediaStoreId == 2L }
            }
            click("stack-compare")
            val comparison = compose.onNodeWithTag("stack-comparison").getUnclippedBoundsInRoot()
            val coverBounds =
                compose.onNodeWithTag("stack-comparison-cover").getUnclippedBoundsInRoot()
            val selectedBounds =
                compose.onNodeWithTag("stack-comparison-selected").getUnclippedBoundsInRoot()
            if (comparison.right - comparison.left >= 600.dp)
                assertTrue(coverBounds.right <= selectedBounds.left)
            else assertTrue(coverBounds.bottom <= selectedBounds.top)
            scroll("stack-compare-zoom")
            compose.onNodeWithTag("stack-compare-zoom").performSemanticsAction(
                SemanticsActions.SetProgress
            ) {
                assertTrue(it(2f))
            }
            capture("comparison")
            click("stack-close-compare")
            val rename = context.getString(R.string.stacks_rename)
            compose.onNodeWithTag("photo-stacks-list").performScrollToNode(hasText(rename))
            compose.onNodeWithText(rename).performClick()
            compose
                .onNodeWithText(context.getString(R.string.stacks_name))
                .performTextReplacement("Summer friends")
            compose.onNodeWithText(context.getString(R.string.stacks_save_name)).performClick()
            compose.waitUntil(10000) {
                runBlocking { db.photoStackDao().get(id)?.title == "Summer friends" }
            }
            click("stack-separate")
            compose.waitUntil(10000) { runBlocking { db.photoStackDao().rawMembers(id).size == 2 } }
            assertEquals(1L, db.photoStackDao().get(id)!!.coverMediaStoreId)
            assertEquals(media(2), db.documentDao().get("external_primary", 2)!!.media)
            click("stack-unstack")
            compose.onNodeWithTag("stack-confirm-unstack").performClick()
            compose.waitUntil(10000) { runBlocking { db.photoStackDao().get(id) == null } }
            assertEquals(3L, db.libraryDao().mediaCount())
            for ((request, bitmap) in bitmaps) {
                assertEquals(hashes[request], hash(bitmap))
                loader.cached(request)?.let { assertEquals(hashes[request], hash(it)) }
            }
            assertTrue(bitmaps.keys.any { it.widthPx == 1024 })
            assertTrue(ratios.get().all { it >= 4.5 })
            android.util.Log.i(
                "PhotoStacksUI",
                "PASS dark=$dark width=$width scale=$scale immutableFrames=${bitmaps.size} contrast=${ratios.get()}",
            )
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            loader.close()
            db.close()
        }
    }
}
