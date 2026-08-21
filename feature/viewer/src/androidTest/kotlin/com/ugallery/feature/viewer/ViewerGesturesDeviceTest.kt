package com.ugallery.feature.viewer

import android.graphics.Bitmap
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.designsystem.UGalleryTheme
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.MediaKind
import com.ugallery.core.model.TimelineMedia
import com.ugallery.core.thumbnail.ThumbnailLoader
import com.ugallery.core.thumbnail.ThumbnailSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerGesturesDeviceTest {
    @get:Rule val compose = createComposeRule()

    private val thumbnails = ThumbnailLoader(
        source = ThumbnailSource { request, _ ->
            Bitmap.createBitmap(request.widthPx, request.heightPx, Bitmap.Config.ARGB_8888)
        },
        maxCacheBytes = 2L * 1_024 * 1_024,
        threadCount = 1,
    )

    @After fun closeLoader() = thumbnails.close()

    @Test fun tapTogglesFilmstripAndSwipeSelectsTheNextItem() {
        val items = listOf(media(1), media(2), media(3))
        var selected: TimelineMedia? = null
        compose.setContent {
            UGalleryTheme {
                ViewerContent(
                    media = items.first(),
                    mediaItems = items,
                    photoState = PhotoLoadState.Error(PhotoFailure.CorruptOrUnsupported),
                    videoController = null,
                    thumbnailLoader = thumbnails,
                    isFavorite = false,
                    onBack = {},
                    onToggleFavorite = {},
                    onShare = {},
                    onShareSanitized = {},
                    onDetails = {},
                    onEdit = {},
                    onTrash = {},
                    onSelectMedia = { selected = it },
                )
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val firstPosition = context.getString(R.string.viewer_thumbnail_position, 1, 3)
        compose.onNode(hasContentDescription(firstPosition)).assertExists()

        compose.onRoot().performTouchInput { click(center) }
        compose.onNode(hasContentDescription(firstPosition)).assertDoesNotExist()
        compose.onRoot().performTouchInput { click(center) }

        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitUntil { selected != null }
        assertEquals(2L, selected?.key?.mediaStoreId)
    }

    private fun media(id: Long) = TimelineMedia(
        key = MediaKey("external_primary", id),
        kind = MediaKind.Image,
        generationModified = 1,
        timelineSortMillis = id,
        width = 100,
        height = 100,
        durationMillis = 0,
    )
}
