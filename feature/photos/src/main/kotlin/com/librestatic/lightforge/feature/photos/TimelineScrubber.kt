package com.librestatic.lightforge.feature.photos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.ItemSnapshotList
import androidx.paging.compose.LazyPagingItems
import com.librestatic.lightforge.core.designsystem.galleryBottomContentPadding
import com.librestatic.lightforge.core.model.TimelineAnchor
import com.librestatic.lightforge.core.model.TimelineEntry
import com.librestatic.lightforge.core.model.TimelineIndex
import com.librestatic.lightforge.core.model.TimelineIndexPosition
import com.librestatic.lightforge.core.model.TimelineYearTick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

// Sizes measured on Google Photos (phone, 411 dp wide): a 48 dp handle half-tucked into the edge,
// 26 dp year pills and a 40 dp month bubble whose right edges line up 56 dp from the screen edge.
private val HandleGripWidth = 56.dp
private val HandleGripHeight = 72.dp
private val HandleWidth = 36.dp
private val HandleHeight = 48.dp
private val LabelEndInset = 64.dp
private val TickMinGap = 30.dp
private val TrackTopInset = 72.dp
private val TrackBottomInset = 8.dp
private val FloatingElevation = 3.dp
private val GrabbedElevation = 8.dp

/** How far a scrub has to turn back before the grid follows; a resting finger trembles less. */
private val ScrubDeadband = 4.dp

private const val NewestFraction = 0.0005f
private const val LingerMillis = 1500L

/** How long the finger has to rest before the pointed rows start decoding thumbnails. */
private const val SettleMillis = 150L
private const val JumpLandMillis = 1500L

/** First and last loaded row of one day in the current page snapshot. */
private class LoadedDay(val first: Int, var last: Int)

/** Where each loaded day sits in a snapshot; rebuilt only when Paging publishes new rows. */
private class LoadedDays(val snapshot: ItemSnapshotList<TimelineEntry>, zone: ZoneId) {
    val days = HashMap<Long, LoadedDay>()
    val dayAt = LongArray(snapshot.size) { Long.MIN_VALUE }

    init {
        var previousDay = Long.MIN_VALUE
        for (i in snapshot.indices) {
            val media = snapshot[i] as? TimelineEntry.Media ?: continue
            val day = Instant.ofEpochMilli(media.value.timelineSortMillis).atZone(zone).toLocalDate().toEpochDay()
            dayAt[i] = day
            if (day == previousDay) days.getValue(day).last = i else days.getOrPut(day) { LoadedDay(i, i) }.last = i
            previousDay = day
        }
    }
}

private class RestingPosition(val epochDay: Long, val fraction: Float)

