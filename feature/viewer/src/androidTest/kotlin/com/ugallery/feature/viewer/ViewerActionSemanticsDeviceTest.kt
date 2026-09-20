package com.ugallery.feature.viewer

import android.graphics.Bitmap
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
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

/**
 * Regression test for M1: every bottom-bar viewer action must expose a single node that both
 * carries the accessibility label and owns the click action.
 */
@RunWith(AndroidJUnit4::class)
class ViewerActionSemanticsDeviceTest {
    @get:Rule val compose = createComposeRule()

    private val thumbnails = ThumbnailLoader(
        source = ThumbnailSource { request, _ ->
            Bitmap.createBitmap(request.widthPx, request.heightPx, Bitmap.Config.ARGB_8888)
        },
        maxCacheBytes = 2L * 1_024 * 1_024,
        threadCount = 1,
    )

    @After fun closeLoader() = thumbnails.close()

    @Test fun bottomBarActionsExposeLabelledClickableNodes() {
        val items = listOf(media(1), media(2))
        val clicks = mutableListOf<String>()
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
                    onToggleFavorite = { clicks += "favorite" },
                    onShare = { clicks += "share" },
                    onShareSanitized = {},
                    onDetails = { clicks += "details" },
                    onEdit = { clicks += "edit" },
                    onRename = {},
                    onCopy = {},
                    onMove = {},
                    onOpenWith = {},
                    onSetAs = {},
                    onPrint = {},
                    onRepairDate = {},
                    onTrash = {},
                    onSelectMedia = {},
                )
            }
        }
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expected = listOf(
            context.getString(R.string.viewer_share) to "share",
            context.getString(R.string.viewer_edit) to "edit",
            context.getString(R.string.viewer_favorite) to "favorite",
            context.getString(R.string.viewer_details) to "details",
        )
        expected.forEach { (label, _) ->
            compose.onNodeWithContentDescription(label).assertHasClickAction()
        }
        expected.forEach { (label, name) ->
            clicks.clear()
            compose.onNodeWithContentDescription(label).performClick()
            compose.waitForIdle()
            assertEquals(listOf(name), clicks)
        }
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
