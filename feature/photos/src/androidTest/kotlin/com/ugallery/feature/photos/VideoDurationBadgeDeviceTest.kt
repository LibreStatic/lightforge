package com.ugallery.feature.photos

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.designsystem.R as DesignSystemR
import com.ugallery.core.designsystem.UGalleryTheme
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.MediaKind
import com.ugallery.core.model.GrantLevel
import com.ugallery.core.model.LibraryAccess
import com.ugallery.core.model.TimelineEntry
import com.ugallery.core.model.TimelineMedia
import com.ugallery.core.thumbnail.ThumbnailLoader
import com.ugallery.core.thumbnail.ThumbnailSource
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoDurationBadgeDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun mixedGridAnnouncesVideoDurationAndKeepsPhotosUnbadged() {
        val thumbnails = ThumbnailLoader(
            source = ThumbnailSource { request, _ ->
                Bitmap.createBitmap(request.widthPx, request.heightPx, Bitmap.Config.ARGB_8888)
            },
            maxCacheBytes = 2L * 1024 * 1024,
            threadCount = 1,
        )
        val items = listOf(
            TimelineEntry.Media(media(1, MediaKind.Image, 0L)),
            TimelineEntry.Media(media(2, MediaKind.Video, 64_000L)),
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val videoDescription = context.getString(
            DesignSystemR.string.video_duration_description,
            "1:04",
        )
        val photoDescription = context.getString(R.string.photo_thumbnail_description)
        val entries = Pager(PagingConfig(pageSize = 20)) {
            object : PagingSource<Int, TimelineEntry>() {
                override suspend fun load(params: LoadParams<Int>): LoadResult<Int, TimelineEntry> =
                    LoadResult.Page(items, prevKey = null, nextKey = null)

                override fun getRefreshKey(state: PagingState<Int, TimelineEntry>): Int? = null
            }
        }.flow

        compose.setContent {
            UGalleryTheme {
                AdaptivePagedPhotosTimeline(
                    entries = entries.collectAsLazyPagingItems(),
                    thumbnailLoader = thumbnails,
                    preferredColumns = 2,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        compose.onNodeWithContentDescription(videoDescription).assertExists()
        compose.onNodeWithContentDescription(photoDescription).assertExists()
        thumbnails.close()
    }

    @Test
    fun libraryRouteForwardsSelectionToTheVisibleMediaCell() {
        val thumbnails = ThumbnailLoader(
            source = ThumbnailSource { request, _ ->
                Bitmap.createBitmap(request.widthPx, request.heightPx, Bitmap.Config.ARGB_8888)
            },
            maxCacheBytes = 2L * 1024 * 1024,
            threadCount = 1,
        )
        val selectedMedia = media(1, MediaKind.Image, 0L)
        var openedDeviceFolders = false
        val entries = Pager(PagingConfig(pageSize = 20)) {
            object : PagingSource<Int, TimelineEntry>() {
                override suspend fun load(params: LoadParams<Int>): LoadResult<Int, TimelineEntry> =
                    LoadResult.Page(
                        listOf(TimelineEntry.Media(selectedMedia)),
                        prevKey = null,
                        nextKey = null,
                    )

                override fun getRefreshKey(state: PagingState<Int, TimelineEntry>): Int? = null
            }
        }.flow

        compose.setContent {
            UGalleryTheme {
                LibraryPhotosRoute(
                    access = LibraryAccess(GrantLevel.Full, GrantLevel.Full, false),
                    engineState = LibraryUiState.Ready,
                    entries = entries.collectAsLazyPagingItems(),
                    thumbnailLoader = thumbnails,
                    onRequestAccess = {},
                    onOpenDeviceFolders = { openedDeviceFolders = true },
                    isMediaSelected = { it.key == selectedMedia.key },
                )
            }
        }

        compose.onNodeWithTag("media_test_101").assertIsSelected()
        compose.onNodeWithTag("media_selection_indicator", useUnmergedTree = true).assertExists()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.library_local)).performClick()
        compose.runOnIdle { assertTrue(openedDeviceFolders) }
        thumbnails.close()
    }

    private fun media(index: Int, kind: MediaKind, durationMillis: Long) = TimelineMedia(
        key = MediaKey("test", 100L + index),
        kind = kind,
        generationModified = 1L,
        timelineSortMillis = 1_000_000L + index * 1_000L,
        width = 100,
        height = 100,
        durationMillis = durationMillis,
    )
}