@Stable
private class ScrubberController(
    private val gridState: LazyGridState,
    private val entries: State<LazyPagingItems<TimelineEntry>>,
    private val index: State<TimelineIndex>,
    private val onJump: State<(TimelineAnchor?) -> Unit>,
    private val haptics: ScrubberHaptics,
    private val scope: CoroutineScope,
    private val zone: ZoneId,
    deadbandPx: Float,
) {
    var scrubbing by mutableStateOf(false)
        private set
    var dragFraction by mutableFloatStateOf(0f)
        private set

    /** Bumped on every drag step; the settle timer restarts from it. */
    var moves by mutableIntStateOf(0)
        private set

    /** True while a restarted pager has not delivered the day being pointed at. */
    var jumping by mutableStateOf(false)
        private set

    /** The scrubber owns the grid position: while dragged, and until a restarted pager lands. */
    val pointing: Boolean get() = scrubbing || jumping

    private val deadband = ScrubberDeadband(deadbandPx)
    private var fingerPx = 0f
    private var loadedCache: LoadedDays? = null
    private var scrollJob: Job? = null
    private var scrolledTo: Triple<Any, Int, Int>? = null
    private var tilePx = 0f
    private var headerPx = 0f
    private var jumpJob: Job? = null
    private var missingDay: Long? = null
    private var lastMonth: YearMonth? = null

    fun beginScrub(fromFraction: Float, travelPx: Float) {
        dragFraction = fromFraction
        fingerPx = scrubberHandleOffset(fromFraction, travelPx)
        deadband.reset(fingerPx)
        lastMonth = monthAt(fromFraction)
        scrubbing = true
        haptics.begin()
    }

    fun dragBy(deltaPx: Float, travelPx: Float) {
        fingerPx = (fingerPx + deltaPx).coerceIn(0f, travelPx.coerceAtLeast(0f))
        // A resting finger trembles a pixel or two; that must not rock the grid between rows.
        if (!deadband.accept(fingerPx)) return
        dragFraction = scrubberFraction(deadband.applied, travelPx)
        moves++
        val month = monthAt(dragFraction)
        val previous = lastMonth
        if (month != null && month != previous) {
            // A light tick per month and a firmer one per year, like the detents on Google Photos.
            if (previous != null && previous.year != month.year) haptics.year() else haptics.month()
            lastMonth = month
        }
        seek(dragFraction)
    }

    fun endScrub() {
        if (!scrubbing) return
        scrubbing = false
        haptics.end()
        seek(dragFraction)
    }

    fun seekTo(fraction: Float) {
        dragFraction = fraction.coerceIn(0f, 1f)
        seek(dragFraction)
    }

    /**
     * Paging published new rows. While the scrubber owns the grid, the position is re-derived from
     * the finger, so prepends and dropped pages never leave the grid on another day.
     */
    fun onRowsChanged() {
        // A landing jump is placed the frame its rows arrive, before the old index shows another day.
        if (pointing) seek(dragFraction, allowJump = jumpJob?.isActive != true)
    }

    /**
     * Moves the grid to [fraction] right away. Rows that are already paged in are scrolled to on
     * every drag step; anything else restarts the pager at that day, one restart at a time, and the
     * latest finger position is applied again as soon as the new rows land.
     */
    private fun seek(fraction: Float, allowJump: Boolean = true) {
        val list = entries.value
        if (fraction <= NewestFraction) {
            val atNewest = list.loadState.refresh is LoadState.NotLoading &&
                (list.loadState.prepend as? LoadState.NotLoading)?.endOfPaginationReached == true
            if (atNewest) scrollTo(0, 0) else if (allowJump) startJump(null)
            return
        }
        val position = index.value.positionAt(fraction) ?: return
        val loaded = loadedIndexOf(position)
        when {
            loaded != null -> scrollTo(loaded.first, loaded.second)
            // A day the index still lists but the rows no longer have: stay where the last jump landed.
            allowJump && position.bucket.epochDay != missingDay -> startJump(position.bucket.epochDay)
        }
    }

    private fun scrollTo(target: Int, offsetPx: Int) {
        val snapshot = entries.value.itemSnapshotList
        if (scrolledTo == Triple(snapshot, target, offsetPx)) return
        scrolledTo = Triple(snapshot, target, offsetPx)
        scrollJob?.cancel()
        scrollJob = scope.launch { gridState.scrollToItem(target, offsetPx) }
    }

    private fun startJump(day: Long?) {
        if (jumpJob?.isActive == true) return
        jumping = true
        onJump.value(day?.let(::TimelineAnchor))
        jumpJob = scope.launch {
            val landed = withTimeoutOrNull(JumpLandMillis) {
                snapshotFlow { hasLanded(day) }.first { it }
            } != null
            missingDay = if (landed) null else day
            withFrameNanos { }
            // This job is still active here; clear it so the catch-up seek may restart the pager.
            jumpJob = null
            seek(dragFraction)
            if (jumpJob == null) jumping = false
        }
    }

    private fun hasLanded(day: Long?): Boolean {
        val list = entries.value
        if (list.loadState.refresh !is LoadState.NotLoading) return false
        return if (day == null) {
            (list.loadState.prepend as? LoadState.NotLoading)?.endOfPaginationReached == true
        } else {
            loaded().days.containsKey(day)
        }
    }

    /**
     * Grid index and scroll offset for [position] when its day is paged in. The day is walked in
     * pixels (its header, then its rows) so the grid glides with the finger.
     */
    private fun loadedIndexOf(position: TimelineIndexPosition): Pair<Int, Int>? {
        val loaded = loaded()
        val day = loaded.days[position.bucket.epochDay] ?: return null
        measureRows(loaded)
        val header = day.first - 1
        val hasHeader = header >= 0 && loaded.snapshot[header] is TimelineEntry.DayHeader
        val columns = gridColumns()
        val target = scrubberDayTarget(
            fractionInDay = position.fractionInBucket,
            count = day.last - day.first + 1,
            columns = columns,
            headerPx = if (hasHeader) headerPx else 0f,
            rowPx = tilePx,
        )
        return (day.first + target.itemOffset) to target.scrollPx
    }

    private fun gridColumns(): Int =
        (gridState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.column } ?: 0) + 1

    /** Remembers how tall a row of tiles and a day header are, spacing included. */
    private fun measureRows(loaded: LoadedDays) {
        val info = gridState.layoutInfo
        val spacing = info.mainAxisItemSpacing
        for (item in info.visibleItemsInfo) {
            when (loaded.snapshot.getOrNull(item.index)) {
                is TimelineEntry.Media -> tilePx = (item.size.height + spacing).toFloat()
                is TimelineEntry.DayHeader -> headerPx = (item.size.height + spacing).toFloat()
                else -> Unit
            }
        }
        if (headerPx == 0f) headerPx = tilePx / 2f
    }

    fun restingPosition(): RestingPosition? {
        val loaded = loaded()
        val first = gridState.firstVisibleItemIndex
        var i = first
        while (i < loaded.dayAt.size && loaded.dayAt[i] == Long.MIN_VALUE && i - first < 8) i++
        val day = loaded.dayAt.getOrNull(i)?.takeIf { it != Long.MIN_VALUE }
            ?: (loaded.snapshot.getOrNull(first) as? TimelineEntry.DayHeader)?.epochDay
            ?: return null
        val before = loaded.days[day]?.let { (i - it.first).coerceAtLeast(0) } ?: 0
        val timeline = index.value
        val bucket = timeline.days.getOrNull(timeline.bucketIndexOf(day)) ?: return null
        return RestingPosition(day, timeline.fractionOf(day, before.toFloat() / bucket.count))
    }

    private fun loaded(): LoadedDays {
        val snapshot = entries.value.itemSnapshotList
        loadedCache?.let { if (it.snapshot === snapshot) return it }
        return LoadedDays(snapshot, zone).also { loadedCache = it }
    }

    private fun monthAt(fraction: Float): YearMonth? =
        index.value.positionAt(fraction)?.bucket?.epochDay?.let { YearMonth.from(LocalDate.ofEpochDay(it)) }
}

