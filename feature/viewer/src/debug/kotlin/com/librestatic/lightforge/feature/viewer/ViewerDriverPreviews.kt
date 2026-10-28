package com.librestatic.lightforge.feature.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.CancellationSignal
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import com.librestatic.lightforge.core.designsystem.GalleryFoldInfo
import com.librestatic.lightforge.core.designsystem.GalleryFoldOrientation
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.frameinterpolation.FrameInterpolationBackend
import com.librestatic.lightforge.core.designsystem.galleryAdaptiveLayoutInfo
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.model.ViewerMedia
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.feature.details.DetailsContent
import com.librestatic.lightforge.feature.details.DetailsPreviewData
import com.librestatic.lightforge.feature.viewer.shared.FaceToFaceViewer
import com.librestatic.lightforge.feature.viewer.shared.OuterDisplayContent
import com.librestatic.lightforge.feature.viewer.shared.SharedZoom

// Debug-only, zero-argument entry points for tools/compose-driver and Android Studio previews.
// They feed fake media into the real ViewerContent inside the real ViewerDetailsScaffold, so the
// swipe-up gesture, the Details sheet/side panel and the chrome all run as in the app.

/** A photo with its chrome and thumbnail strip; Details closed. */
@Preview
@Composable
fun ViewerPhotoPreview() = ViewerPreviewFrame(selected = 13, detailsOpen = false)

/** The same photo with Details open: a half sheet on phones, a side panel on wide windows. */
@Preview
@Composable
fun ViewerPhotoDetailsPreview() = ViewerPreviewFrame(selected = 13, detailsOpen = true)

/** A paused video with the immersive controls. */
@Preview
@Composable
fun ViewerVideoPreview() = ViewerPreviewFrame(selected = 12, detailsOpen = false)

/** A motion photo: its play button shares the top bar with the date. */
@Preview
@Composable
fun ViewerMotionPhotoPreview() = ViewerPreviewFrame(selected = 13, detailsOpen = false, motion = true)

/** Face to face on a foldable with a vertical hinge: left and right halves around the gap (w673dp-h841dp). */
@Preview
@Composable
fun ViewerFaceToFaceVerticalHingePreview() = FaceToFacePreviewFrame(selected = 13, hinge = PreviewHinge.Vertical)

/** Face to face without a fold on a portrait window: top and bottom halves, the top one upside down. */
@Preview
@Composable
fun ViewerFaceToFaceHorizontalPreview() = FaceToFacePreviewFrame(selected = 13, hinge = PreviewHinge.None)

/** Face to face on a video: surfaces per half are mirror outputs, here shown with their posters. */
@Preview
@Composable
fun ViewerFaceToFaceVideoPreview() = FaceToFacePreviewFrame(selected = 12, hinge = PreviewHinge.Vertical)

/** What the outer screen shows: the photo fitted full screen at the presenter's zoom (w411dp-h960dp). */
@Preview
@Composable
fun ViewerOuterDisplayPanePreview() = LightforgeTheme {
    val photo = remember { PhotoLoadState.Thumbnail(fakePhoto(1200, 900, 13)) }
    OuterDisplayContent(
        media = previewItems[13],
        photoState = photo,
        videoController = null,
        thumbnailLoader = null,
        zoom = SharedZoom(scale = 1.6f, panX = 0.1f, panY = 0f),
    )
}

/** The viewer while the media is mirrored on the outer screen: the chip sits in the top bar. */
@Preview
@Composable
fun ViewerSharingIndicatorPreview() = ViewerPreviewFrame(selected = 13, detailsOpen = false, sharing = true)

/** An item that cannot be opened (expired grant, moved or deleted file). */
@Preview
@Composable
fun ViewerMediaUnavailablePreview() = LightforgeTheme { ViewerMediaUnavailable(onClose = {}) }

