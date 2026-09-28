package com.librestatic.lightforge.feature.photos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.librestatic.lightforge.core.designsystem.galleryBottomContentPadding
import com.librestatic.lightforge.core.model.TimelineAnchor
import com.librestatic.lightforge.core.model.TimelineEntry
import com.librestatic.lightforge.core.model.TimelineIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

private val HandleGripWidth = 48.dp
private val HandleGripHeight = 56.dp
private val HandleWidth = 28.dp
private val HandleHeight = 44.dp
private val TickPanelWidth = 60.dp
private val TickMinGap = 22.dp
private val TrackTopInset = 4.dp

private const val NewestFraction = 0.0005f
private const val SettleMillis = 180L
private const val LingerMillis = 1500L
private const val RefreshStartMillis = 600L
private const val RefreshFinishMillis = 4000L
private const val MaxDayWalk = 4000

private class PendingJump(val targetEpochDay: Long?)

private class RestingPosition(val epochDay: Long, val fraction: Float)

@Stable
private class ScrubberController(
    private val gridState: LazyGridState,
    private val entries: State<LazyPagingItems<TimelineEntry>>,
    private val index: State<TimelineIndex>,
    private val onJump: State<(TimelineAnchor?) -> Unit>,
    private val onScrubbingChange: State<(Boolean) -> Unit>,
    private val scope: CoroutineScope,
    private val zone: ZoneId,
) {
    var scrubbing by mutableStateOf(false)
        private set
    var dragFraction by mutableFloatStateOf(0f)
        private set
    var committedFraction by mutableFloatStateOf(0f)
        private set
    var pending by mutableStateOf<PendingJump?>(null)
        private set

    fun beginScrub(fromFraction: Float) {
        dragFraction = fromFraction
        committedFraction = fromFraction
        scrubbing = true
        onScrubbingChange.value(true)
    }

    fun dragBy(deltaPx: Float, travelPx: Float) {
        dragFraction = scrubberFraction(scrubberHandleOffset(dragFraction, travelPx) + deltaPx, travelPx)
    }

    fun endScrub() {
        if (!scrubbing) return
        scrubbing = false
        onScrubbingChange.value(false)
        if (dragFraction != committedFraction) commit(dragFraction)
    }

    fun seekTo(fraction: Float) {
        dragFraction = fraction.coerceIn(0f, 1f)
        commit(dragFraction)
    }

    fun commit(fraction: Float) {
        committedFraction = fraction
        val list = entries.value
        val target = if (fraction <= NewestFraction) null else index.value.positionAt(fraction)?.bucket?.epochDay
        if (target == null) {
            val atNewest = (list.loadState.prepend as? LoadState.NotLoading)?.endOfPaginationReached == true
            if (atNewest) scope.launch { gridState.scrollToItem(0) } else startJump(null)
            return
        }
        val loaded = loadedIndexOf(list, target)
        if (loaded != null) scope.launch { gridState.scrollToItem(loaded) } else startJump(target)
    }

    private fun startJump(target: Long?) {
        pending = PendingJump(target)
        onJump.value(target?.let(::TimelineAnchor))
    }

    /** Waits for the restarted pager's first page, then puts the grid on its first row. */
    suspend fun settlePending(jump: PendingJump) {
        val list = entries.value
        withTimeoutOrNull(RefreshStartMillis) {
            snapshotFlow { list.loadState.refresh is LoadState.Loading }.first { it }
        }
        withTimeoutOrNull(RefreshFinishMillis) {
            snapshotFlow { list.loadState.refresh is LoadState.NotLoading }.first { it }
        }
        withFrameNanos { }
        gridState.scrollToItem(0)
        if (pending === jump) pending = null
    }

    fun restingPosition(): RestingPosition? {
        val snapshot = entries.value.itemSnapshotList
        val first = gridState.firstVisibleItemIndex
        var i = first
        while (i < snapshot.size && snapshot[i] !is TimelineEntry.Media && i - first < 8) i++
        val media = snapshot.getOrNull(i) as? TimelineEntry.Media
        val header = snapshot.getOrNull(first) as? TimelineEntry.DayHeader
        val day = media?.let(::dayOf) ?: header?.epochDay ?: return null
        var before = 0
        if (media != null) {
            var j = i - 1
            while (j >= 0 && before < MaxDayWalk) {
                val entry = snapshot[j]
                if (entry is TimelineEntry.Media) {
                    if (dayOf(entry) != day) break
                    before++
                }
                j--
            }
        }
        val timeline = index.value
        val bucket = timeline.days.getOrNull(timeline.bucketIndexOf(day)) ?: return null
        return RestingPosition(day, timeline.fractionOf(day, before.toFloat() / bucket.count))
    }

    private fun loadedIndexOf(list: LazyPagingItems<TimelineEntry>, epochDay: Long): Int? {
        val snapshot = list.itemSnapshotList
        for (i in snapshot.indices) {
            val entry = snapshot[i]
            if (entry is TimelineEntry.Media && dayOf(entry) == epochDay) {
                return if (i > 0 && snapshot[i - 1] is TimelineEntry.DayHeader) i - 1 else i
            }
        }
        return null
    }

    private fun dayOf(media: TimelineEntry.Media): Long =
        Instant.ofEpochMilli(media.value.timelineSortMillis).atZone(zone).toLocalDate().toEpochDay()
}


