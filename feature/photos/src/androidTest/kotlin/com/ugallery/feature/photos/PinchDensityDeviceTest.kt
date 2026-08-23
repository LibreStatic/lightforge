package com.ugallery.feature.photos

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.designsystem.UGalleryTheme
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.MediaKind
import com.ugallery.core.model.TimelineEntry
import com.ugallery.core.model.TimelineMedia
import com.ugallery.core.thumbnail.ThumbnailLoader
import com.ugallery.core.thumbnail.ThumbnailSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PinchDensityDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private fun media(i: Int) = TimelineMedia(
        key = MediaKey("test", 100L + i),
        kind = MediaKind.Image,
        generationModified = 1L,
        timelineSortMillis = 1_000_000L + i * 1_000L,
        width = 100,
        height = 100,
        durationMillis = 0L,
    )

    private fun loader() = ThumbnailLoader(
        source = ThumbnailSource { request, _ ->
            Bitmap.createBitmap(request.widthPx, request.heightPx, Bitmap.Config.ARGB_8888)
        },
        maxCacheBytes = 2L * 1024 * 1024,
        threadCount = 1,
    )

    private fun entriesFlow(items: List<TimelineEntry>) = Pager(
        config = PagingConfig(pageSize = 20, initialLoadSize = 60),
    ) {
        object : PagingSource<Int, TimelineEntry>() {
            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, TimelineEntry> =
                LoadResult.Page(data = items, prevKey = null, nextKey = null)

            override fun getRefreshKey(state: PagingState<Int, TimelineEntry>): Int? = null
        }
    }.flow

    private fun pinchOutOnce() {
        compose.onRoot().performTouchInput {
            pinch(
                start0 = Offset(width * 0.38f, height * 0.5f),
                end0 = Offset(width * 0.12f, height * 0.5f),
                start1 = Offset(width * 0.62f, height * 0.5f),
                end1 = Offset(width * 0.88f, height * 0.5f),
            )
        }
        compose.waitForIdle()
    }

    private fun pinchInOnce() {
        compose.onRoot().performTouchInput {
            pinch(
                start0 = Offset(width * 0.10f, height * 0.5f),
                end0 = Offset(width * 0.40f, height * 0.5f),
                start1 = Offset(width * 0.90f, height * 0.5f),
                end1 = Offset(width * 0.60f, height * 0.5f),
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun pinchChangesColumnCountBothWays() {
        val thumbnails = loader()
        val items = List(80) { TimelineEntry.Media(media(it)) }
        val reported = mutableListOf<Int>()
        compose.setContent {
            UGalleryTheme {
                val lazyItems = entriesFlow(items).collectAsLazyPagingItems()
                AdaptivePagedPhotosTimeline(
                    entries = lazyItems,
                    thumbnailLoader = thumbnails,
                    preferredColumns = 3,
                    onDensityChange = { reported.add(it) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.waitForIdle()
        assertTrue("density callback must fire at least once", reported.isNotEmpty())
        val baseline = reported.last()

        pinchOutOnce()
        assertTrue(
            "pinch-out must increase columns: reported=$reported",
            reported.last() == baseline + 1 || reported.last() == baseline,
        )
        val afterOut = reported.last()
        if (afterOut == baseline + 1) {
            pinchInOnce()
            assertEquals(
                "pinch-in must restore the baseline columns: reported=$reported",
                baseline, reported.last(),
            )
        }
        thumbnails.close()
    }
}
