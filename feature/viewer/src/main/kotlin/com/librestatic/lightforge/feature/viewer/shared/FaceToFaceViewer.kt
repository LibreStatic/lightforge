package com.librestatic.lightforge.feature.viewer.shared

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GalleryExpressiveIconButton
import com.librestatic.lightforge.core.designsystem.GalleryFoldInfo
import com.librestatic.lightforge.core.designsystem.GalleryFoldOrientation
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GalleryOverlayTokens
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.model.ViewerMedia
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.feature.viewer.MediaThumbnail
import com.librestatic.lightforge.feature.viewer.PhotoLoadState
import com.librestatic.lightforge.feature.viewer.R
import com.librestatic.lightforge.feature.viewer.VideoViewerController
import com.librestatic.lightforge.feature.viewer.VideoViewerState
import com.librestatic.lightforge.feature.viewer.viewerTopScrim
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/**
 * Two people facing each other across a table: the window splits into two halves, each turned so
 * the top of its content points at the split line (see [faceToFaceLayout]). Both halves show the
 * same item with the same zoom, and a swipe or pinch in either one moves both. The split follows
 * the separating fold when [foldInfo] has one; the switch button in each half's top bar flips it.
 * Back and the close button call [onExit].
 *
 * A video draws on a surface per half that the renderer in [videoController] turns, so only the
 * top bars and neighbouring pages are Compose-rotated there.
 */
@Composable
fun FaceToFaceViewer(
    media: ViewerMedia,
    mediaItems: List<ViewerMedia>,
    photoState: PhotoLoadState?,
    adjacentPhotoStates: Map<MediaKey, PhotoLoadState.Ready> = emptyMap(),
    videoController: VideoViewerController?,
    thumbnailLoader: ThumbnailLoader?,
    foldInfo: GalleryFoldInfo?,
    onSelectMedia: (ViewerMedia) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onExit)
    val density = LocalDensity.current
    var axisSwitched by rememberSaveable { mutableStateOf(false) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var zoom by remember(media.viewerId) { mutableStateOf(SharedZoom()) }
    // Pagers key by viewerId, so a duplicate in the source list must never reach them.
    val displayedItems = remember(mediaItems, media) { mediaItems.distinctBy { it.viewerId }.ifEmpty { listOf(media) } }
    val selectedIndex = displayedItems.indexOfFirst { it.viewerId == media.viewerId }.coerceAtLeast(0)
    val videoState = videoController?.state?.collectAsState()?.value
    BoxWithConstraints(
        modifier.fillMaxSize()
            .background(Color.Black)
            .onGloballyPositioned { origin = it.positionInWindow() },
    ) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val fold = foldInfo?.toFaceToFaceFold(density, origin)
        val natural = faceToFaceLayout(width, height, fold)
        val layout = if (axisSwitched) faceToFaceLayout(width, height, fold, natural.axis.other) else natural
        listOf(layout.first, layout.second).forEachIndexed { index, half ->
            key(index) {
                FaceToFaceHalfPane(
                    half = half,
                    axis = layout.axis,
                    media = media,
                    displayedItems = displayedItems,
                    selectedIndex = selectedIndex,
                    photoState = photoState,
                    adjacentPhotoStates = adjacentPhotoStates,
                    videoController = videoController,
                    videoState = videoState,
                    thumbnailLoader = thumbnailLoader,
                    zoom = zoom,
                    onZoomChange = { zoom = it },
                    onSelectMedia = onSelectMedia,
                    onSwitchAxis = { axisSwitched = !axisSwitched },
                    onExit = onExit,
                )
            }
        }
    }
}

