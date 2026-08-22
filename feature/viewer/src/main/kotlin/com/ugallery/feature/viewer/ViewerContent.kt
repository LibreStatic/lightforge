package com.ugallery.feature.viewer

import android.graphics.drawable.AnimatedImageDrawable
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ugallery.core.model.MediaKind
import com.ugallery.core.designsystem.GalleryAnimatedVisibility
import com.ugallery.core.designsystem.GalleryIcons
import com.ugallery.core.designsystem.GalleryMotionEdge
import java.text.DateFormat
import java.util.Date
import com.ugallery.core.model.TimelineMedia
import com.ugallery.core.thumbnail.ThumbnailLoader
import com.ugallery.core.thumbnail.ThumbnailRequest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlin.math.min

@Composable
fun ViewerContent(
    media: TimelineMedia,
    mediaItems: List<TimelineMedia>,
    photoState: PhotoLoadState?,
    videoController: VideoViewerController?,
    thumbnailLoader: ThumbnailLoader?,
    isFavorite: Boolean,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onShare: () -> Unit,
    onShareSanitized: () -> Unit,
    onDetails: () -> Unit,
    onEdit: () -> Unit,
    onTrash: () -> Unit,
    onSelectMedia: (TimelineMedia) -> Unit,
    onContentTap: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var menuExpanded by remember { mutableStateOf(false) }
    var photoZoomed by remember(media.key) { mutableStateOf(false) }
    val displayedItems = mediaItems.ifEmpty { listOf(media) }
    val selectedIndex = displayedItems.indexOfFirst { it.key == media.key }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = selectedIndex) { displayedItems.size }
    val latestDisplayedItems by rememberUpdatedState(displayedItems)
    val latestMediaKey by rememberUpdatedState(media.key)
    val latestOnSelectMedia by rememberUpdatedState(onSelectMedia)
    LaunchedEffect(media.key, selectedIndex) {
        if (pagerState.currentPage != selectedIndex) pagerState.scrollToPage(selectedIndex)
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage to pagerState.isScrollInProgress }
            .filter { !it.second }
            .map { it.first }
            .distinctUntilChanged()
            .collect { page ->
                latestDisplayedItems.getOrNull(page)
                    ?.takeIf { it.key != latestMediaKey }
                    ?.let(latestOnSelectMedia)
            }
    }
    val dateLabel = remember(media.timelineSortMillis) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(media.timelineSortMillis))
    }
    Box(
        modifier.fillMaxSize().background(Color.Black),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(onContentTap) {
                    detectTapGestures(onTap = {
                        chromeVisible = !chromeVisible
                        onContentTap()
                    })
                },
        ) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !photoZoomed,
                key = { displayedItems[it].key },
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val pageMedia = displayedItems[page]
                if (pageMedia.key == media.key) {
                    if (media.kind == MediaKind.Video) {
                        VideoSurface(videoController)
                    } else {
                        PhotoSurface(photoState, onZoomedChange = { photoZoomed = it })
                    }
                } else {
                    MediaThumbnail(pageMedia, thumbnailLoader, Modifier.fillMaxSize())
                }
            }
        }
        GalleryAnimatedVisibility(
            visible = chromeVisible,
            edge = GalleryMotionEdge.Top,
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(GalleryIcons.Back, contentDescription = stringResource(R.string.viewer_back), tint = Color.White)
                }
                Text(dateLabel, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(GalleryIcons.More, contentDescription = stringResource(R.string.viewer_more), tint = Color.White)
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_details)) },
                            onClick = { menuExpanded = false; onDetails() },
                            leadingIcon = { Icon(GalleryIcons.Info, contentDescription = null) },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_share_private)) },
                            onClick = { menuExpanded = false; onShareSanitized() },
                            leadingIcon = { Icon(GalleryIcons.Lock, contentDescription = null) },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_trash), color = MaterialTheme.colorScheme.error) },
                            onClick = { menuExpanded = false; onTrash() },
                            leadingIcon = { Icon(GalleryIcons.Trash, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        )
                    }
                }
            }
        }
        GalleryAnimatedVisibility(
            visible = chromeVisible,
            edge = GalleryMotionEdge.Bottom,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.75f))
            ) {
                ViewerFilmstrip(displayedItems, selectedIndex, thumbnailLoader, onSelectMedia)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ViewerAction(onClick = onShare, icon = GalleryIcons.Share, label = stringResource(R.string.viewer_share), modifier = Modifier.weight(1f))
                    ViewerAction(onClick = onEdit, icon = GalleryIcons.Edit, label = stringResource(R.string.viewer_edit), modifier = Modifier.weight(1f))
                    ViewerAction(onClick = onToggleFavorite, icon = GalleryIcons.Heart, label = stringResource(if (isFavorite) R.string.viewer_unfavorite else R.string.viewer_favorite), modifier = Modifier.weight(1f))
                    ViewerAction(onClick = onDetails, icon = GalleryIcons.Info, label = stringResource(R.string.viewer_details), modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ViewerAction(onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier = Modifier) {
    androidx.compose.material3.TextButton(onClick = onClick, modifier = modifier.heightIn(min = 72.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = Color.White)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ViewerFilmstrip(
    items: List<TimelineMedia>,
    selectedIndex: Int,
    thumbnailLoader: ThumbnailLoader?,
    onSelectMedia: (TimelineMedia) -> Unit,
) {
    if (items.size <= 1 || thumbnailLoader == null) return
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (selectedIndex - 2).coerceAtLeast(0))
    LaunchedEffect(selectedIndex) {
        listState.animateScrollToItem((selectedIndex - 2).coerceAtLeast(0))
    }
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
            val selected = index == selectedIndex
            val position = stringResource(R.string.viewer_thumbnail_position, index + 1, items.size)
            Box(
                Modifier
                    .size(if (selected) 66.dp else 58.dp)
                    .border(
                        width = if (selected) 3.dp else 1.dp,
                        color = if (selected) Color.White else Color.White.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(8.dp),
                    )
                    .background(Color.DarkGray, RoundedCornerShape(8.dp))
                    .pointerInput(item.key) { detectTapGestures { onSelectMedia(item) } }
                    .semantics { contentDescription = position },
            ) {
                MediaThumbnail(item, thumbnailLoader, Modifier.fillMaxSize())
                if (item.kind == MediaKind.Video) {
                    Icon(
                        GalleryIcons.Play,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.align(Alignment.Center).size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaThumbnail(
    media: TimelineMedia,
    thumbnailLoader: ThumbnailLoader?,
    modifier: Modifier = Modifier,
) {
    val request = remember(media.key, media.generationModified) {
        ThumbnailRequest(media.key, media.generationModified, 320, 320)
    }
    val bitmap by produceState(
        initialValue = thumbnailLoader?.cached(request),
        media.key,
        media.generationModified,
        thumbnailLoader,
    ) {
        if (value == null && thumbnailLoader != null) {
            value = runCatching { thumbnailLoader.load(request) }.getOrNull()
        }
    }
    val current = bitmap
    if (current == null) {
        Box(modifier.background(Color.DarkGray))
    } else {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    }
}

@Composable
private fun PhotoSurface(
    state: PhotoLoadState?,
    onZoomedChange: (Boolean) -> Unit,
) {
    when (state) {
        is PhotoLoadState.Ready -> {
            var scale by remember(state.drawable) { mutableFloatStateOf(1f) }
            var offset by remember(state.drawable) { mutableStateOf(Offset.Zero) }
            var containerSize by remember(state.drawable) { mutableStateOf(IntSize.Zero) }
            val description = stringResource(R.string.viewer_photo_description)
            fun constrained(candidate: Offset, candidateScale: Float): Offset {
                val intrinsicWidth = state.drawable.intrinsicWidth.coerceAtLeast(1).toFloat()
                val intrinsicHeight = state.drawable.intrinsicHeight.coerceAtLeast(1).toFloat()
                val width = containerSize.width.toFloat().coerceAtLeast(1f)
                val height = containerSize.height.toFloat().coerceAtLeast(1f)
                val fit = min(width / intrinsicWidth, height / intrinsicHeight)
                val displayedWidth = intrinsicWidth * fit * candidateScale
                val displayedHeight = intrinsicHeight * fit * candidateScale
                val maxX = ((displayedWidth - width) / 2f).coerceAtLeast(0f)
                val maxY = ((displayedHeight - height) / 2f).coerceAtLeast(0f)
                return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
            }
            AndroidView(
                factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
                update = { it.setImageDrawable(state.drawable) },
                modifier = Modifier.fillMaxSize()
                    .onSizeChanged { containerSize = it }
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    )
                    .pointerInput(state.drawable) {
                        detectViewerTransformGestures(
                            isZoomed = { scale > 1.01f },
                        ) { centroid, pan, zoom ->
                            val oldScale = scale
                            val newScale = (oldScale * zoom).coerceIn(1f, 8f)
                            val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
                            val focusCorrection = (centroid - center - offset) * (1f - newScale / oldScale)
                            scale = newScale
                            offset = if (newScale == 1f) Offset.Zero else {
                                constrained(offset + pan + focusCorrection, newScale)
                            }
                            onZoomedChange(newScale > 1.01f)
                        }
                    }.semantics { contentDescription = description },
            )
            DisposableEffect(state.drawable) {
                (state.drawable as? AnimatedImageDrawable)?.start()
                onDispose { (state.drawable as? AnimatedImageDrawable)?.stop() }
            }
        }
        is PhotoLoadState.Error -> Text(
            stringResource(R.string.viewer_unsupported),
            Modifier.padding(24.dp),
            color = MaterialTheme.colorScheme.error,
        )
        else -> CircularProgressIndicator(Modifier.padding(24.dp))
    }
}

private suspend fun PointerInputScope.detectViewerTransformGestures(
    isZoomed: () -> Boolean,
    onGesture: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
) {
    awaitEachGesture {
        var transforming = false
        do {
            val event = awaitPointerEvent()
            val pressedPointers = event.changes.count { it.pressed }
            if (pressedPointers >= 2 || isZoomed()) transforming = true
            if (transforming) {
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                if (zoom != 1f || pan != Offset.Zero) {
                    onGesture(event.calculateCentroid(), pan, zoom)
                }
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

@Composable
private fun VideoSurface(controller: VideoViewerController?) {
    if (controller == null) {
        CircularProgressIndicator(Modifier.padding(24.dp))
        return
    }
    val state by controller.state.collectAsStateWithLifecycle()
    val description = stringResource(R.string.viewer_video_description)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (state !is VideoViewerState.Failure) {
            AndroidView(
                factory = { context -> android.view.SurfaceView(context).also(controller::attachSurface) },
                modifier = Modifier.fillMaxSize().semantics { contentDescription = description },
            )
        }
        when (val current = state) {
            is VideoViewerState.Ready -> FilledIconButton(
                onClick = {
                    when {
                        current.isMuted -> controller.unmute()
                        current.isPlaying -> controller.pause()
                        else -> controller.play()
                    }
                },
            ) {
                Icon(
                    imageVector = if (current.isPlaying) GalleryIcons.Pause else GalleryIcons.Play,
                    contentDescription = stringResource(
                        when {
                            current.isMuted -> R.string.viewer_unmute
                            current.isPlaying -> R.string.viewer_pause
                            else -> R.string.viewer_play
                        },
                    ),
                )
            }
            is VideoViewerState.Failure -> Text(
                stringResource(R.string.viewer_unsupported),
                Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.error,
            )
            VideoViewerState.Idle,
            is VideoViewerState.Loading -> CircularProgressIndicator()
            VideoViewerState.Released -> Unit
        }
    }
    DisposableEffect(controller) { onDispose { controller.attachSurface(null) } }
}