/**
 * Google Photos-style timeline scrubber. While the grid scrolls, a date pill and a handle show
 * where the viewport sits in the whole library. Dragging the handle shows year pills and a month
 * bubble and moves the grid live: paged-in rows scroll on every step and farther days restart the
 * pager there. Like Google Photos, thumbnails only start decoding once the finger rests for
 * [SettleMillis]; until then the rows show their placeholders ([onFastScrubChange]).
 */
@Composable
internal fun TimelineScrubber(
    entries: LazyPagingItems<TimelineEntry>,
    gridState: LazyGridState,
    index: TimelineIndex,
    onJump: (TimelineAnchor?) -> Unit,
    onScrubbingChange: (Boolean) -> Unit,
    onFastScrubChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }
    val entriesState = rememberUpdatedState(entries)
    val indexState = rememberUpdatedState(index)
    val jumpState = rememberUpdatedState(onJump)
    val view = androidx.compose.ui.platform.LocalView.current
    val haptics = remember(view) { ScrubberHaptics(view) }
    val deadbandPx = with(LocalDensity.current) { ScrubDeadband.toPx() }
    val controller = remember(gridState) {
        ScrubberController(gridState, entriesState, indexState, jumpState, haptics, scope, zone, deadbandPx)
    }
    val scrubbingState = rememberUpdatedState(onScrubbingChange)
    LaunchedEffect(controller.pointing) { scrubbingState.value(controller.pointing) }
    LaunchedEffect(controller) {
        snapshotFlow { entriesState.value.itemSnapshotList }.collect { controller.onRowsChanged() }
    }
    val resting by remember(controller) { derivedStateOf { controller.restingPosition() } }
    val fastScrubState = rememberUpdatedState(onFastScrubChange)
    LaunchedEffect(controller.moves, controller.scrubbing) {
        if (controller.scrubbing && controller.moves > 0) {
            fastScrubState.value(true)
            delay(SettleMillis)
        }
        fastScrubState.value(false)
    }

    val active = gridState.isScrollInProgress || controller.scrubbing || controller.jumping
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

    val pointing = controller.scrubbing || controller.jumping
    val handleFraction = if (pointing) controller.dragFraction else resting?.fraction ?: controller.dragFraction
    val shownDay = if (pointing) index.positionAt(controller.dragFraction)?.bucket?.epochDay else resting?.epochDay

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

    val density = LocalDensity.current
    var trackPx by remember { mutableIntStateOf(0) }
    val handleHeightPx = with(density) { HandleHeight.toPx() }
    val travelPx = (trackPx - handleHeightPx).coerceAtLeast(0f)
    val handleOffsetPx = scrubberHandleOffset(handleFraction, travelPx)
    val handleFractionState = rememberUpdatedState(handleFraction)
    val surface = MaterialTheme.colorScheme.surfaceBright
    val onSurface = MaterialTheme.colorScheme.onSurface

    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible && pillText != null,
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Surface(
                color = surface,
                contentColor = onSurface,
                shape = CircleShape,
                shadowElevation = FloatingElevation,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .widthIn(max = 300.dp)
                    .testTag("timeline_scrub_date")
                    .clearAndSetSemantics { },
            ) {
                Text(
                    text = pillText.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = TrackTopInset, bottom = galleryBottomContentPadding() + TrackBottomInset)
                .onSizeChanged { trackPx = it.height },
        ) {
            AnimatedVisibility(
                visible = controller.scrubbing,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopEnd).padding(end = LabelEndInset),
            ) {
                YearTicks(
                    ticks = remember(index) { index.yearTicks() },
                    travelPx = travelPx,
                    handleHeightPx = handleHeightPx,
                )
            }
            MonthBubble(
                visible = controller.scrubbing && monthText != null,
                text = monthText.orEmpty(),
                centerYPx = handleOffsetPx + handleHeightPx / 2f,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = LabelEndInset),
            )
            val handleDescription = stringResource(R.string.scrubber_handle_description)
            val gripOffsetPx = handleOffsetPx - with(density) { (HandleGripHeight - HandleHeight).toPx() } / 2f
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn() + slideInHorizontally { it / 2 },
                exit = fadeOut() + slideOutHorizontally { it / 2 },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, gripOffsetPx.toInt()) },
            ) {
                val elevation by animateDpAsState(
                    if (controller.scrubbing) GrabbedElevation else FloatingElevation,
                    label = "scrubberHandleElevation",
                )
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
                                onDragStart = { controller.beginScrub(handleFractionState.value, travelPx) },
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
                        color = surface,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50),
                        shadowElevation = elevation,
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
private fun MonthBubble(visible: Boolean, text: String, centerYPx: Float, modifier: Modifier) {
    var heightPx by remember { mutableIntStateOf(0) }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.8f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f)),
        exit = fadeOut() + scaleOut(targetScale = 0.8f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f)),
        modifier = modifier.offset { IntOffset(0, (centerYPx - heightPx / 2f).toInt()) },
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceBright,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = CircleShape,
            shadowElevation = FloatingElevation,
            modifier = Modifier
                .onSizeChanged { heightPx = it.height }
                .testTag("timeline_scrub_month")
                .clearAndSetSemantics { },
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
            )
        }
    }
}