@Composable
private fun FaceToFaceHalfPane(
    half: FaceToFaceHalf,
    axis: SplitAxis,
    media: ViewerMedia,
    displayedItems: List<ViewerMedia>,
    selectedIndex: Int,
    photoState: PhotoLoadState?,
    adjacentPhotoStates: Map<MediaKey, PhotoLoadState.Ready>,
    videoController: VideoViewerController?,
    videoState: VideoViewerState?,
    thumbnailLoader: ThumbnailLoader?,
    zoom: SharedZoom,
    onZoomChange: (SharedZoom) -> Unit,
    onSelectMedia: (ViewerMedia) -> Unit,
    onSwitchAxis: () -> Unit,
    onExit: () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = selectedIndex) { displayedItems.size }
    val latestDisplayedItems by rememberUpdatedState(displayedItems)
    val latestMediaKey by rememberUpdatedState(media.viewerId)
    val latestOnSelectMedia by rememberUpdatedState(onSelectMedia)
    // The other half's swipe changes the selected media; this pager follows it.
    LaunchedEffect(media.viewerId, selectedIndex) {
        if (pagerState.currentPage != selectedIndex) pagerState.scrollToPage(selectedIndex)
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage to pagerState.isScrollInProgress }
            .filter { !it.second }
            .map { it.first }
            .distinctUntilChanged()
            .collect { page ->
                latestDisplayedItems.getOrNull(page)
                    ?.takeIf { it.viewerId != latestMediaKey }
                    ?.let(latestOnSelectMedia)
            }
    }
    Box(Modifier.pinnedTo(half.bounds)) {
        // The video surface cannot be turned by Compose, so it sits unrotated under the turned
        // pager (whose current page is see-through) and the renderer applies the turn.
        if (media.kind == MediaKind.Video) {
            SharedMediaPane(
                media = media,
                photoState = null,
                videoController = videoController,
                thumbnailLoader = thumbnailLoader,
                zoom = zoom,
                rotationDegrees = half.rotationDegrees,
                modifier = Modifier.fillMaxSize(),
            )
        }
        RotatedPane(half.rotationDegrees, Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    beyondViewportPageCount = 1,
                    userScrollEnabled = !zoom.isZoomed,
                    key = { displayedItems[it].viewerId },
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    val pageMedia = displayedItems[page]
                    if (pageMedia.viewerId == media.viewerId) {
                        if (media.kind == MediaKind.Video) {
                            SharedVideoPoster(media, videoState, thumbnailLoader, Modifier.fillMaxSize())
                        } else {
                            SharedMediaPane(
                                media = media,
                                photoState = photoState,
                                videoController = null,
                                thumbnailLoader = thumbnailLoader,
                                zoom = zoom,
                                rotationDegrees = 0,
                                modifier = Modifier.fillMaxSize(),
                                onZoomChange = onZoomChange,
                            )
                        }
                    } else {
                        val adjacentPhoto = pageMedia.mediaKey?.let(adjacentPhotoStates::get)
                        if (pageMedia.kind == MediaKind.Image && adjacentPhoto != null) {
                            SharedMediaPane(
                                media = pageMedia,
                                photoState = adjacentPhoto,
                                videoController = null,
                                thumbnailLoader = thumbnailLoader,
                                zoom = SharedZoom(),
                                rotationDegrees = 0,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            MediaThumbnail(
                                media = pageMedia,
                                thumbnailLoader = thumbnailLoader,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                                backgroundColor = Color.Black,
                            )
                        }
                    }
                }
                FaceToFaceTopBar(
                    position = selectedIndex + 1,
                    total = displayedItems.size,
                    axis = axis,
                    videoController = videoController.takeIf { media.kind == MediaKind.Video },
                    videoState = videoState,
                    onSwitchAxis = onSwitchAxis,
                    onExit = onExit,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }
}

/** Exit, position and the split switch, in the half's own turned frame. */
@Composable
private fun FaceToFaceTopBar(
    position: Int,
    total: Int,
    axis: SplitAxis,
    videoController: VideoViewerController?,
    videoState: VideoViewerState?,
    onSwitchAxis: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().viewerTopScrim().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val exitLabel = stringResource(R.string.viewer_face_to_face_exit)
        GalleryExpressiveIconButton(onClick = onExit) {
            Icon(GalleryIcons.Close, contentDescription = exitLabel, tint = GalleryOverlayTokens.Content)
        }
        Text(
            stringResource(R.string.viewer_face_to_face_position, position, total),
            Modifier.weight(1f).padding(horizontal = 4.dp),
            style = MaterialTheme.typography.titleMedium,
            color = GalleryOverlayTokens.Content,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        val ready = videoState as? VideoViewerState.Ready
        if (videoController != null && ready != null) {
            val label = stringResource(if (ready.isPlaying) R.string.viewer_pause else R.string.viewer_play)
            GalleryExpressiveIconButton(onClick = { if (ready.isPlaying) videoController.pause() else videoController.play() }) {
                Icon(
                    if (ready.isPlaying) GalleryIcons.Pause else GalleryIcons.Play,
                    contentDescription = label,
                    tint = GalleryOverlayTokens.Content,
                )
            }
        }
        // The icon shows the split the button switches to.
        val switchLabel = stringResource(R.string.viewer_face_to_face_switch_axis)
        GalleryExpressiveIconButton(onClick = onSwitchAxis) {
            Icon(
                if (axis == SplitAxis.Vertical) GalleryIcons.SwapVert else GalleryIcons.SwapHoriz,
                contentDescription = switchLabel,
                tint = GalleryOverlayTokens.Content,
            )
        }
    }
}

/** Places and sizes this exactly over [bounds] (window pixels) of its parent box. */
private fun Modifier.pinnedTo(bounds: Rect): Modifier = offset {
    IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt())
}.layout { measurable, _ ->
    val placeable = measurable.measure(
        Constraints.fixed(bounds.width.roundToInt().coerceAtLeast(0), bounds.height.roundToInt().coerceAtLeast(0)),
    )
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/** The separating fold as a [FaceToFaceFold] in the viewer's own pixels, given its [origin] in the window. */
internal fun GalleryFoldInfo.toFaceToFaceFold(density: Density, origin: Offset): FaceToFaceFold? {
    if (!isSeparating) return null
    return with(density) {
        when (orientation) {
            GalleryFoldOrientation.Vertical ->
                FaceToFaceFold(SplitAxis.Vertical, left.toPx() - origin.x, right.toPx() - origin.x)
            GalleryFoldOrientation.Horizontal ->
                FaceToFaceFold(SplitAxis.Horizontal, top.toPx() - origin.y, bottom.toPx() - origin.y)
        }
    }
}
