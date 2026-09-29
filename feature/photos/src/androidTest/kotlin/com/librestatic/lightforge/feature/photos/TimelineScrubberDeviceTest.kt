package com.librestatic.lightforge.feature.photos

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.center
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.up
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.model.*
import com.librestatic.lightforge.core.thumbnail.*
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TimelineScrubberDeviceTest {
    @get:Rule val compose = createComposeRule()

    private val newest = LocalDate.of(2026, 9, 1)
    private val dayCount = 400
    private val perDay = 3
    private fun dayAt(offset: Int) = newest.minusDays(offset.toLong())

    private fun entriesFor(days: Int): List<TimelineEntry> = buildList {
        for (offset in 0 until days) {
            val day = dayAt(offset)
            add(TimelineEntry.DayHeader(day.toEpochDay()))
            repeat(perDay) { n ->
                val millis = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() - n
                add(TimelineEntry.Media(TimelineMedia(MediaKey("scrub", offset * 10L + n), MediaKind.Image, 1, millis, 100, 100, 0)))
            }
        }
    }

    private val index = TimelineIndex((0 until dayCount).map { TimelineDayBucket(dayAt(it).toEpochDay(), perDay) })

    private fun handle() = compose.onNodeWithTag("timeline_scrub_handle")
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun setUp(loadedDays: Int, jumps: MutableList<TimelineAnchor?>): Pair<() -> LazyGridState, ThumbnailLoader> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loader = ThumbnailLoader(ThumbnailSource { request, _ ->
            Bitmap.createBitmap(request.widthPx, request.heightPx, Bitmap.Config.ARGB_8888)
        }, 4L * 1024 * 1024, threadCount = 1,
            prefetchPolicy = ThumbnailPrefetchPolicy.detect(context, 4L * 1024 * 1024))
        val flow = MutableStateFlow(PagingData.from(entriesFor(loadedDays)))
        lateinit var grid: LazyGridState
        lateinit var lazy: LazyPagingItems<TimelineEntry>
        compose.setContent {
            LightforgeTheme {
                lazy = flow.collectAsLazyPagingItems()
                grid = rememberLazyGridState()
                AdaptivePagedPhotosTimeline(
                    entries = lazy,
                    thumbnailLoader = loader,
                    modifier = Modifier.fillMaxSize(),
                    state = grid,
                    scrubberIndex = index,
                    onScrubberJump = { jumps += it },
                )
            }
        }
        compose.waitUntil(10_000) { lazy.itemCount == loadedDays * (perDay + 1) }
        return { grid } to loader
    }

    @Test fun handleAppearsWhileScrollingAndScrubbingJumpsBeforeRelease() {
        val jumps = Collections.synchronizedList(mutableListOf<TimelineAnchor?>())
        val (_, loader) = setUp(loadedDays = 40, jumps = jumps)
        try {
            assertTrue(!exists("timeline_scrub_handle"))
            compose.onNodeWithTag("timeline_grid").performTouchInput { swipeUp() }
            compose.waitUntil(5_000) { exists("timeline_scrub_handle") }
            assertTrue(exists("timeline_scrub_date"))

            handle().performTouchInput { down(center); moveBy(Offset(0f, 400f)) }
            compose.waitUntil(5_000) { exists("timeline_scrub_years") && exists("timeline_scrub_month") }
            // The pager restarts at the pointed day while the finger is still down.
            compose.waitUntil(5_000) { jumps.isNotEmpty() }
            val anchor = jumps.last()
            assertNotNull(anchor)
            assertTrue(anchor!!.epochDay < dayAt(39).toEpochDay())
            handle().performTouchInput { up() }
        } finally { loader.close() }
    }

    @Test fun scrubbingInsideTheLoadedWindowScrollsWithoutRestartingThePager() {
        val jumps = Collections.synchronizedList(mutableListOf<TimelineAnchor?>())
        val (grid, loader) = setUp(loadedDays = dayCount, jumps = jumps)
        try {
            compose.onNodeWithTag("timeline_grid").performTouchInput { swipeUp() }
            compose.waitUntil(5_000) { exists("timeline_scrub_handle") }
            handle().performTouchInput { down(center); moveBy(Offset(0f, 500f)) }
            // The grid follows the finger before release.
            compose.waitUntil(10_000) { grid().firstVisibleItemIndex > 200 }
            handle().performTouchInput { up() }
            assertEquals(emptyList<TimelineAnchor?>(), jumps.toList())
        } finally { loader.close() }
    }

    @Test fun aHeldFingerKeepsTheGridStillAndReturningLandsOnTheSameRow() {
        val jumps = Collections.synchronizedList(mutableListOf<TimelineAnchor?>())
        val (grid, loader) = setUp(loadedDays = dayCount, jumps = jumps)
        fun position() = grid().firstVisibleItemIndex to grid().firstVisibleItemScrollOffset
        try {
            compose.onNodeWithTag("timeline_grid").performTouchInput { swipeUp() }
            compose.waitUntil(5_000) { exists("timeline_scrub_handle") }
            handle().performTouchInput { down(center); moveBy(Offset(0f, 300f)) }
            compose.waitUntil(10_000) { grid().firstVisibleItemIndex > 100 }
            compose.waitForIdle()
            val held = position()
            // A resting finger trembles back and forth by a pixel or two.
            repeat(4) {
                handle().performTouchInput { moveBy(Offset(0f, -3f)) }
                compose.waitForIdle()
                assertEquals(held, position())
                handle().performTouchInput { moveBy(Offset(0f, 3f)) }
                compose.waitForIdle()
                assertEquals(held, position())
            }
            // Away and back to the very same finger position lands on the very same row.
            handle().performTouchInput { moveBy(Offset(0f, 200f)) }
            compose.waitForIdle()
            assertTrue(position() != held)
            handle().performTouchInput { moveBy(Offset(0f, -200f)) }
            compose.waitForIdle()
            assertEquals(held, position())
            handle().performTouchInput { up() }
        } finally { loader.close() }
    }

    @Test fun handleAndDateStayHiddenAtRest() {
        val jumps = Collections.synchronizedList(mutableListOf<TimelineAnchor?>())
        val (_, loader) = setUp(loadedDays = 40, jumps = jumps)
        try {
            compose.waitForIdle()
            assertTrue(!exists("timeline_scrub_handle"))
            assertTrue(!exists("timeline_scrub_date"))
        } finally { loader.close() }
    }
}
