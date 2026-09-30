package com.librestatic.lightforge.feature.photos

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.model.*
import com.librestatic.lightforge.core.thumbnail.*
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class StackTimelineContentDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun lightCollapsedBadgeAndExpandedSelection(): Unit = exercise(false, 1f)

    @Test fun darkLargeTextCollapsedBadgeAndExpandedSelection(): Unit = exercise(true, 1.6f)

    private fun exercise(dark: Boolean, scale: Float) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val selected = mutableStateOf(false)
        val coverId = mutableStateOf(1L)
        var clicked: TimelineMedia? = null
        val ratios = java.util.concurrent.atomic.AtomicReference<List<Double>>(emptyList())
        fun media(id: Long) =
            TimelineMedia(MediaKey("fixture", id), MediaKind.Image, 1, 1, 100, 100, 0)
        val cover = media(1).copy(stack = TimelineStack("saved", "revision", 3))
        val loader =
            ThumbnailLoader(
                ThumbnailSource { request, _ ->
                    Bitmap.createBitmap(request.widthPx, request.heightPx, Bitmap.Config.ARGB_8888)
                        .apply {
                            eraseColor(
                                android.graphics.Color.rgb(
                                    35 + request.mediaKey.mediaStoreId.toInt() * 30,
                                    100,
                                    140,
                                )
                            )
                        }
                },
                4L * 1024 * 1024,
            )
        try {
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    LightforgeTheme(darkTheme = dark) {
                        val c = androidx.compose.material3.MaterialTheme.colorScheme
                        SideEffect {
                            ratios.set(
                                listOf(c.onSecondaryContainer to c.secondaryContainer, c.onBackground to c.background).map {
                                    (fg, bg) ->
                                    (maxOf(fg.luminance(), bg.luminance()) + 0.05) /
                                        (minOf(fg.luminance(), bg.luminance()) + 0.05)
                                }
                            )
                        }
                        val entries =
                            remember(selected.value, coverId.value) {
                                    flowOf(
                                        PagingData.from<TimelineEntry>(
                                            if (selected.value)
                                                (1L..3L).map { TimelineEntry.Media(media(it)) }
                                            else
                                                listOf(
                                                    TimelineEntry.Media(
                                                        cover.copy(
                                                            key = MediaKey("fixture", coverId.value)
                                                        )
                                                    )
                                                )
                                        )
                                    )
                                }
                                .collectAsLazyPagingItems()
                        LibraryPhotosRoute(
                            LibraryAccess(GrantLevel.Full, GrantLevel.Full, false),
                            LibraryUiState.Ready,
                            entries,
                            loader,
                            {},
                            preferredColumns = 2,
                            selectionMode = selected.value,
                            onMediaClick = { clicked = it },
                            onMediaSelectionChange = { item, value ->
                                assertEquals(3, item.stack?.count)
                                selected.value = value
                            },
                            isMediaSelected = { selected.value },
                            modifier = Modifier.width(360.dp).height(640.dp),
                        )
                    }
                }
            }
            compose.waitUntil(10000) {
                compose.onAllNodesWithTag("media_fixture_1").fetchSemanticsNodes().isNotEmpty()
            }
            val description = context.getString(R.string.timeline_stack_description, 3)
            compose.onNodeWithContentDescription(description).assertIsDisplayed().performClick()
            assertEquals("saved", clicked?.stack?.id)
            compose.onNodeWithTag("media_fixture_2").assertDoesNotExist()
            compose
                .onNodeWithTag("timeline-stack-saved", useUnmergedTree = true)
                .assertIsDisplayed()
            fun capture(label: String) {
                val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
                java.io
                    .File(context.filesDir, "stack-timeline-$dark-$scale-$label.png")
                    .outputStream()
                    .use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                java.io
                    .File(context.filesDir, "stack-timeline-$dark-$scale-$label.txt")
                    .writeText(compose.onRoot().printToString())
            }
            capture("collapsed")
            compose.runOnIdle { coverId.value = 2L }
            compose.waitUntil(10000) {
                compose
                    .onAllNodesWithTag("timeline-image-loaded-fixture_2", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose
                .onNodeWithTag("timeline-image-loaded-fixture_1", useUnmergedTree = true)
                .assertDoesNotExist()
            val pixels =
                compose
                    .onNodeWithTag("timeline-image-loaded-fixture_2", useUnmergedTree = true)
                    .captureToImage()
                    .toPixelMap()
            assertEquals(95f / 255f, pixels[pixels.width / 2, pixels.height / 2].red, 0.02f)
            compose.onNodeWithContentDescription(description).performSemanticsAction(
                SemanticsActions.OnLongClick
            ) {
                assertTrue(it())
            }
            compose.waitUntil(10000) {
                compose.onAllNodesWithTag("media_fixture_2").fetchSemanticsNodes().isNotEmpty()
            }
            compose
                .onNodeWithText(context.getString(R.string.timeline_stack_selection_hint))
                .assertIsDisplayed()
            compose.onNodeWithTag("media_fixture_2").assertIsSelected()
            compose
                .onNodeWithTag("timeline-stack-saved", useUnmergedTree = true)
                .assertDoesNotExist()
            capture("selection")
            assertTrue(ratios.get().all { it >= 4.5 })
            android.util.Log.i(
                "StackTimelineUI",
                "PASS dark=$dark scale=$scale contrast=${ratios.get()}",
            )
        } finally {
            loader.close()
        }
    }
}
