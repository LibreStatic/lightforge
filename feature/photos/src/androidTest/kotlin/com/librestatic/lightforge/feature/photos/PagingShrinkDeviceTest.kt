package com.librestatic.lightforge.feature.photos

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.model.*
import com.librestatic.lightforge.core.thumbnail.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PagingShrinkDeviceTest {
    @get:Rule val compose = createComposeRule()
    private fun items(count: Int) = List<TimelineEntry>(count) { i ->
        TimelineEntry.Media(TimelineMedia(MediaKey("paging-shrink", i + 1L), MediaKind.Image,
            1, i * 1000L, 100, 100, 0))
    }
    @Test fun activePrefetchSurvivesShrinkingAndEmptyPagingSnapshots() {
        val calls = AtomicInteger()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loader = ThumbnailLoader(ThumbnailSource { request, _ ->
            calls.incrementAndGet()
            Bitmap.createBitmap(request.widthPx, request.heightPx, Bitmap.Config.ARGB_8888)
        }, 4L * 1024 * 1024, threadCount = 1,
            prefetchPolicy = ThumbnailPrefetchPolicy.detect(context, 4L * 1024 * 1024))
        val flow = MutableStateFlow(PagingData.from(items(40)))
        lateinit var lazy: LazyPagingItems<TimelineEntry>
        lateinit var grid: LazyGridState
        lateinit var scope: CoroutineScope
        try {
            compose.setContent {
                LightforgeTheme {
                    lazy = flow.collectAsLazyPagingItems()
                    grid = rememberLazyGridState()
                    scope = rememberCoroutineScope()
                    PagedPhotosTimeline(lazy, loader, columns = 3, thumbnailSizePx = 64,
                        modifier = Modifier.fillMaxSize(), state = grid)
                }
            }
            compose.waitUntil(10_000) { lazy.itemCount == 40 && calls.get() > 0 }
            compose.runOnIdle { scope.launch { grid.scrollToItem(38) } }
            compose.waitUntil(10_000) { grid.firstVisibleItemIndex >= 20 }
            for (size in listOf(6, 2, 0, 9, 1, 0)) {
                compose.runOnIdle { flow.value = PagingData.from(items(size)) }
                compose.waitUntil(10_000) { lazy.itemCount == size }
                compose.waitForIdle()
            }
            assertTrue(calls.get() > 0)
        } finally { loader.close() }
    }
}
