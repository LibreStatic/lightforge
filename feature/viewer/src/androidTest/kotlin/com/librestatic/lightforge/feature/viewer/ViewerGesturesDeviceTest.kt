package com.librestatic.lightforge.feature.viewer

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.view.SurfaceView
import androidx.media3.common.Effect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveTo
import com.librestatic.lightforge.core.preferences.GestureSettings
import com.librestatic.lightforge.core.preferences.VideoHoldAction
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onFirst
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
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.model.ViewerMedia
import com.librestatic.lightforge.core.model.TimelineMedia
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.core.thumbnail.ThumbnailSource
import com.librestatic.lightforge.core.preferences.VideoScrubbingMode
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        var selected: ViewerMedia? = null
        compose.setContent {
            LightforgeTheme {
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
        val firstPosition = viewerThumbnailDescription(context.resources, media(1))
        compose.onNode(hasTestTag(VIEWER_CHROME_SCRIM_TEST_TAG)).assertDoesNotExist()
        // Fixtures share a capture minute, so every thumbnail carries the same label.
        compose.onAllNodes(hasContentDescription(firstPosition)).onFirst().assertExists()

        compose.onRoot().performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        compose.onAllNodes(hasContentDescription(firstPosition)).assertCountEquals(0)
        compose.onRoot().performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()

        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitUntil { selected != null }
        assertEquals(items[1].mediaKey, selected?.mediaKey)
        assertEquals(items[1].viewerId, selected?.viewerId)
    }

    @Test fun replacingTheViewerWindowAfterSwipeDoesNotSelectAnUnrelatedItem() {
        val originalWindow = listOf(media(1), media(2), media(3))
        val shiftedWindow = listOf(media(2), media(3), media(4))
        val selections = mutableListOf<Long>()
        var current by mutableStateOf<ViewerMedia>(originalWindow[1])
        var window by mutableStateOf(originalWindow)
        compose.setContent {
            LightforgeTheme {
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
                        val key = requireNotNull(it.mediaKey)
                        selections += key.mediaStoreId
                        current = it
                        if (key.mediaStoreId == 3L) window = shiftedWindow
                    },
                )
            }
        }

        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitUntil { current.mediaKey == shiftedWindow[1].mediaKey }
        compose.waitForIdle()

        assertEquals(listOf(3L), selections)
        assertEquals(shiftedWindow[1].viewerId, current.viewerId)
    }

    @Test fun secondDoubleTapResetsPhotoZoomAndUnlocksPaging() {
        val items = listOf(media(1), media(2))
        var selected: ViewerMedia? = null
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val photo = PhotoLoadState.Ready(
            drawable = BitmapDrawable(context.resources, bitmap),
            isAnimated = false,
            supportsDeepZoom = false,
            deepZoomUnavailableReason = null,
        )
        compose.setContent {
            LightforgeTheme {
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
        assertEquals(items[1].mediaKey, selected?.mediaKey)
        assertEquals(items[1].viewerId, selected?.viewerId)
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
            LightforgeTheme {
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

        compose.mainClock.advanceTimeBy(4_000)
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
            LightforgeTheme {
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

        assertEquals(listOf(true, false), engine.scrubbingModeChanges)
        assertEquals(0, engine.pauseCalls)
        assertEquals(0, engine.playCalls)
        assertTrue(engine.seekPositions.size > 1)
        assertTrue(engine.events.indexOf("scrubbing:true") < engine.events.indexOf("seek"))
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
            LightforgeTheme {
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
        assertEquals(listOf(true, false), engine.scrubbingModeChanges)
        assertTrue(engine.seekPositions.size > 1)
        assertTrue(engine.events.indexOf("scrubbing:true") < engine.events.indexOf("seek"))
        assertTrue(engine.lastSeek > 5_000L)

        compose.onNode(hasContentDescription(context.getString(R.string.viewer_close_video_timeline))).performClick()
        compose.onNode(hasTestTag(VIDEO_FRAME_SCRUBBER_TEST_TAG)).assertDoesNotExist()
        compose.onNode(hasContentDescription(viewerThumbnailDescription(context.resources, item))).performClick()
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
            LightforgeTheme {
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

    @Test fun holdingTheRightZoneFastForwardsAtTheConfiguredSpeedUntilRelease() {
        val engine = FakeVideoEngine()
        val controller = readyController(engine, playing = true)
        showVideo(controller, GestureSettings())

        // The clock only advances when the next event is injected, so the still move lets the hold timeout fire.
        compose.onRoot().performTouchInput { down(zonePoint(0.9f)); advanceEventTime(700); moveBy(Offset.Zero) }
        compose.waitForIdle()
        assertEquals(2f, engine.speeds.last(), 0f)
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(1f, engine.speeds.last(), 0f)
        assertTrue(engine.playing)
        controller.close()
    }

    @Test fun fastForwardWithoutAudioMutesAndRestoresThePausedState() {
        val engine = FakeVideoEngine()
        val controller = readyController(engine, playing = false)
        showVideo(controller, GestureSettings(videoFastForwardAudio = false))

        compose.onRoot().performTouchInput { down(zonePoint(0.9f)); advanceEventTime(700); moveBy(Offset.Zero) }
        compose.waitForIdle()
        assertEquals(0f, engine.volumes.last(), 0f)
        assertTrue(engine.playing)
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(1f, engine.volumes.last(), 0f)
        assertFalse(engine.playing)
        controller.close()
    }

    @Test fun holdingTheLeftZoneRewindsWithScrubbingUntilRelease() {
        val engine = FakeVideoEngine()
        val controller = readyController(engine, playing = true)
        controller.seekTo(8_000L)
        engine.seekPositions.clear()
        showVideo(controller, GestureSettings())

        compose.onRoot().performTouchInput { down(zonePoint(0.1f)); advanceEventTime(1_200); moveBy(Offset.Zero) }
        compose.waitForIdle()
        assertEquals(listOf(true), engine.scrubbingModeChanges)
        assertFalse(engine.playing)
        assertTrue(engine.seekPositions.size >= 2)
        assertEquals(engine.seekPositions.sortedDescending(), engine.seekPositions)
        assertTrue(engine.lastSeek < 8_000L)
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(listOf(true, false), engine.scrubbingModeChanges)
        assertTrue(engine.playing)
        controller.close()
    }

    @Test fun draggingUpDuringAFastHoldStepsTheSpeed() {
        val engine = FakeVideoEngine()
        val controller = readyController(engine, playing = true)
        showVideo(controller, GestureSettings())

        compose.onRoot().performTouchInput {
            val start = zonePoint(0.9f)
            down(start)
            advanceEventTime(700)
            moveTo(Offset(start.x, start.y - 60.dp.toPx()))
        }
        compose.waitForIdle()
        assertEquals(3f, engine.speeds.last(), 0f)
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(1f, engine.speeds.last(), 0f)
        controller.close()
    }

    @Test fun horizontalDragDuringAHoldDoesNotPageTheViewer() {
        val engine = FakeVideoEngine()
        val controller = readyController(engine, playing = true)
        val items = listOf(video(30), media(31))
        var selected: ViewerMedia? = null
        showVideo(
            controller,
            GestureSettings(videoHoldCenter = VideoHoldAction.FastForward),
            items = items,
            onSelectMedia = { selected = it },
        )

        compose.onRoot().performTouchInput {
            val start = zonePoint(0.5f)
            down(start)
            advanceEventTime(700)
            moveTo(Offset(start.x - width * 0.6f, start.y))
            advanceEventTime(200)
            up()
        }
        compose.waitForIdle()
        assertNull(selected)
        assertEquals(1f, engine.speeds.last(), 0f)
        controller.close()
    }

    @Test fun twoFingerTapTogglesPlayPause() {
        val engine = FakeVideoEngine()
        val controller = readyController(engine, playing = true)
        showVideo(controller, GestureSettings())

        compose.onRoot().performTouchInput {
            down(0, zonePoint(0.3f))
            down(1, zonePoint(0.7f))
            advanceEventTime(100)
            up(0)
            up(1)
        }
        compose.waitForIdle()
        assertEquals(1, engine.pauseCalls)
        assertFalse(engine.playing)
        controller.close()
    }

    @Test fun doubleTapThenTapInTheSameSeekZoneSeeksTwice() {
        val engine = FakeVideoEngine()
        val controller = readyController(engine, playing = true)
        showVideo(controller, GestureSettings(videoSkipSeconds = 1))

        compose.onRoot().performTouchInput {
            val point = zonePoint(0.9f)
            doubleClick(point)
            advanceEventTime(200)
            click(point)
        }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertEquals(listOf(1_000L, 2_000L), engine.seekPositions)
        controller.close()
    }

    private fun androidx.compose.ui.test.TouchInjectionScope.zonePoint(fraction: Float) =
        Offset(width * fraction, height * 0.4f)

    private fun readyController(engine: FakeVideoEngine, playing: Boolean): VideoViewerController {
        val controller = VideoViewerController(engine)
        controller.select(Uri.parse("content://media/external/video/media/40"))
        engine.listener?.onReady(10_000L, playing)
        engine.playing = playing
        return controller
    }

    private fun showVideo(
        controller: VideoViewerController,
        settings: GestureSettings,
        items: List<ViewerMedia> = listOf(video(40)),
        onSelectMedia: (ViewerMedia) -> Unit = {},
    ) {
        compose.setContent {
            LightforgeTheme {
                ViewerContent(
                    media = items.first(),
                    mediaItems = items,
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
                    onSelectMedia = onSelectMedia,
                    gestureSettings = settings,
                )
            }
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
        var playing = false
        val speeds = mutableListOf<Float>()
        val volumes = mutableListOf<Float>()
        var lastSeek = 0L
        val repeatEnabled = mutableListOf<Boolean>()
        val scrubbingModeChanges = mutableListOf<Boolean>()
        val seekPositions = mutableListOf<Long>()
        val events = mutableListOf<String>()
        override fun setMedia(uri: Uri) = Unit
        override fun prepare() = Unit
        override fun play() { playCalls++; playing = true; listener?.onPlayingChanged(true, 10_000) }
        override fun pause() {
            pauseCalls++
            playing = false
            listener?.onPlayingChanged(false, 10_000)
        }
        override fun setScrubbingModeEnabled(enabled: Boolean) {
            scrubbingModeChanges += enabled
            events += "scrubbing:$enabled"
        }
        override fun seekTo(positionMillis: Long) {
            lastSeek = positionMillis
            seekPositions += positionMillis
            events += "seek"
        }
        override fun stopAndClear() = Unit
        override fun release() = Unit
        override fun attachSurface(surfaceView: SurfaceView?) = Unit
        override fun setVolume(volume: Float) { volumes += volume }
        override fun setPlaybackSpeed(speed: Float) { speeds += speed }
        override fun setRepeatEnabled(enabled: Boolean) { repeatEnabled += enabled }
        override fun setVideoEffects(effects: List<Effect>) = Unit
        override fun currentPositionMillis(): Long = lastSeek
    }
}