/**
 * Google Photos-style timeline scrubber. While the grid scrolls, a date pill and a handle show
 * where the viewport sits in the whole library; dragging the handle shows year ticks and a month
 * bubble, hides the tiles behind a skeleton, and jumps to the pointed day once the finger slows
 * or lifts.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun TimelineScrubber(
    entries: LazyPagingItems<TimelineEntry>,
    gridState: LazyGridState,
    index: TimelineIndex,
    columns: Int,
    onJump: (TimelineAnchor?) -> Unit,
    onScrubbingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }
    val entriesState = rememberUpdatedState(entries)
    val indexState = rememberUpdatedState(index)
    val jumpState = rememberUpdatedState(onJump)
    val scrubbingState = rememberUpdatedState(onScrubbingChange)
    val controller = remember(gridState) {
        ScrubberController(gridState, entriesState, indexState, jumpState, scrubbingState, scope, zone)
    }
    val resting by remember(controller) { derivedStateOf { controller.restingPosition() } }

    LaunchedEffect(controller.scrubbing) {
        if (controller.scrubbing) {
            snapshotFlow { controller.dragFraction }
                .debounce(SettleMillis)
                .collect { if (it != controller.committedFraction) controller.commit(it) }
        }
    }
    val pending = controller.pending
    LaunchedEffect(pending) { if (pending != null) controller.settlePending(pending) }

    val active = gridState.isScrollInProgress || controller.scrubbing || pending != null
    var lingering by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (active) {
            lingering = true
        } else {
            delay(LingerMillis)
            lingering = false
        }
    }
    val visible = active || lingering

    val handleFraction = when {
        controller.scrubbing -> controller.dragFraction
        pending != null -> controller.committedFraction
        else -> resting?.fraction ?: controller.committedFraction
    }
    val shownDay = when {
        controller.scrubbing -> index.positionAt(controller.dragFraction)?.bucket?.epochDay
        pending != null -> index.positionAt(controller.committedFraction)?.bucket?.epochDay
        else -> resting?.epochDay
    }
    val showSkeleton = pending != null ||
        (controller.scrubbing && controller.dragFraction != controller.committedFraction)

    val locale = LocalConfiguration.current.locales[0]
    val bestPattern: (String) -> String = remember(locale) {
        { skeleton -> android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton) }
    }
    val todayLabel = stringResource(R.string.scrubber_today)
    val yesterdayLabel = stringResource(R.string.scrubber_yesterday)
    val pillText = shownDay?.let {
        formatScrubberDate(it, LocalDate.now(), locale, todayLabel, yesterdayLabel, bestPattern)
    }
    val monthText = shownDay?.let { formatScrubberMonth(it, locale, bestPattern) }

    val haptics = LocalHapticFeedback.current
    val monthKey = shownDay?.let { YearMonth.from(LocalDate.ofEpochDay(it)) }
    LaunchedEffect(monthKey) {
        if (controller.scrubbing && monthKey != null) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    val density = LocalDensity.current
    var trackPx by remember { mutableIntStateOf(0) }
    val gripHeightPx = with(density) { HandleGripHeight.toPx() }
    val travelPx = (trackPx - gripHeightPx).coerceAtLeast(0f)
    val handleOffsetPx = scrubberHandleOffset(handleFraction, travelPx)
    val handleFractionState = rememberUpdatedState(handleFraction)

    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = showSkeleton,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.testTag("timeline_scrub_skeleton"),
        ) {
            ScrubSkeleton(columns)
        }
        AnimatedVisibility(
            visible = visible && pillText != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = CircleShape,
                shadowElevation = 2.dp,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .widthIn(max = 280.dp)
                    .testTag("timeline_scrub_date")
                    .clearAndSetSemantics { },
            ) {
                Text(
                    text = pillText.orEmpty(),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = TrackTopInset, bottom = galleryBottomContentPadding())
                .onSizeChanged { trackPx = it.height },
        ) {
            AnimatedVisibility(
                visible = controller.scrubbing,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopEnd).padding(end = HandleGripWidth),
            ) {
                YearTicks(
                    ticks = remember(index) { index.yearTicks() },
                    travelPx = travelPx,
                    gripHeightPx = gripHeightPx,
                    trackPx = trackPx,
                )
            }
            AnimatedVisibility(
                visible = controller.scrubbing && monthText != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = HandleGripWidth + TickPanelWidth + 8.dp)
                    .offset { IntOffset(0, (handleOffsetPx + (gripHeightPx - with(density) { 36.dp.toPx() }) / 2f).toInt()) },
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = CircleShape,
                    shadowElevation = 2.dp,
                    modifier = Modifier.testTag("timeline_scrub_month").clearAndSetSemantics { },
                ) {
                    Text(
                        text = monthText.orEmpty(),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            val handleDescription = stringResource(R.string.scrubber_handle_description)
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, handleOffsetPx.toInt()) },
            ) {
                Box(
                    Modifier
                        .size(HandleGripWidth, HandleGripHeight)
                        .testTag("timeline_scrub_handle")
                        .semantics(mergeDescendants = true) {
                            contentDescription = handleDescription
                            stateDescription = monthText.orEmpty()
                            progressBarRangeInfo = ProgressBarRangeInfo(handleFraction, 0f..1f)
                            setProgress { target ->
                                controller.seekTo(target)
                                true
                            }
                        }
                        .pointerInput(controller, travelPx) {
                            detectVerticalDragGestures(
                                onDragStart = { controller.beginScrub(handleFractionState.value) },
                                onDragEnd = { controller.endScrub() },
                                onDragCancel = { controller.endScrub() },
                                onVerticalDrag = { change, delta ->
                                    change.consume()
                                    controller.dragBy(delta, travelPx)
                                },
                            )
                        },
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50),
                        shadowElevation = 2.dp,
                        modifier = Modifier.size(HandleWidth, HandleHeight),
                    ) {
                        HandleArrows()
                    }
                }
            }
        }
    }
}

@Composable
private fun HandleArrows() {
    val color = LocalContentColor.current
    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val half = size.width * 0.2f
        val gap = size.width * 0.12f
        val up = Path().apply {
            moveTo(cx - half, cy - gap)
            lineTo(cx + half, cy - gap)
            lineTo(cx, cy - gap - half * 1.4f)
            close()
        }
        val down = Path().apply {
            moveTo(cx - half, cy + gap)
            lineTo(cx + half, cy + gap)
            lineTo(cx, cy + gap + half * 1.4f)
            close()
        }
        drawPath(up, color)
        drawPath(down, color)
    }
}

@Composable
private fun YearTicks(
    ticks: List<com.librestatic.lightforge.core.model.TimelineYearTick>,
    travelPx: Float,
    gripHeightPx: Float,
    trackPx: Int,
) {
    val density = LocalDensity.current
    val placed = remember(ticks, travelPx) {
        layoutYearTicks(ticks, travelPx, with(density) { TickMinGap.toPx() })
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 2.dp,
        modifier = Modifier
            .size(TickPanelWidth, with(density) { trackPx.toDp() })
            .testTag("timeline_scrub_years")
            .clearAndSetSemantics { },
    ) {
        Box(Modifier.fillMaxSize()) {
            val labelHalf = with(density) { 9.dp.toPx() }
            placed.forEach { tick ->
                Text(
                    text = tick.year.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset { IntOffset(0, (gripHeightPx / 2f + tick.y - labelHalf).toInt()) },
                )
            }
        }
    }
}

@Composable
private fun ScrubSkeleton(columns: Int) {
    val background = MaterialTheme.colorScheme.background
    val cell = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxSize().background(background)) {
        val gap = 2.dp.toPx()
        val count = columns.coerceAtLeast(1)
        val side = (size.width - gap * (count - 1)) / count
        var y = 0f
        while (y < size.height) {
            for (column in 0 until count) {
                drawRect(
                    color = cell,
                    topLeft = Offset(column * (side + gap), y),
                    size = androidx.compose.ui.geometry.Size(side, side),
                )
            }
            y += side + gap
        }
    }
}
