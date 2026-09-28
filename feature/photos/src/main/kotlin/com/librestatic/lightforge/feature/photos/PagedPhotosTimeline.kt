package com.librestatic.lightforge.feature.photos

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filter
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import com.librestatic.lightforge.core.model.MediaKey
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import com.librestatic.lightforge.core.designsystem.GalleryGridMetrics
import com.librestatic.lightforge.core.designsystem.GallerySpacing
import com.librestatic.lightforge.core.designsystem.MediaSelectionOverlay
import com.librestatic.lightforge.core.designsystem.RetainGridThumbnailViewport
import com.librestatic.lightforge.core.designsystem.VideoDurationBadge
import com.librestatic.lightforge.core.designsystem.lazyGridDragSelection
import com.librestatic.lightforge.core.designsystem.videoDurationDescription
import com.librestatic.lightforge.core.model.TimelineAnchor
import com.librestatic.lightforge.core.model.TimelineGrouping
import com.librestatic.lightforge.core.model.TimelineIndex
import com.librestatic.lightforge.core.model.TimelineEntry
import com.librestatic.lightforge.core.model.TimelineMedia
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.core.thumbnail.ThumbnailPrefetchCandidate
import com.librestatic.lightforge.core.thumbnail.ThumbnailRequest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** One-shot input-focus request. Real TalkBack focus requires separate device acceptance. */
data class TimelineFocusReturn(val key: MediaKey, val token: Long)

@Composable
fun AdaptivePagedPhotosTimeline(
    entries: LazyPagingItems<TimelineEntry>,
    thumbnailLoader: ThumbnailLoader,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    densityState: TimelineDensityState = rememberTimelineDensityState(),
    onMediaClick: (TimelineMedia) -> Unit = {},
    onMediaSelectionChange: (TimelineMedia, Boolean) -> Unit = { _, _ -> },
    preferredColumns: Int? = null,
    cropThumbnails: Boolean = true,
    onDensityChange: ((Int) -> Unit)? = null,
    isMediaSelected: (TimelineMedia) -> Boolean = { false },
    /** 1-based pick order for a selection whose order matters (e.g. Create PDF); null (the
     * default) keeps the plain checkmark badge. */
    selectionOrder: (TimelineMedia) -> Int? = { null },
    focusReturn: TimelineFocusReturn? = null,
    onFocusReturnConsumed: (TimelineFocusReturn) -> Unit = {},
    /** Day histogram of the whole timeline; null (the default) hides the date scrubber. */
    scrubberIndex: TimelineIndex? = null,
    onScrubberJump: (TimelineAnchor?) -> Unit = {},
) {
    var scrubbing by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier) {
        val widthDp = maxWidth.value.toInt()
        // Seed once from the persisted preference BEFORE deriving columns so the
        // first frame already shows the stored density; afterwards the density
        // state owns the value and gestures/buttons change it freely.
        densityState.seedFromPreferredColumns(preferredColumns, widthDp)
        val columns = densityState.columns(widthDp)
        if (onDensityChange != null) {
            // Persist only deliberate pinch/button changes; the automatic width-driven
            // default must not be frozen into an explicit preference.
            LaunchedEffect(columns) {
                if (densityState.userAdjusted && columns in 2..13) onDensityChange(columns)
            }
        }
        val leadingIndex = state.firstVisibleItemIndex
        densityState.prepareColumnChange(
            columns = columns,
            index = leadingIndex,
            stableKey = if (leadingIndex < entries.itemCount) {
                entries.itemSnapshotList.getOrNull(leadingIndex)?.stableKey
            } else {
                null
            },
        )
        val sizePx = with(LocalDensity.current) {
            ((maxWidth - GalleryGridMetrics.Gap * (columns - 1)) / columns).roundToPx()
        }.coerceAtLeast(1)
        PagedPhotosTimeline(
            entries = entries,
            thumbnailLoader = thumbnailLoader,
            columns = columns,
            thumbnailSizePx = sizePx,
            modifier = Modifier.fillMaxWidth(),
            state = state,
            densityState = densityState,
            onMediaClick = onMediaClick,
            onMediaSelectionChange = onMediaSelectionChange,
            cropThumbnails = cropThumbnails,
            isMediaSelected = isMediaSelected,
            selectionOrder = selectionOrder,
            focusReturn = focusReturn,
            onFocusReturnConsumed = onFocusReturnConsumed,
            scrubbing = scrubbing,
        )
        if (scrubberIndex != null && !scrubberIndex.isEmpty) {
            TimelineScrubber(
                entries = entries,
                gridState = state,
                index = scrubberIndex,
                columns = columns,
                onJump = onScrubberJump,
                onScrubbingChange = { scrubbing = it },
            )
        }
    }
}