/** Two rounded chevrons, the Google Photos scrubber glyph. */
@Composable
private fun HandleArrows() {
    val color = LocalContentColor.current
    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width / 2f + 2.dp.toPx()
        val cy = size.height / 2f
        val half = 5.dp.toPx()
        val rise = 3.5.dp.toPx()
        val gap = 4.dp.toPx()
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val up = Path().apply {
            moveTo(cx - half, cy - gap)
            lineTo(cx, cy - gap - rise)
            lineTo(cx + half, cy - gap)
            close()
        }
        val down = Path().apply {
            moveTo(cx - half, cy + gap)
            lineTo(cx, cy + gap + rise)
            lineTo(cx + half, cy + gap)
            close()
        }
        drawPath(up, color)
        drawPath(up, color, style = stroke)
        drawPath(down, color)
        drawPath(down, color, style = stroke)
    }
}

/** Separate floating year pills along the track, never a solid panel. */
@Composable
private fun YearTicks(ticks: List<TimelineYearTick>, travelPx: Float, handleHeightPx: Float) {
    val density = LocalDensity.current
    val placed = remember(ticks, travelPx) {
        layoutYearTicks(ticks, travelPx, with(density) { TickMinGap.toPx() })
    }
    Box(Modifier.testTag("timeline_scrub_years").clearAndSetSemantics { }) {
        placed.forEach { tick ->
            var heightPx by remember { mutableIntStateOf(0) }
            Surface(
                color = MaterialTheme.colorScheme.surfaceBright,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = CircleShape,
                shadowElevation = 1.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, (handleHeightPx / 2f + tick.y - heightPx / 2f).toInt()) }
                    .onSizeChanged { heightPx = it.height },
            ) {
                Text(
                    text = tick.year.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}