/** A photo opened from the trash: the pill offers Restore and Delete permanently only. */
@Preview
@Composable
fun ViewerTrashPreview() = ViewerPreviewFrame(selected = 13, detailsOpen = false, trashed = true)

/**
 * The video with the save offer left by a finished hold. The overlay is stacked over the real
 * viewer because the session state is not fakeable; production draws it in the same place.
 */
@Preview
@Composable
fun ViewerSlowMotionSavePreview() = ViewerPreviewFrame(selected = 12, detailsOpen = false) {
    SlowMotionOverlay(
        state = HoldSlowMotionState.ReadyToSave(SlowMotionClip(Uri.EMPTY, 4_000, 6_000)),
        saveProgress = null,
        onSave = {},
        onDismiss = {},
        onCancelSave = {},
    )
}

/** The save offer while the clip is being written: a spinner and Cancel. */
@Preview
@Composable
fun ViewerSlowMotionSavingPreview() = ViewerPreviewFrame(selected = 12, detailsOpen = false) {
    SlowMotionOverlay(
        state = HoldSlowMotionState.ReadyToSave(SlowMotionClip(Uri.EMPTY, 4_000, 6_000)),
        saveProgress = 0.4f,
        onSave = {},
        onDismiss = {},
        onCancelSave = {},
    )
}

/** The 0.25× preview frame with its speed badge under the top bar. */
@Preview
@Composable
fun ViewerSlowMotionPlayingPreview() = ViewerPreviewFrame(selected = 12, detailsOpen = false) {
    val frame = remember { fakePhoto(320, 180, 12) }
    SlowMotionOverlay(
        state = HoldSlowMotionState.Playing(frame, 5_000, FrameInterpolationBackend.Vulkan),
        saveProgress = null,
        onSave = {},
        onDismiss = {},
        onCancelSave = {},
    )
}

/** The 2x badge of a fast-forward hold. */
@Preview
@Composable
fun ViewerFastForwardPreview() = ViewerPreviewFrame(selected = 12, detailsOpen = false) {
    FastHoldPill(FastHoldIndicator(forward = true, speed = 2f))
}

/** The -3x badge of a rewind hold after one live speed step. */
@Preview
@Composable
fun ViewerFastRewindPreview() = ViewerPreviewFrame(selected = 12, detailsOpen = false) {
    FastHoldPill(FastHoldIndicator(forward = false, speed = 3f))
}

/** Slow-motion shuttle dragged left of its anchor: the preview plays in reverse at -0.25x. */
@Preview
@Composable
fun ViewerSlowMotionShuttleReversePreview() = ViewerPreviewFrame(selected = 12, detailsOpen = false) {
    val frame = remember { fakePhoto(320, 180, 12) }
    SlowMotionOverlay(
        state = HoldSlowMotionState.Playing(frame, 5_000, FrameInterpolationBackend.Vulkan, rate = -0.25f),
        saveProgress = null,
        onSave = {},
        onDismiss = {},
        onCancelSave = {},
    )
}

/** Three consecutive double-tap seeks to the right: the running total at the right edge. */
@Preview
@Composable
fun ViewerSeekAccumulationPreview() = ViewerPreviewFrame(selected = 12, detailsOpen = false) {
    SeekAccumulationIndicator(SeekAccumulation(direction = 1, totalSeconds = 30, lastTapMillis = 0L))
}

private data class PreviewMedia(
    val id: Long,
    override val kind: MediaKind,
) : ViewerMedia {
    override val viewerId: String get() = "preview:$id"
    override val mediaKey: MediaKey get() = MediaKey("external_primary", id)
    override val generationModified: Long get() = 1L
    override val timelineSortMillis: Long get() = 1_788_465_360_000 - id * 3_600_000
    override val width: Int get() = if (kind == MediaKind.Video) 1920 else 4032
    override val height: Int get() = if (kind == MediaKind.Video) 1080 else 3024
    override val durationMillis: Long get() = if (kind == MediaKind.Video) 13_400 else 0
    override val isFavorite: Boolean get() = id % 5 == 0L
    override val displayName: String get() = if (kind == MediaKind.Video) "VID_$id.mp4" else "PXL_$id.jpg"
}

