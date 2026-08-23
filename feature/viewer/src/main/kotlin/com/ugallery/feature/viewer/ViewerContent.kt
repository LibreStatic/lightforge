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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.util.VelocityTracker
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
import com.ugallery.core.preferences.GestureSettings
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.min
import android.app.Activity
import android.content.Context
import android.media.AudioManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity

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
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onOpenWith: () -> Unit,
    onSetAs: () -> Unit,
    onPrint: () -> Unit,
    onRepairDate: () -> Unit,
    onTrash: () -> Unit,
    onSelectMedia: (TimelineMedia) -> Unit,
    onContentTap: () -> Unit = {},
    slowMotionSession: HoldSlowMotionSession? = null,
    onSaveSlowMotionClip: (SlowMotionClip) -> Unit = {},
    slowMotionSaveProgress: Float? = null,
    slowMotionSaveCompletionGeneration: Long = 0,
    gestureSettings: GestureSettings = GestureSettings(),
    modifier: Modifier = Modifier,
) {
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var menuExpanded by remember { mutableStateOf(false) }
    var contentZoomed by remember(media.key) { mutableStateOf(false) }
    var slowHoldConsumed by remember(media.key) { mutableStateOf(false) }
    val slowMotionState by slowMotionSession?.state?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf<HoldSlowMotionState>(HoldSlowMotionState.Idle) }
    var zoomTapGeneration by remember(media.key) { mutableIntStateOf(0) }
    var zoomTapPosition by remember(media.key) { mutableStateOf(Offset.Zero) }
    var gestureFeedback by remember(media.key) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val activity = context as? Activity
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
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
                .pointerInput(media.key, gestureSettings, contentZoomed) {
                    var start = Offset.Zero
                    var totalY = 0f
                    var initialBrightness = 0.5f
                    var initialVolume = 0
                    val maximumVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    detectVerticalDragGestures(
                        onDragStart = { position ->
                            start = position
                            totalY = 0f
                            initialBrightness = activity?.window?.attributes?.screenBrightness
                                ?.takeIf { it >= 0f } ?: 0.5f
                            initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        },
                        onVerticalDrag = { change, amount ->
                            totalY += amount
                            val fraction = (-totalY / size.height.coerceAtLeast(1)).coerceIn(-1f, 1f)
                            val leftSide = start.x < size.width / 3f
                            val rightSide = start.x > size.width * 2f / 3f
                            when {
                                leftSide && ((media.kind == MediaKind.Video && gestureSettings.videoBrightness) || (media.kind != MediaKind.Video && gestureSettings.photoBrightness)) -> {
                                    val value = (initialBrightness + fraction).coerceIn(0.01f, 1f)
                                    activity?.window?.attributes = activity?.window?.attributes?.apply { screenBrightness = value }
                                    gestureFeedback = "☀ ${(value * 100).toInt()}%"
                                    change.consume()
                                }
                                rightSide && media.kind == MediaKind.Video && gestureSettings.videoVolume -> {
                                    val value = (initialVolume + fraction * maximumVolume).toInt().coerceIn(0, maximumVolume)
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
                                    videoController?.unmute()
                                    gestureFeedback = "♪ ${value * 100 / maximumVolume}%"
                                    change.consume()
                                }
                            }
                        },
                        onDragEnd = {
                            val center = start.x in (size.width / 3f)..(size.width * 2f / 3f)
                            if (center && !contentZoomed && gestureSettings.swipeDownToClose && totalY > size.height * 0.16f) onBack()
                            gestureFeedback = null
                        },
                        onDragCancel = { gestureFeedback = null },
                    )
                }
                .pointerInput(onContentTap, slowMotionSession, videoController, media.key, gestureSettings) {
                    detectTapGestures(
                        onPress = {
                            if (media.kind != MediaKind.Video || slowMotionSession == null || videoController == null) {
                                tryAwaitRelease()
                                return@detectTapGestures
                            }
                            tryAwaitRelease()
                            if (slowHoldConsumed) {
                                val clip = slowMotionSession.stop()
                                val resumeAt = clip?.endMillis ?: videoController.currentPositionMillis()
                                videoController.seekTo(resumeAt)
                                videoController.play()
                                slowHoldConsumed = false
                            }
                        },
                        onLongPress = {
                            if (media.kind == MediaKind.Video && slowMotionSession != null && videoController != null) {
                                slowHoldConsumed = true
                                videoController.pause()
                                slowMotionSession.start(videoController.currentPositionMillis())
                            }
                        },
                        onTap = {
                            chromeVisible = !chromeVisible
                            onContentTap()
                        },
                        onDoubleTap = { position ->
                            if (!gestureSettings.doubleTapZoom) return@detectTapGestures
                            if (media.kind == MediaKind.Video && videoController != null) {
                                val edge = size.width / 3f
                                when {
                                    position.x < edge -> videoController.seekBy(-gestureSettings.videoSkipSeconds * 1_000L)
                                    position.x > size.width - edge -> videoController.seekBy(gestureSettings.videoSkipSeconds * 1_000L)
                                    else -> { zoomTapPosition = position; zoomTapGeneration++ }
                                }
                            } else {
                                zoomTapPosition = position
                                zoomTapGeneration++
                            }
                        },
                    )
                },
        ) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !contentZoomed,
                key = { displayedItems[it].key },
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val pageMedia = displayedItems[page]
                if (pageMedia.key == media.key) {
                    if (media.kind == MediaKind.Video) {
                        VideoSurface(
                            controller = videoController,
                            aspectRatio = if (media.width > 0 && media.height > 0) {
                                media.width.toFloat() / media.height.toFloat()
                            } else null,
                            settings = gestureSettings,
                            zoomTapPosition = zoomTapPosition,
                            zoomTapGeneration = zoomTapGeneration,
                            onZoomedChange = { contentZoomed = it },
                        )
                    } else {
                        PhotoSurface(
                            state = photoState,
                            settings = gestureSettings,
                            zoomTapPosition = zoomTapPosition,
                            zoomTapGeneration = zoomTapGeneration,
                            onZoomedChange = { contentZoomed = it },
                        )
                    }
                } else {
                    MediaThumbnail(pageMedia, thumbnailLoader, Modifier.fillMaxSize())
                }
            }
        }
        gestureFeedback?.let { feedback ->
            Text(
                feedback,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        when (val slow = slowMotionState) {
            HoldSlowMotionState.Buffering -> Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Text(stringResource(R.string.viewer_slow_motion_buffering), color = Color.White)
                }
            }
            is HoldSlowMotionState.Playing -> {
                Image(
                    bitmap = slow.frame.asImageBitmap(),
                    contentDescription = stringResource(R.string.viewer_slow_motion_preview),
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    contentScale = ContentScale.Fit,
                )
                Text(
                    "0.25×",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.TopEnd)
                        .padding(top = 72.dp, end = 16.dp)
                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            is HoldSlowMotionState.ReadyToSave -> androidx.compose.material3.Button(
                onClick = { onSaveSlowMotionClip(slow.clip) },
                enabled = slowMotionSaveProgress == null,
                modifier = Modifier.align(Alignment.TopStart).padding(top = 72.dp, start = 12.dp),
            ) {
                if (slowMotionSaveProgress != null) {
                    CircularProgressIndicator(
                        progress = { slowMotionSaveProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                    )
                } else Text(stringResource(R.string.viewer_save_slow_motion_clip))
            }
            is HoldSlowMotionState.Failure -> Text(
                slow.message,
                color = Color.White,
                modifier = Modifier.align(Alignment.TopCenter)
                    .padding(top = 72.dp)
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
                    .padding(12.dp),
            )
            HoldSlowMotionState.Idle -> Unit
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
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_print)) },
                            enabled = media.kind == MediaKind.Image,
                            onClick = { menuExpanded = false; onPrint() },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_rename)) },
                            onClick = { menuExpanded = false; onRename() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_copy_to)) },
                            onClick = { menuExpanded = false; onCopy() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_move_to)) },
                            onClick = { menuExpanded = false; onMove() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_set_as)) },
                            onClick = { menuExpanded = false; onSetAs() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_open_with)) },
                            onClick = { menuExpanded = false; onOpenWith() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_repair_date)) },
                            onClick = { menuExpanded = false; onRepairDate() },
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
    settings: GestureSettings,
    zoomTapPosition: Offset,
    zoomTapGeneration: Int,
    onZoomedChange: (Boolean) -> Unit,
) {
    when (state) {
        is PhotoLoadState.Ready -> {
            val scope = rememberCoroutineScope()
            var containerSize by remember(state.drawable) { mutableStateOf(IntSize.Zero) }
            val description = stringResource(R.string.viewer_photo_description)
            val density = LocalDensity.current
            val zoom = remember(state.drawable) {
                ZoomPanState(
                    density = density,
                    maxOffsets = { candidateScale ->
                        val intrinsicWidth = state.drawable.intrinsicWidth.coerceAtLeast(1).toFloat()
                        val intrinsicHeight = state.drawable.intrinsicHeight.coerceAtLeast(1).toFloat()
                        val width = containerSize.width.toFloat().coerceAtLeast(1f)
                        val height = containerSize.height.toFloat().coerceAtLeast(1f)
                        val fit = min(width / intrinsicWidth, height / intrinsicHeight)
                        val displayedWidth = intrinsicWidth * fit * candidateScale
                        val displayedHeight = intrinsicHeight * fit * candidateScale
                        Offset(
                            ((displayedWidth - width) / 2f).coerceAtLeast(0f),
                            ((displayedHeight - height) / 2f).coerceAtLeast(0f),
                        )
                    },
                )
            }
            LaunchedEffect(zoomTapGeneration) {
                if (zoomTapGeneration == 0) return@LaunchedEffect
                val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
                if (zoom.currentScale > ZoomPanState.ZOOMED_THRESHOLD) {
                    // Keep pager locked until the settle-back finishes; an interruption freezes
                    // the scale above identity and the flag must stay consistent with it.
                    zoom.zoomTo(center, 1f, null, settings.photoMaxZoom)
                    onZoomedChange(false)
                } else {
                    onZoomedChange(true)
                    zoom.zoomTo(center, minOf(2f, settings.photoMaxZoom), zoomTapPosition, settings.photoMaxZoom)
                }
            }
            AndroidView(
                factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
                update = { it.setImageDrawable(state.drawable) },
                modifier = Modifier.fillMaxSize()
                    .onSizeChanged { containerSize = it }
                    .graphicsLayer(
                        scaleX = zoom.scale.value,
                        scaleY = zoom.scale.value,
                        translationX = zoom.offsetX.value,
                        translationY = zoom.offsetY.value,
                    )
                    .pointerInput(state.drawable, settings.pinchZoom, settings.photoMaxZoom) {
                        detectViewerTransformGestures(
                            isZoomed = { zoom.isZoomed },
                            allowPinch = settings.pinchZoom,
                            onGestureStart = { scope.launch { zoom.stopTransitions() } },
                            onGesture = { centroid, pan, factor ->
                                val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
                                scope.launch {
                                    zoom.applyGesture(center, centroid, pan, factor, 1f, settings.photoMaxZoom)
                                    onZoomedChange(zoom.isZoomed)
                                }
                            },
                            onGestureEnd = { velocity ->
                                if (!zoom.isZoomed && zoom.currentScale > 1f) {
                                    // Pinch/drag ended just above identity: settle smoothly
                                    // to 1x instead of freezing at a sub-threshold zoom.
                                    scope.launch {
                                        val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
                                        zoom.zoomTo(center, 1f, null, settings.photoMaxZoom)
                                        onZoomedChange(false)
                                    }
                                } else {
                                    zoom.fling(velocity, scope)
                                }
                            },
                        )
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
    allowPinch: Boolean = true,
    onGestureStart: () -> Unit = {},
    onGesture: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
    onGestureEnd: (velocity: Offset) -> Unit = {},
) {
    awaitEachGesture {
        var transforming = false
        var started = false
        var trackedPointerId: PointerId? = null
        val velocityTracker = VelocityTracker()
        do {
            val event = awaitPointerEvent()
            val pressedPointers = event.changes.count { it.pressed }
            if ((allowPinch && pressedPointers >= 2) || isZoomed()) transforming = true
            if (transforming) {
                if (!started) {
                    started = true
                    onGestureStart()
                }
                // Feed the tracker from ONE stable pointer so pinch velocity stays sane;
                // when that pointer lifts we stop feeding rather than jump to another
                // finger's position, which would fabricate a velocity spike.
                if (trackedPointerId == null) {
                    trackedPointerId = event.changes.firstOrNull { it.pressed }?.id
                }
                event.changes
                    .firstOrNull { it.id == trackedPointerId && it.pressed }
                    ?.let { change ->
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                    }
                val rawZoom = event.calculateZoom()
                val pan = event.calculatePan()
                if (rawZoom != 1f || pan != Offset.Zero) {
                    // Honor the pinch-zoom setting even while already zoomed: without it a
                    // two-finger gesture contributes pan only, never scale.
                    onGesture(event.calculateCentroid(), pan, if (allowPinch) rawZoom else 1f)
                }
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (started) {
            val velocity = velocityTracker.calculateVelocity()
            onGestureEnd(Offset(velocity.x, velocity.y))
        }
    }
}

@Composable
private fun VideoSurface(
    controller: VideoViewerController?,
    aspectRatio: Float?,
    settings: GestureSettings,
    zoomTapPosition: Offset,
    zoomTapGeneration: Int,
    onZoomedChange: (Boolean) -> Unit,
) {
    if (controller == null) {
        CircularProgressIndicator(Modifier.padding(24.dp))
        return
    }
    val state by controller.state.collectAsStateWithLifecycle()
    val description = stringResource(R.string.viewer_video_description)
    val scope = rememberCoroutineScope()
    var videoContainerSize by remember(controller) { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val zoom = remember(controller) {
        ZoomPanState(
            density = density,
            maxOffsets = { candidateScale ->
                val halfSpan = (candidateScale - 1f) / 2f
                Offset(videoContainerSize.width * halfSpan, videoContainerSize.height * halfSpan)
            },
        )
    }
    LaunchedEffect(zoomTapGeneration) {
        if (zoomTapGeneration == 0) return@LaunchedEffect
        val center = Offset(videoContainerSize.width / 2f, videoContainerSize.height / 2f)
        if (zoom.currentScale > ZoomPanState.ZOOMED_THRESHOLD) {
            // Keep pager locked until the settle-back finishes; an interruption freezes
            // the scale above identity and the flag must stay consistent with it.
            zoom.zoomTo(center, 1f, null, settings.videoMaxZoom)
            onZoomedChange(false)
        } else {
            onZoomedChange(true)
            zoom.zoomTo(center, minOf(2f, settings.videoMaxZoom), zoomTapPosition, settings.videoMaxZoom)
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (state !is VideoViewerState.Failure) {
            AndroidView(
                factory = { context -> android.view.SurfaceView(context).also(controller::attachSurface) },
                modifier = (aspectRatio?.let { Modifier.aspectRatio(it) } ?: Modifier.fillMaxSize())
                    .onSizeChanged { videoContainerSize = it }
                    .graphicsLayer(
                        scaleX = zoom.scale.value,
                        scaleY = zoom.scale.value,
                        translationX = zoom.offsetX.value,
                        translationY = zoom.offsetY.value,
                    )
                    .pointerInput(controller, settings.pinchZoom, settings.videoMaxZoom) {
                        detectViewerTransformGestures(
                            isZoomed = { zoom.isZoomed },
                            allowPinch = settings.pinchZoom,
                            onGestureStart = { scope.launch { zoom.stopTransitions() } },
                            onGesture = { centroid, pan, factor ->
                                val center = Offset(videoContainerSize.width / 2f, videoContainerSize.height / 2f)
                                scope.launch {
                                    zoom.applyGesture(center, centroid, pan, factor, 1f, settings.videoMaxZoom)
                                    onZoomedChange(zoom.isZoomed)
                                }
                            },
                            onGestureEnd = { velocity ->
                                if (!zoom.isZoomed && zoom.currentScale > 1f) {
                                    // Pinch/drag ended just above identity: settle smoothly
                                    // to 1x instead of freezing at a sub-threshold zoom.
                                    scope.launch {
                                        val center = Offset(videoContainerSize.width / 2f, videoContainerSize.height / 2f)
                                        zoom.zoomTo(center, 1f, null, settings.videoMaxZoom)
                                        onZoomedChange(false)
                                    }
                                } else {
                                    zoom.fling(velocity, scope)
                                }
                            },
                        )
                    }
                    .semantics { contentDescription = description },
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
