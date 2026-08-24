package com.ugallery.feature.viewer

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.view.SurfaceView
import androidx.media3.common.Effect
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipe
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.designsystem.UGalleryTheme
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.MediaKind
import com.ugallery.core.model.TimelineMedia
import com.ugallery.core.thumbnail.ThumbnailLoader
import com.ugallery.core.thumbnail.ThumbnailSource
import com.ugallery.core.preferences.VideoScrubbingMode
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
                    onRename = {},
                    onCopy = {},
                    onMove = {},
                    onOpenWith = {},
                    onSetAs = {},
                    onPrint = {},
                    onRepairDate = {},
                    onTrash = {},
                    onSelectMedia = { selected = it },
                )
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val firstPosition = context.getString(R.string.viewer_thumbnail_position, 1, 3)
        compose.onNode(hasTestTag(VIEWER_CHROME_SCRIM_TEST_TAG)).assertDoesNotExist()
        compose.onNode(hasContentDescription(firstPosition)).assertExists()

        compose.onRoot().performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        compose.onNode(hasContentDescription(firstPosition)).assertDoesNotExist()
        compose.onRoot().performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()

        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitUntil { selected != null }
        assertEquals(2L, selected?.key?.mediaStoreId)
    }

    @Test fun replacingTheViewerWindowAfterSwipeDoesNotSelectAnUnrelatedItem() {
        val originalWindow = listOf(media(1), media(2), media(3))
        val shiftedWindow = listOf(media(2), media(3), media(4))
        val selections = mutableListOf<Long>()
        var current by mutableStateOf(originalWindow[1])
        var window by mutableStateOf(originalWindow)
        compose.setContent {
            UGalleryTheme {
                ViewerContent(
                    media = current,
                    mediaItems = window,
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
                    onRename = {},
                    onCopy = {},
                    onMove = {},
                    onOpenWith = {},
                    onSetAs = {},
                    onPrint = {},
                    onRepairDate = {},
                    onTrash = {},
                    onSelectMedia = {
                        selections += it.key.mediaStoreId
                        current = it
                        if (it.key.mediaStoreId == 3L) window = shiftedWindow
                    },
                )
            }
        }

        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitUntil { current.key.mediaStoreId == 3L }
        compose.waitForIdle()

        assertEquals(listOf(3L), selections)
    }

    @Test fun secondDoubleTapResetsPhotoZoomAndUnlocksPaging() {
        val items = listOf(media(1), media(2))
        var selected: TimelineMedia? = null
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val photo = PhotoLoadState.Ready(
            drawable = BitmapDrawable(context.resources, bitmap),
            isAnimated = false,
            supportsDeepZoom = false,
            deepZoomUnavailableReason = null,
        )
        compose.setContent {
            UGalleryTheme {
                ViewerContent(
                    media = items.first(),
                    mediaItems = items,
                    photoState = photo,
                    videoController = null,
                    thumbnailLoader = thumbnails,
                    isFavorite = false,
                    onBack = {},
                    onToggleFavorite = {},
                    onShare = {},
                    onShareSanitized = {},
                    onDetails = {},
                    onEdit = {},
                    onRename = {},
                    onCopy = {},
                    onMove = {},
                    onOpenWith = {},
                    onSetAs = {},
                    onPrint = {},
                    onRepairDate = {},
                    onTrash = {},
                    onSelectMedia = { selected = it },
                )
            }
        }

        compose.onRoot().performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertNull(selected)

        compose.onRoot().performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitUntil { selected != null }
        assertEquals(2L, selected?.key?.mediaStoreId)
    }

    @Test fun landscapeVideoKeepsItsRatioAndPlaybackChromeAutoHidesUntilPaused() {
        val item = TimelineMedia(
            key = MediaKey("external_primary", 7),
            kind = MediaKind.Video,
            generationModified = 1,
            timelineSortMillis = 7,
            width = 1_920,
            height = 1_080,
            durationMillis = 10_000,
        )
        val engine = FakeVideoEngine()
        val controller = VideoViewerController(engine)
        controller.select(Uri.parse("content://media/external/video/media/7"))
        engine.listener?.onVideoAspectRatioChanged(16f / 9f)
        engine.listener?.onReady(10_000, true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            UGalleryTheme {
                ViewerContent(
                    media = item,
                    mediaItems = listOf(item),
                    photoState = null,
                    videoController = controller,
                    thumbnailLoader = thumbnails,
                    isFavorite = false,
                    onBack = {},
                    onToggleFavorite = {},
                    onShare = {},
                    onShareSanitized = {},
                    onDetails = {},
                    onEdit = {},
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
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val videoBounds = compose.onNode(
            hasContentDescription(context.getString(R.string.viewer_video_description)),
        ).fetchSemanticsNode().boundsInRoot
        assertEquals(16f / 9f, videoBounds.width / videoBounds.height, 0.03f)

        val pause = context.getString(R.string.viewer_pause)
        val play = context.getString(R.string.viewer_play)
        compose.onNode(hasTestTag(VIEWER_CHROME_SCRIM_TEST_TAG)).assertExists()
        compose.onNode(hasContentDescription(pause)).assertExists()

        compose.mainClock.advanceTimeBy(3_200)
        compose.waitForIdle()
        compose.onNode(hasTestTag(VIEWER_CHROME_SCRIM_TEST_TAG)).assertDoesNotExist()
        compose.onNode(hasContentDescription(pause)).assertDoesNotExist()

        compose.onRoot().performTouchInput {
            click(androidx.compose.ui.geometry.Offset(center.x, center.y * 0.65f))
        }
        // Single-tap dispatch waits for the double-tap timeout before revealing chrome.
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onNode(hasContentDescription(pause)).performClick()
        compose.mainClock.advanceTimeBy(3_200)
        compose.waitForIdle()

        assertTrue(engine.pauseCalls > 0)
        compose.onNode(hasTestTag(VIEWER_CHROME_SCRIM_TEST_TAG)).assertExists()
        compose.onNode(hasContentDescription(play)).assertExists()
        controller.close()
    }

    @Test fun legacySeekBarPausesSeeksAndResumesPlayback() {
        val item = video(8)
        val engine = FakeVideoEngine()
        val controller = VideoViewerController(engine)
        controller.select(Uri.parse("content://media/external/video/media/8"))
        engine.listener?.onReady(10_000L, true)
        compose.setContent {
            UGalleryTheme {
                ViewerContent(
                    media = item,
                    mediaItems = listOf(item),
                    photoState = null,
                    videoController = controller,
                    thumbnailLoader = thumbnails,
                    isFavorite = false,
                    onBack = {},
                    onToggleFavorite = {},
                    onShare = {},
                    onShareSanitized = {},
                    onDetails = {},
                    onEdit = {},
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

        compose.onNode(hasTestTag(VIDEO_LEGACY_SEEK_BAR_TEST_TAG)).performTouchInput {
            swipe(
                start = androidx.compose.ui.geometry.Offset(width * 0.2f, height * 0.7f),
                end = androidx.compose.ui.geometry.Offset(width * 0.8f, height * 0.7f),
                durationMillis = 400,
            )
        }
        compose.waitForIdle()

        assertTrue(engine.pauseCalls > 0)
        assertTrue(engine.playCalls > 0)
        assertTrue(engine.lastSeek > 5_000L)
        controller.close()
    }

    @Test fun filmstripScrubsAndCanCollapseAndReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val videoFile = File(context.cacheDir, "viewer-filmstrip-h264.mp4")
        context.assets.open("h264.mp4").use { input -> videoFile.outputStream().use(input::copyTo) }
        val item = video(9)
        val engine = FakeVideoEngine()
        val controller = VideoViewerController(engine)
        controller.select(Uri.fromFile(videoFile))
        engine.listener?.onReady(10_000L, true)
        compose.setContent {
            UGalleryTheme {
                ViewerContent(
                    media = item,
                    mediaItems = listOf(item),
                    photoState = null,
                    videoController = controller,
                    thumbnailLoader = thumbnails,
                    isFavorite = false,
                    onBack = {},
                    onToggleFavorite = {},
                    onShare = {},
                    onShareSanitized = {},
                    onDetails = {},
                    onEdit = {},
                    onRename = {},
                    onCopy = {},
                    onMove = {},
                    onOpenWith = {},
                    onSetAs = {},
                    onPrint = {},
                    onRepairDate = {},
                    onTrash = {},
                    onSelectMedia = {},
                    videoScrubbingMode = VideoScrubbingMode.Filmstrip,
                )
            }
        }

        compose.onNode(hasTestTag(VIDEO_FRAME_SCRUBBER_TEST_TAG)).performTouchInput {
            swipe(
                start = androidx.compose.ui.geometry.Offset(width * 0.15f, height * 0.7f),
                end = androidx.compose.ui.geometry.Offset(width * 0.75f, height * 0.7f),
                durationMillis = 400,
            )
        }
        compose.waitForIdle()
        assertTrue(engine.lastSeek > 5_000L)

        compose.onNode(hasContentDescription(context.getString(R.string.viewer_close_video_timeline))).performClick()
        compose.onNode(hasTestTag(VIDEO_FRAME_SCRUBBER_TEST_TAG)).assertDoesNotExist()
        compose.onNode(hasContentDescription(context.getString(R.string.viewer_thumbnail_position, 1, 1))).performClick()
        compose.onNode(hasTestTag(VIDEO_FRAME_SCRUBBER_TEST_TAG)).assertExists()

        controller.close()
        videoFile.delete()
    }

    @Test fun unavailableFilmstripFallsBackToLegacySeekBar() {
        val item = video(10)
        val engine = FakeVideoEngine()
        val controller = VideoViewerController(engine)
        controller.select(Uri.parse("content://missing/video/10"))
        engine.listener?.onReady(10_000L, false)
        compose.setContent {
            UGalleryTheme {
                ViewerContent(
                    media = item,
                    mediaItems = listOf(item),
                    photoState = null,
                    videoController = controller,
                    thumbnailLoader = thumbnails,
                    isFavorite = false,
                    onBack = {},
                    onToggleFavorite = {},
                    onShare = {},
                    onShareSanitized = {},
                    onDetails = {},
                    onEdit = {},
                    onRename = {},
                    onCopy = {},
                    onMove = {},
                    onOpenWith = {},
                    onSetAs = {},
                    onPrint = {},
                    onRepairDate = {},
                    onTrash = {},
                    onSelectMedia = {},
                    videoScrubbingMode = VideoScrubbingMode.Filmstrip,
                )
            }
        }

        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag(VIDEO_LEGACY_SEEK_BAR_TEST_TAG))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasTestTag(VIDEO_LEGACY_SEEK_BAR_TEST_TAG)).assertExists()
        controller.close()
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

    private fun video(id: Long) = TimelineMedia(
        key = MediaKey("external_primary", id),
        kind = MediaKind.Video,
        generationModified = 1,
        timelineSortMillis = id,
        width = 1_920,
        height = 1_080,
        durationMillis = 10_000,
    )

    private class FakeVideoEngine : VideoEngine {
        override var listener: VideoEngine.Listener? = null
        var pauseCalls = 0
        var playCalls = 0
        var lastSeek = 0L
        val repeatEnabled = mutableListOf<Boolean>()
        override fun setMedia(uri: Uri) = Unit
        override fun prepare() = Unit
        override fun play() { playCalls++; listener?.onPlayingChanged(true, 10_000) }
        override fun pause() {
            pauseCalls++
            listener?.onPlayingChanged(false, 10_000)
        }
        override fun seekTo(positionMillis: Long) { lastSeek = positionMillis }
        override fun stopAndClear() = Unit
        override fun release() = Unit
        override fun attachSurface(surfaceView: SurfaceView?) = Unit
        override fun setVolume(volume: Float) = Unit
        override fun setRepeatEnabled(enabled: Boolean) { repeatEnabled += enabled }
        override fun setVideoEffects(effects: List<Effect>) = Unit
        override fun currentPositionMillis(): Long = lastSeek
    }
}