private val previewItems: List<ViewerMedia> = (1L..80L).map { id ->
    PreviewMedia(id, if (id % 7 == 6L) MediaKind.Video else MediaKind.Image)
}

/** Stand-in pixels for media: a diagonal two-tone gradient, varied per item. */
private fun fakePhoto(width: Int, height: Int, seed: Long): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val hue = (seed * 47 % 360).toFloat()
    val start = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.85f))
    val end = android.graphics.Color.HSVToColor(floatArrayOf((hue + 60f) % 360f, 0.7f, 0.35f))
    val paint = Paint().apply {
        shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), start, end, Shader.TileMode.CLAMP)
    }
    Canvas(bitmap).drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    return bitmap
}

/** Paused at 4 s of a 13 s clip; play/pause only flips the reported state. */
@androidx.annotation.OptIn(UnstableApi::class)
private class PreviewVideoEngine : VideoEngine {
    override var listener: VideoEngine.Listener? = null
    private var playing = false
    override fun setMedia(uri: Uri) = Unit
    override fun prepare() {
        listener?.onReady(13_400, false)
        listener?.onVideoAspectRatioChanged(16f / 9f)
    }
    override fun play() { playing = true; listener?.onPlayingChanged(true, 13_400) }
    override fun pause() { playing = false; listener?.onPlayingChanged(false, 13_400) }
    override fun setScrubbingModeEnabled(enabled: Boolean) = Unit
    override fun seekTo(positionMillis: Long) = Unit
    override fun stopAndClear() = Unit
    override fun release() = Unit
    override fun attachSurface(surfaceView: SurfaceView?) = Unit
    override fun setVolume(volume: Float) = Unit
    override fun setRepeatEnabled(enabled: Boolean) = Unit
    override fun setVideoEffects(effects: List<Effect>) = Unit
    override fun currentPositionMillis(): Long = 4_000
}

