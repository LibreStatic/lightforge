package com.librestatic.lightforge.feature.photos

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.model.TimelineDayBucket
import com.librestatic.lightforge.core.model.TimelineIndex
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.core.thumbnail.ThumbnailRequest
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.WeekFields

private val DayTileShape = RoundedCornerShape(12.dp)
private val DayGap = 3.dp
private val RingWidth = 2.dp

/** "Go to date": a month-by-month calendar where every day with media shows its first photo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimelineDatePickerSheet(
    index: TimelineIndex,
    loader: ThumbnailLoader,
    shownDay: Long?,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    // Half-expanded, the list is still laid out at full height, so its last months sit below the screen.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        TimelineDatePickerContent(
            index = index,
            loader = loader,
            shownDay = shownDay,
            onPick = { day ->
                scope.launch { sheetState.hide() }.invokeOnCompletion {
                    onDismiss()
                    onPick(day)
                }
            },
        )
    }
}

@Composable
internal fun TimelineDatePickerContent(
    index: TimelineIndex,
    loader: ThumbnailLoader,
    shownDay: Long?,
    onPick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
) {
    val locale = LocalConfiguration.current.locales[0]
    val bestPattern: (String) -> String = remember(locale) {
        { skeleton -> android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton) }
    }
    val months = remember(index) { datePickerMonths(index) }
    val years = remember(months) { months.map { it.month.year }.distinct() }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = datePickerMonthIndex(months, shownDay))
    val yearState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val visibleYear by remember(months) {
        derivedStateOf {
            // At the end of the list the oldest year can never reach the top, so it follows the bottom.
            val position = if (listState.canScrollForward) listState.firstVisibleItemIndex
            else listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: listState.firstVisibleItemIndex
            months.getOrNull(position)?.month?.year
        }
    }
    LaunchedEffect(visibleYear) {
        val position = years.indexOf(visibleYear)
        if (position >= 0) yearState.animateScrollToItem(position)
    }
    val firstDayOfWeek = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val newestDay = remember(index) { index.days.maxOfOrNull { it.epochDay } }

    Column(modifier.fillMaxWidth().testTag("date_picker")) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.date_picker_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (newestDay != null) {
                AssistChip(
                    onClick = { onPick(newestDay) },
                    label = { Text(stringResource(R.string.date_picker_today)) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    border = null,
                    modifier = Modifier.testTag("date_picker_today"),
                )
            }
        }
        if (years.size > 1) {
            LazyRow(
                state = yearState,
                contentPadding = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                itemsIndexed(years, key = { _, year -> year }) { _, year ->
                    FilterChip(
                        selected = year == visibleYear,
                        onClick = {
                            val target = months.indexOfFirst { it.month.year == year }
                            if (target >= 0) scope.launch { listState.animateScrollToItem(target) }
                        },
                        label = { Text(year.toString()) },
                        modifier = Modifier.testTag("date_picker_year_$year"),
                    )
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 8.dp)
                .clearAndSetSemantics { },
        ) {
            repeat(7) { offset ->
                Text(
                    firstDayOfWeek.plus(offset.toLong()).getDisplayName(TextStyle.NARROW, locale),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        BoxWithConstraints(Modifier.weight(1f, fill = false)) {
            val cellPx = with(LocalDensity.current) { (maxWidth / 7).roundToPx() }.coerceIn(48, 320)
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            ) {
                itemsIndexed(months, key = { _, month -> month.month.toString() }) { _, month ->
                    DatePickerMonthGrid(
                        month = month,
                        loader = loader,
                        cellPx = cellPx,
                        firstDayOfWeek = firstDayOfWeek,
                        today = today,
                        shownDay = shownDay,
                        locale = locale,
                        bestPattern = bestPattern,
                        onPick = onPick,
                    )
                }
            }
        }
    }
}

@Composable
private fun DatePickerMonthGrid(
    month: DatePickerMonth,
    loader: ThumbnailLoader,
    cellPx: Int,
    firstDayOfWeek: java.time.DayOfWeek,
    today: LocalDate,
    shownDay: Long?,
    locale: java.util.Locale,
    bestPattern: (String) -> String,
    onPick: (Long) -> Unit,
) {
    val weeks = remember(month.month, firstDayOfWeek) { datePickerWeeks(month.month, firstDayOfWeek) }
    Column(Modifier.fillMaxWidth()) {
        Text(
            formatWithSkeleton(month.month.atDay(1), locale, "MMMMy", bestPattern),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 8.dp, top = 20.dp, bottom = 8.dp).semantics { heading() },
        )
        weeks.forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { dayOfMonth ->
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(DayGap)) {
                        if (dayOfMonth != null) {
                            val date = month.month.atDay(dayOfMonth)
                            val label = formatWithSkeleton(date, locale, "yMMMMEEEEd", bestPattern)
                            val ringed = date == today || date.toEpochDay() == shownDay
                            val bucket = month.days[dayOfMonth]
                            if (bucket != null) {
                                DayTile(bucket, dayOfMonth, label, ringed, loader, cellPx) { onPick(bucket.epochDay) }
                            } else {
                                EmptyDay(dayOfMonth, label, ringed)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayTile(
    bucket: TimelineDayBucket,
    dayOfMonth: Int,
    label: String,
    ringed: Boolean,
    loader: ThumbnailLoader,
    sizePx: Int,
    onClick: () -> Unit,
) {
    val description = pluralStringResource(R.plurals.date_picker_day_description, bucket.count, label, bucket.count)
    val cover = bucket.cover
    val request = cover?.let { ThumbnailRequest(it.key, it.generationModified, sizePx, sizePx) }
    val bitmap: Bitmap? by key(request, loader) {
        produceState(request?.let(loader::cached), request) {
            if (value == null && request != null) {
                try { value = loader.load(request) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { value = null }
            }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .ring(ringed, DayTileShape)
            .clip(DayTileShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag("date_picker_day_${bucket.epochDay}"),
    ) {
        bitmap?.let {
            Image(
                it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shape = CircleShape,
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).clearAndSetSemantics { },
        ) {
            Text(
                dayOfMonth.toString(),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun EmptyDay(dayOfMonth: Int, label: String, ringed: Boolean) {
    val description = stringResource(R.string.date_picker_no_media, label)
    Box(
        Modifier
            .fillMaxSize()
            .ring(ringed, CircleShape)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            dayOfMonth.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

@Composable
private fun Modifier.ring(show: Boolean, shape: androidx.compose.ui.graphics.Shape): Modifier =
    if (show) border(RingWidth, MaterialTheme.colorScheme.primary, shape).padding(RingWidth + 1.dp) else this
