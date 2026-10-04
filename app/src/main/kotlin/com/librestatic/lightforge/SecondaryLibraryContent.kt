package com.librestatic.lightforge

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.librestatic.lightforge.core.designsystem.GalleryShapeIllustration
import com.librestatic.lightforge.core.designsystem.galleryFadeRise
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GallerySpacing
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.librestatic.lightforge.core.data.GalleryActivityEvent
import com.librestatic.lightforge.core.data.GalleryActivityType
import com.librestatic.lightforge.core.designsystem.GalleryContentWidths
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GalleryWindowClass
import com.librestatic.lightforge.core.designsystem.galleryWindowClass
import com.librestatic.lightforge.core.designsystem.GalleryStateContent
import com.librestatic.lightforge.core.model.AlbumSummary

/**
 * @param showTitle draw the screen title in the content, like the other rail destinations
 *   (Collections): in rail layouts Updates is a rail destination with no top app bar.
 */
@Composable
internal fun UpdatesContent(
    events: List<GalleryActivityEvent>,
    modifier: Modifier = Modifier,
    showTitle: Boolean = false,
) {
    val title: @Composable () -> Unit = {
        Text(
            stringResource(R.string.updates_title),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier
                .padding(top = GallerySpacing.Xl, bottom = GallerySpacing.Sm)
                .semantics { heading() },
        )
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (galleryWindowClass(maxWidth) != GalleryWindowClass.Compact) {
            UpdatesTwoPane(events, showTitle, title)
        } else {
            UpdatesSinglePane(events, showTitle, title)
        }
    }
}

@Composable
private fun UpdatesTwoPane(
    events: List<GalleryActivityEvent>,
    showTitle: Boolean,
    title: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxSize().padding(horizontal = GallerySpacing.Xl),
        horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Xl),
    ) {
        Column(Modifier.width(340.dp).fillMaxHeight()) {
            if (showTitle) title() else Spacer(Modifier.height(GallerySpacing.Xl))
            Text(
                stringResource(R.string.updates_local_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (events.isEmpty()) {
            val updatesTitle = stringResource(R.string.updates_title)
            Column(
                Modifier.weight(1f).fillMaxHeight().padding(GallerySpacing.Xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                GalleryShapeIllustration(
                    GalleryIcons.Notifications,
                    size = 168.dp,
                    animateEntrance = true,
                    modifier = Modifier.semantics { contentDescription = updatesTitle },
                )
                Spacer(Modifier.height(GallerySpacing.Xl))
                Text(
                    stringResource(R.string.updates_empty),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.galleryFadeRise(delayMillis = 180),
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight().widthIn(max = GalleryContentWidths.Reading),
                contentPadding = PaddingValues(vertical = GallerySpacing.Xl),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(events, key = GalleryActivityEvent::id) { event -> ActivityCard(event) }
            }
        }
    }
}

@Composable
private fun UpdatesSinglePane(
    events: List<GalleryActivityEvent>,
    showTitle: Boolean,
    title: @Composable () -> Unit,
) {
    val modifier = Modifier
    if (events.isEmpty()) {
        val updatesTitle = stringResource(R.string.updates_title)
        Column(modifier.fillMaxSize().padding(horizontal = if (showTitle) GallerySpacing.Lg else 0.dp)) {
            if (showTitle) title()
            // The whole screen is this state, so it gets real art instead of the small default icon.
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(GallerySpacing.Xxl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                GalleryShapeIllustration(
                    GalleryIcons.Notifications,
                    size = 168.dp,
                    animateEntrance = true,
                    modifier = Modifier.semantics { contentDescription = updatesTitle },
                )
                Spacer(Modifier.height(GallerySpacing.Xl))
                Text(
                    stringResource(R.string.updates_empty),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.galleryFadeRise(delayMillis = 180),
                )
                Spacer(Modifier.height(GallerySpacing.Sm))
                Text(
                    stringResource(R.string.updates_local_body),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 480.dp).galleryFadeRise(delayMillis = 260),
                )
            }
        }
        return
    }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showTitle) item { title() }
        item {
            Text(stringResource(R.string.updates_local_body), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        }
        items(events, key = GalleryActivityEvent::id) { event -> ActivityCard(event) }
    }
}

@Composable
private fun ActivityCard(event: GalleryActivityEvent) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(activityIcon(event.type), contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(activityLabel(event), style = MaterialTheme.typography.titleMedium)
                Text(
                    DateUtils.getRelativeTimeSpanString(event.occurredAtMillis).toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun activityLabel(event: GalleryActivityEvent): String = when (event.type) {
    GalleryActivityType.Archived -> pluralStringResource(R.plurals.activity_archived, event.itemCount.toInt(), event.itemCount)
    GalleryActivityType.Unarchived -> pluralStringResource(R.plurals.activity_unarchived, event.itemCount.toInt(), event.itemCount)
    GalleryActivityType.Trashed -> pluralStringResource(R.plurals.activity_trashed, event.itemCount.toInt(), event.itemCount)
    GalleryActivityType.Restored -> pluralStringResource(R.plurals.activity_restored, event.itemCount.toInt(), event.itemCount)
    GalleryActivityType.Deleted -> pluralStringResource(R.plurals.activity_deleted, event.itemCount.toInt(), event.itemCount)
    GalleryActivityType.Exported -> pluralStringResource(R.plurals.activity_exported, event.itemCount.toInt(), event.itemCount)
    GalleryActivityType.Imported -> pluralStringResource(R.plurals.activity_imported, event.itemCount.toInt(), event.itemCount)
    GalleryActivityType.AnalysisCompleted -> stringResource(R.string.activity_analysis_complete)
    GalleryActivityType.AnalysisFailed -> stringResource(R.string.activity_analysis_failed)
}

private fun activityIcon(type: GalleryActivityType) = when (type) {
    GalleryActivityType.Archived -> GalleryIcons.Archive
    GalleryActivityType.Unarchived, GalleryActivityType.Restored -> GalleryIcons.Download
    GalleryActivityType.Trashed, GalleryActivityType.Deleted -> GalleryIcons.Trash
    GalleryActivityType.Exported -> GalleryIcons.Share
    GalleryActivityType.Imported -> GalleryIcons.Plus
    GalleryActivityType.AnalysisCompleted, GalleryActivityType.AnalysisFailed -> GalleryIcons.Analyze
}

@Composable
internal fun DeviceFoldersContent(
    albums: LazyPagingItems<AlbumSummary>,
    onAlbumClick: (AlbumSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (albums.itemCount == 0 && albums.loadState.refresh !is LoadState.Loading) {
        GalleryStateContent(
            stringResource(R.string.device_folders_empty),
            stringResource(R.string.device_folders_title),
            stringResource(R.string.device_folders_title),
            modifier.fillMaxSize(),
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(180.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(albums.itemCount, key = { index -> albums.peek(index)?.key?.toString() ?: "pending:$index" }) { index ->
            albums[index]?.let { album ->
                Card(Modifier.fillMaxWidth().clickable { onAlbumClick(album) }) {
                    Row(
                        Modifier.fillMaxWidth().padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(GalleryIcons.Folder, contentDescription = null)
                        Column {
                            Text(album.name ?: stringResource(com.librestatic.lightforge.feature.album.R.string.album_untitled), style = MaterialTheme.typography.titleMedium)
                            Text(pluralStringResource(com.librestatic.lightforge.feature.album.R.plurals.album_item_count, album.itemCount.toInt(), album.itemCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