@Composable
fun PagedPhotosTimeline(
    entries: LazyPagingItems<TimelineEntry>,
    thumbnailLoader: ThumbnailLoader,
    columns: Int,
    thumbnailSizePx: Int,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    densityState: TimelineDensityState? = null,
    onMediaClick: (TimelineMedia) -> Unit = {},
    onMediaSelectionChange: (TimelineMedia, Boolean) -> Unit = { _, _ -> },
    cropThumbnails: Boolean = true,
    isMediaSelected: (TimelineMedia) -> Boolean = { false },
    selectionOrder: (TimelineMedia) -> Int? = { null },
    focusReturn: TimelineFocusReturn? = null,
    onFocusReturnConsumed: (TimelineFocusReturn) -> Unit = {},
    /** True while the scrubber handle is dragged, which counts as the user taking over the position. */
    scrubbing: Boolean = false,
) {
    require(columns > 0 && thumbnailSizePx > 0)
    val focusSnapshot = entries.itemSnapshotList
    LaunchedEffect(focusReturn, state.isScrollInProgress, focusSnapshot, entries.loadState) {
        val request = focusReturn ?: return@LaunchedEffect
        // Never steal focus later after a user scroll or a fully loaded source disappears.
        val sourceMissing = focusSnapshot.items.isNotEmpty() &&
            entries.loadState.refresh is androidx.paging.LoadState.NotLoading &&
            (entries.loadState.prepend as? androidx.paging.LoadState.NotLoading)?.endOfPaginationReached == true &&
            (entries.loadState.append as? androidx.paging.LoadState.NotLoading)?.endOfPaginationReached == true &&
            focusSnapshot.items.none { (it as? TimelineEntry.Media)?.value?.key == request.key }
        if (state.isScrollInProgress || sourceMissing) onFocusReturnConsumed(request)
    }
    var userScrolled by rememberSaveable { mutableStateOf(false) }
    if (scrubbing && !userScrolled) userScrolled = true
    PinTimelineToNewestUntilUserScrolls(state, columns, focusReturn, userScrolled) { userScrolled = true }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = state,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            bottom = com.librestatic.lightforge.core.designsystem.galleryBottomContentPadding(),
        ),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(GalleryGridMetrics.Gap),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(GalleryGridMetrics.Gap),
        modifier = (if (densityState == null) modifier else
        modifier.timelinePinchDensity(densityState, state) { index ->
            entries.itemSnapshotList.getOrNull(index)?.stableKey
        })
            .lazyGridDragSelection(
                state = state,
                itemAtIndex = { index -> (entries.itemSnapshotList.getOrNull(index) as? TimelineEntry.Media)?.value },
                itemKey = { it.key },
                isSelected = isMediaSelected,
                onSelectionChange = { media, selected ->
                    userScrolled = true
                    onMediaSelectionChange(media, selected)
                },
            )
            .testTag("timeline_grid")
            .semantics { testTagsAsResourceId = true },
    ) {
        items(
            count = entries.itemCount,
            key = { index -> entries.itemSnapshotList.getOrNull(index)?.stableKey ?: "unloaded:$index" },
            span = { index ->
                if (entries.itemSnapshotList.getOrNull(index) is TimelineEntry.DayHeader) GridItemSpan(maxLineSpan)
                else GridItemSpan(1)
            },
            contentType = { index -> entries.itemSnapshotList.getOrNull(index)?.javaClass?.simpleName ?: "unloaded" },
        ) { index ->
            // A previous layout can still request an index after Paging publishes fewer rows.
            when (val entry = if (index in 0 until entries.itemCount) entries[index] else null) {
                is TimelineEntry.DayHeader -> TimelineDayHeader(entry.epochDay, entry.granularity)
                is TimelineEntry.Media -> TimelineThumbnail(
                    entry = entry,
                    loader = thumbnailLoader,
                    sizePx = thumbnailSizePx,
                    onClick = { onMediaClick(entry.value) },
                    onLongClick = {
                        onMediaSelectionChange(entry.value, !isMediaSelected(entry.value))
                    },
                    cropToFill = cropThumbnails,
                    selected = isMediaSelected(entry.value),
                    selectionOrder = selectionOrder(entry.value),
                    focusReturn = focusReturn?.takeIf { request ->
                        !state.isScrollInProgress && request.key == entry.value.key &&
                            state.layoutInfo.visibleItemsInfo.any { it.index == index }
                    },
                    onFocusReturnConsumed = onFocusReturnConsumed,
                )
                null -> Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
        }
    }
    RetainGridThumbnailViewport(
        state = state,
        loader = thumbnailLoader,
        columns = columns,
        itemCount = entries.itemCount,
        contentKey = entries.itemSnapshotList,
        itemAtIndex = { index ->
            val media = (entries.itemSnapshotList.getOrNull(index) as? TimelineEntry.Media)?.value ?: return@RetainGridThumbnailViewport null
            ThumbnailPrefetchCandidate(
                request = media.thumbnailRequest(thumbnailSizePx),
                sourceWidth = media.width,
                sourceHeight = media.height,
                distanceFromViewportCenter = 0,
            )
        },
    )
    if (densityState != null) {
        PreserveTimelineAnchor(
            densityState = densityState,
            gridState = state,
            columns = columns,
            itemCount = entries.itemCount,
            resolveStableKey = { stableKey ->
                val snapshot = entries.itemSnapshotList
                val loadedIndex = snapshot.items.indexOfFirst { it.stableKey == stableKey }
                if (loadedIndex < 0) null else snapshot.placeholdersBefore + loadedIndex
            },
        )
    }
}

