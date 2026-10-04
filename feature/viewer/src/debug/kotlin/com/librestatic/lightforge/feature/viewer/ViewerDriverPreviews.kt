package com.librestatic.lightforge.feature.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.CancellationSignal
import android.view.SurfaceView
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
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.designsystem.galleryAdaptiveLayoutInfo
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.model.ViewerMedia
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.feature.details.DetailsContent
import com.librestatic.lightforge.feature.details.DetailsPreviewData

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
private fun ViewerPreviewFrame(selected: Int, detailsOpen: Boolean, motion: Boolean = false) = LightforgeTheme {
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
            ViewerContent(
                media = current,
                mediaItems = items,
                photoState = if (current.kind == MediaKind.Image) photo else null,
                videoController = video,
                thumbnailLoader = thumbnails,
                isFavorite = favorite,
                onBack = {},
                onToggleFavorite = { favorite = !favorite },
                onShare = {},
                onShareSanitized = {},
                onDetails = { showDetails = true },
                onEdit = {},
                onMotionPhoto = if (motion) ({}) else null,
                motionPhotoLabel = if (motion) "Motion" else null,
                onRename = {},
                onCopy = {},
                onMove = {},
                onOpenWith = {},
                onSetAs = {},
                onPrint = {},
                onRepairDate = {},
                onTrash = {},
                onSelectMedia = { current = it },
                detailsState = detailsState,
                modifier = Modifier.fillMaxSize(),
            )
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
