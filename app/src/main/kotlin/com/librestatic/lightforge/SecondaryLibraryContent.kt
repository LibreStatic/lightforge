package com.librestatic.lightforge

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GallerySpacing
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.librestatic.lightforge.core.data.GalleryActivityEvent
import com.librestatic.lightforge.core.data.GalleryActivityType
import com.librestatic.lightforge.core.designsystem.GalleryIcons
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
    if (events.isEmpty()) {
        Column(modifier.fillMaxSize().padding(horizontal = if (showTitle) GallerySpacing.Lg else 0.dp)) {
            if (showTitle) title()
            GalleryStateContent(
                stringResource(R.string.updates_empty),
                stringResource(R.string.updates_local_body),
                stringResource(R.string.updates_title),
                Modifier.weight(1f).fillMaxWidth(),
            )
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
        items(events, key = GalleryActivityEvent::id) { event ->
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
    }
}

@Composable
private fun activityLabel(event: GalleryActivityEvent): String = when (event.type) {
    GalleryActivityType.Archived -> stringResource(R.string.activity_archived, event.itemCount)
    GalleryActivityType.Unarchived -> stringResource(R.string.activity_unarchived, event.itemCount)
    GalleryActivityType.Trashed -> stringResource(R.string.activity_trashed, event.itemCount)
    GalleryActivityType.Restored -> stringResource(R.string.activity_restored, event.itemCount)
    GalleryActivityType.Deleted -> stringResource(R.string.activity_deleted, event.itemCount)
    GalleryActivityType.Exported -> stringResource(R.string.activity_exported, event.itemCount)
    GalleryActivityType.Imported -> stringResource(R.string.activity_imported, event.itemCount)
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
                            Text(album.itemCount.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