@Composable
private fun TimelineDayHeader(epochDay: Long, granularity: TimelineGrouping) {
    val locale = LocalConfiguration.current.locales[0]
    val date = LocalDate.ofEpochDay(epochDay)
    val text = when (granularity) {
        TimelineGrouping.Day -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
        TimelineGrouping.Month -> date.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
        TimelineGrouping.Year -> date.year.toString()
    }
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(
            start = GallerySpacing.Lg,
            end = GallerySpacing.Lg,
            top = GallerySpacing.Lg,
            bottom = GallerySpacing.Sm,
        ),
    )
}

@Composable
private fun TimelineThumbnail(
    entry: TimelineEntry.Media,
    loader: ThumbnailLoader,
    sizePx: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    cropToFill: Boolean = true,
    selected: Boolean = false,
    selectionOrder: Int? = null,
    focusReturn: TimelineFocusReturn? = null,
    onFocusReturnConsumed: (TimelineFocusReturn) -> Unit = {},
) {
    val inputFocusRequester = remember(entry.value.key) { FocusRequester() }
    var placedVisible by remember(entry.value.key) { mutableStateOf(false) }
    LaunchedEffect(focusReturn, placedVisible) {
        val request = focusReturn ?: return@LaunchedEffect
        if (placedVisible) {
            // This drives the clickable's real input focus, never fabricated accessibility state.
            if (inputFocusRequester.requestFocus()) onFocusReturnConsumed(request)
        }
    }
    val stack = entry.value.stack
    val isVideo = entry.value.kind == MediaKind.Video
    val longPressLabel = stack?.let { stringResource(R.string.timeline_stack_select, it.count) }
    val contentDescription = if (stack != null) {
        stringResource(R.string.timeline_stack_description, stack.count)
    } else if (isVideo) {
        videoDurationDescription(entry.value.durationMillis)
    } else {
        stringResource(R.string.photo_thumbnail_description)
    }.let { base ->
        // One tile node: badges are folded into the label instead of separate a11y nodes (R-06).
        com.librestatic.lightforge.core.designsystem.mediaTileDescription(
            base, entry.value.isFavorite, entry.value.displayName, isVideo,
        )
    }
    val request = entry.value.thumbnailRequest(sizePx)
    val bitmap by key(request, loader) { produceState(loader.cached(request)) {
        // A stack keeps its stable grid key when its cover changes. Reset the bitmap for
        // the new source request instead of retaining a non-null previous cover forever.
        value = loader.cached(request)
        if (value == null) {
            try { value = loader.load(request) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { value = null }
        }
    } }
    val loaded = bitmap
    val cellModifier = modifier
        .fillMaxWidth()
        .aspectRatio(1f)
        .testTag("media_${entry.value.key.volumeName}_${entry.value.key.mediaStoreId}")
        .clearAndSetSemantics {
            // Clearing also drops the clickable's actions, so they are restated here.
            this.contentDescription = contentDescription
            this.selected = selected
            this.onClick { onClick(); true }
            onLongClick(label = longPressLabel) {
                onLongClick()
                true
            }
        }
        .onGloballyPositioned { coordinates ->
            placedVisible = coordinates.isAttached && !coordinates.boundsInWindow().isEmpty
        }
        .focusRequester(inputFocusRequester)
        .clickable(onClick = onClick)
    Box(cellModifier) {
        if (loaded == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
            )
        } else {
            Image(
                bitmap = loaded.asImageBitmap(),
                contentDescription = null,
                contentScale = if (cropToFill) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.fillMaxSize().testTag(
                    "timeline-image-loaded-${entry.value.key.volumeName}_${entry.value.key.mediaStoreId}"),
            )
        }
        if (!isVideo && stack == null) {
            com.librestatic.lightforge.core.designsystem.AnimatedMediaTile(
                uri = com.librestatic.lightforge.core.designsystem.mediaStoreImageUri(
                    entry.value.key.volumeName, entry.value.key.mediaStoreId,
                ),
                displayName = entry.value.displayName,
                sizePx = sizePx,
            )
        }
        com.librestatic.lightforge.core.designsystem.MediaTileBadges(
            isFavorite = entry.value.isFavorite,
            displayName = entry.value.displayName,
            isVideo = isVideo,
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
        )
        if (isVideo) {
            VideoDurationBadge(
                durationMillis = entry.value.durationMillis,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
            )
        }
        if (stack != null) {
            androidx.compose.material3.Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                    .testTag("timeline-stack-${stack.id}"),
            ) {
                androidx.compose.foundation.layout.Row(
                    Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
                ) {
                    androidx.compose.material3.Icon(com.librestatic.lightforge.core.designsystem.GalleryIcons.Album,
                        null, Modifier.size(14.dp))
                    Text(stack.count.toString(), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        MediaSelectionOverlay(selected, order = selectionOrder)
    }
}

private fun TimelineMedia.thumbnailRequest(sizePx: Int) = ThumbnailRequest(
    mediaKey = key,
    generationModified = generationModified,
    widthPx = sizePx,
    heightPx = sizePx,
)

/**
 * Keeps a freshly shown timeline on its newest row while newer rows arrive above an early
 * partial page (first grant, initial scan). The grid anchors on its first visible key, so an
 * insertion at the top would otherwise leave it scrolled down one or more days (Z-01). Any move
 * away from the top that the user did not make is undone; once the user scrolls, pinches,
 * drag-selects or returns from a viewer with a focus target, the position is theirs.
 */
@Composable
private fun PinTimelineToNewestUntilUserScrolls(
    state: LazyGridState,
    columns: Int,
    focusReturn: TimelineFocusReturn?,
    userScrolled: Boolean,
    onUserScrolled: () -> Unit,
) {
    val initialColumns = rememberSaveable { columns }
    if (!userScrolled && (focusReturn != null || columns != initialColumns)) onUserScrolled()
    // Any scroll we did not start (drag, fling, wheel, keyboard, accessibility) hands over.
    var pinning by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress && !pinning }
            .filter { it }
            .collect { onUserScrolled() }
    }
    val pinned by rememberUpdatedState(!userScrolled)
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex }
            .filter { it > 0 }
            .collect { index ->
                if (pinned) {
                    android.util.Log.i(PinLogTag, "Timeline moved to index $index without a user scroll; pinning to newest")
                    pinning = true
                    try {
                        state.scrollToItem(0)
                    } finally {
                        pinning = false
                    }
                }
            }
    }
}

private const val PinLogTag = "LightforgeLibrary"