@Composable
private fun ViewerPreviewFrame(
    selected: Int,
    detailsOpen: Boolean,
    motion: Boolean = false,
    trashed: Boolean = false,
    sharing: Boolean = false,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
) = LightforgeTheme {
    val items = previewItems
    var current by remember { mutableStateOf(items[selected]) }
    var favorite by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(detailsOpen) }
    val adaptive = galleryAdaptiveLayoutInfo(LocalConfiguration.current.screenWidthDp.dp)
    val thumbnails = remember {
        ThumbnailLoader(
            source = { request, _ -> fakePhoto(request.widthPx, request.heightPx, request.mediaKey.mediaStoreId) },
            maxCacheBytes = 32L * 1024 * 1024,
            threadCount = 1,
        )
    }
    val video = remember(current.viewerId) {
        if (current.kind != MediaKind.Video) null
        else VideoViewerController(PreviewVideoEngine()).also { it.select(Uri.parse("content://preview/${current.viewerId}")) }
    }
    DisposableEffect(video) { onDispose { video?.close() } }
    val photo = remember(current.viewerId) {
        PhotoLoadState.Thumbnail(fakePhoto(1200, 900, current.mediaKey?.mediaStoreId ?: 0))
    }
    val detailsState = rememberViewerDetailsState()
    ViewerDetailsScaffold(
        open = showDetails,
        onOpen = { showDetails = true },
        onClose = { showDetails = false },
        sidePanel = adaptive.supportsTwoPane,
        state = detailsState,
        viewer = {
            Box(Modifier.fillMaxSize()) {
                ViewerContent(
                    media = current,
                    mediaItems = items,
                    photoState = if (current.kind == MediaKind.Image) photo else null,
                    videoController = video,
                    thumbnailLoader = thumbnails,
                    isFavorite = favorite,
                    onBack = {},
                    onToggleFavorite = if (trashed) null else ({ favorite = !favorite }),
                    onShare = if (trashed) null else ({}),
                    onShareSanitized = if (trashed) null else ({}),
                    onDetails = { showDetails = true },
                    onEdit = if (trashed) null else ({}),
                    onMotionPhoto = if (motion) ({}) else null,
                    motionPhotoLabel = if (motion) "Motion" else null,
                    onRename = if (trashed) null else ({}),
                    onCopy = if (trashed) null else ({}),
                    onMove = if (trashed) null else ({}),
                    onOpenWith = if (trashed) null else ({}),
                    onSetAs = if (trashed) null else ({}),
                    onPrint = if (trashed) null else ({}),
                    onRepairDate = if (trashed) null else ({}),
                    onTrash = {},
                    trashActionLabel = if (trashed) "Restore" else null,
                    onDelete = if (trashed) ({}) else null,
                    deleteActionLabel = if (trashed) "Delete permanently" else null,
                    onSelectMedia = { current = it },
                    detailsState = detailsState,
                    onShowOnOuterDisplay = if (trashed) null else ({}),
                    onFaceToFace = if (trashed) null else ({}),
                    outerDisplayActive = sharing,
                    onStopOuterDisplay = if (sharing) ({}) else null,
                    modifier = Modifier.fillMaxSize(),
                )
                overlay?.invoke(this)
            }
        },
        details = { padding ->
            val isVideo = current.kind == MediaKind.Video
            DetailsContent(
                cheap = if (isVideo) DetailsPreviewData.video else DetailsPreviewData.photo,
                exif = if (isVideo) null else DetailsPreviewData.withLocation,
                isExifLoading = false,
                onLoadExif = {},
                modifier = Modifier.fillMaxSize(),
                placeName = "Buenos Aires",
                showTitle = false,
                contentPadding = padding,
            )
        },
    )
}

private enum class PreviewHinge { None, Vertical }

/** The real face-to-face viewer over fake media; a vertical hinge is a 13 dp gap at the window centre. */
@Composable
private fun FaceToFacePreviewFrame(selected: Int, hinge: PreviewHinge) = LightforgeTheme {
    val items = previewItems
    var current by remember { mutableStateOf(items[selected]) }
    val configuration = LocalConfiguration.current
    val fold = when (hinge) {
        PreviewHinge.None -> null
        PreviewHinge.Vertical -> {
            val center = configuration.screenWidthDp / 2f
            GalleryFoldInfo(
                orientation = GalleryFoldOrientation.Vertical,
                isSeparating = true,
                left = (center - 6.5f).dp,
                top = 0.dp,
                right = (center + 6.5f).dp,
                bottom = configuration.screenHeightDp.dp,
            )
        }
    }
    val thumbnails = remember {
        ThumbnailLoader(
            source = { request, _ -> fakePhoto(request.widthPx, request.heightPx, request.mediaKey.mediaStoreId) },
            maxCacheBytes = 32L * 1024 * 1024,
            threadCount = 1,
        )
    }
    val video = remember(current.viewerId) {
        if (current.kind != MediaKind.Video) null
        else VideoViewerController(PreviewVideoEngine()).also { it.select(Uri.parse("content://preview/${current.viewerId}")) }
    }
    DisposableEffect(video) { onDispose { video?.close() } }
    val photo = remember(current.viewerId) {
        PhotoLoadState.Thumbnail(fakePhoto(1200, 900, current.mediaKey?.mediaStoreId ?: 0))
    }
    FaceToFaceViewer(
        media = current,
        mediaItems = items,
        photoState = if (current.kind == MediaKind.Image) photo else null,
        videoController = video,
        thumbnailLoader = thumbnails,
        foldInfo = fold,
        onSelectMedia = { current = it },
        onExit = {},
        modifier = Modifier.fillMaxSize(),
    )
}
