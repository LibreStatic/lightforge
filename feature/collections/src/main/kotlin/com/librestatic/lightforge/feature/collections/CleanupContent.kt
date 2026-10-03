package com.librestatic.lightforge.feature.collections

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GalleryExpressiveButton
import com.librestatic.lightforge.core.designsystem.GalleryIndeterminateProgressIndicator
import com.librestatic.lightforge.core.designsystem.GalleryTopAppBar
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.core.designsystem.GalleryProgressSlot

data class CleanupDuplicateGroupUi(
    val id: String,
    val members: List<MediaKey>,
    val keep: MediaKey,
    val memberCount: Long,
    val recoverableBytes: Long,
)

data class CleanupUiState(
    val loading: Boolean = true,
    val analysisEnabled: Boolean = false,
    val duplicateGroups: List<CleanupDuplicateGroupUi> = emptyList(),
    val duplicateGroupCount: Long = 0,
    val duplicateBytes: Long = 0,
    val largeVideos: List<MediaKey> = emptyList(),
    val largeVideoCount: Long = 0,
    val largeVideoBytes: Long = 0,
    val screenshots: List<MediaKey> = emptyList(),
    val screenshotCount: Long = 0,
    val blurry: List<MediaKey> = emptyList(),
    val blurryCount: Long = 0,
)

/** Sections that move every listed item to the trash in one select-all action. */
enum class CleanupSection { LargeVideos, Screenshots, Blurry }

/** The list an opened item belongs to, so the viewer pages through that same list. */
sealed interface CleanupList {
    data class DuplicateGroup(val groupId: String) : CleanupList
    data class Section(val section: CleanupSection) : CleanupList
}

private sealed interface CleanupTrashRequest {
    val count: Long
    data class Copies(val groupId: String, override val count: Long) : CleanupTrashRequest
    data class Section(val section: CleanupSection, override val count: Long) : CleanupTrashRequest
}

/**
 * Review surface for local cleanup analysis. Nothing is removed here: every action goes through
 * the shared select-all trash pipeline and its system confirmation.
 */
@Composable
fun CleanupContent(
    state: CleanupUiState,
    thumbnailLoader: ThumbnailLoader?,
    onBack: () -> Unit,
    onEnableAnalysis: () -> Unit,
    onOpen: (MediaKey, CleanupList) -> Unit,
    onTrashDuplicateCopies: (groupId: String) -> Unit,
    onTrashSection: (CleanupSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    var request by remember { mutableStateOf<CleanupTrashRequest?>(null) }
    // LazyColumn's builder is not composable, so resolve section texts up front.
    val duplicatesTitle = stringResource(R.string.cleanup_duplicates_title)
    val duplicatesSummary = pluralStringResource(
        R.plurals.cleanup_duplicates_summary,
        state.duplicateGroupCount.toInt(),
        state.duplicateGroupCount,
        size(state.duplicateBytes),
    )
    val largeVideosTitle = stringResource(R.string.cleanup_large_videos_title)
    val largeVideosSummary = pluralStringResource(
        R.plurals.cleanup_large_videos_summary,
        state.largeVideoCount.toInt(),
        state.largeVideoCount,
        size(state.largeVideoBytes),
    )
    val screenshotsTitle = stringResource(R.string.cleanup_screenshots_title)
    val screenshotsSummary = pluralStringResource(
        R.plurals.cleanup_screenshots_summary,
        state.screenshotCount.toInt(),
        state.screenshotCount,
    )
    val blurryTitle = stringResource(R.string.cleanup_blurry_title)
    val blurrySummary = pluralStringResource(R.plurals.cleanup_blurry_summary, state.blurryCount.toInt(), state.blurryCount)
    Column(modifier.fillMaxSize()) {
        GalleryTopAppBar(
            title = stringResource(R.string.cleanup_title),
            onBack = onBack,
            navigationContentDescription = stringResource(R.string.cleanup_back),
        )
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).testTag("cleanup-screen"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { GalleryProgressSlot(state.loading) }
            if (!state.analysisEnabled) item {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.cleanup_analysis_off))
                        GalleryExpressiveButton(onClick = onEnableAnalysis, modifier = Modifier.testTag("cleanup-enable")) {
                            Text(stringResource(R.string.cleanup_analysis_enable))
                        }
                    }
                }
            } else item {
                Text(
                    stringResource(R.string.cleanup_analysis_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            sectionHeader("duplicates", duplicatesTitle, duplicatesSummary)
            if (state.duplicateGroups.isEmpty()) item { Text(stringResource(R.string.cleanup_empty)) }
            items(state.duplicateGroups, key = { "group:${it.id}" }) { group ->
                Card(Modifier.fillMaxWidth().testTag("cleanup-group-${group.id}")) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            pluralStringResource(
                                R.plurals.cleanup_group_summary,
                                group.memberCount.toInt(),
                                group.memberCount,
                                size(group.recoverableBytes),
                            ),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        ThumbnailRow(group.members, thumbnailLoader, keep = group.keep) {
                            onOpen(it, CleanupList.DuplicateGroup(group.id))
                        }
                        OutlinedButton(onClick = {
                            request = CleanupTrashRequest.Copies(group.id, group.memberCount - 1)
                        }) { Text(stringResource(R.string.cleanup_trash_copies)) }
                    }
                }
            }

            section(
                "large-videos",
                largeVideosTitle,
                largeVideosSummary,
                state.largeVideos,
                state.largeVideoCount,
                thumbnailLoader,
                onOpen = { onOpen(it, CleanupList.Section(CleanupSection.LargeVideos)) },
                onTrash = { request = CleanupTrashRequest.Section(CleanupSection.LargeVideos, state.largeVideoCount) },
            )
            section(
                "screenshots",
                screenshotsTitle,
                screenshotsSummary,
                state.screenshots,
                state.screenshotCount,
                thumbnailLoader,
                onOpen = { onOpen(it, CleanupList.Section(CleanupSection.Screenshots)) },
                onTrash = { request = CleanupTrashRequest.Section(CleanupSection.Screenshots, state.screenshotCount) },
            )
            section(
                "blurry",
                blurryTitle,
                blurrySummary,
                state.blurry,
                state.blurryCount,
                thumbnailLoader,
                onOpen = { onOpen(it, CleanupList.Section(CleanupSection.Blurry)) },
                onTrash = { request = CleanupTrashRequest.Section(CleanupSection.Blurry, state.blurryCount) },
            )
        }
    }
    request?.let { pending ->
        AlertDialog(
            onDismissRequest = { request = null },
            modifier = Modifier.testTag("cleanup-trash-dialog"),
            title = {
                Text(pluralStringResource(R.plurals.cleanup_trash_title, pending.count.toInt(), pending.count))
            },
            text = { Text(stringResource(R.string.cleanup_trash_body)) },
            confirmButton = {
                TextButton(onClick = {
                    request = null
                    when (pending) {
                        is CleanupTrashRequest.Copies -> onTrashDuplicateCopies(pending.groupId)
                        is CleanupTrashRequest.Section -> onTrashSection(pending.section)
                    }
                }, modifier = Modifier.testTag("cleanup-trash-confirm")) { Text(stringResource(R.string.cleanup_trash_confirm)) }
            },
            dismissButton = { TextButton(onClick = { request = null }) { Text(stringResource(R.string.cleanup_cancel)) } },
        )
    }
}

private fun LazyListScope.sectionHeader(key: String, title: String, summary: String) {
    item(key = "$key-header") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun LazyListScope.section(
    key: String,
    title: String,
    summary: String,
    items: List<MediaKey>,
    count: Long,
    loader: ThumbnailLoader?,
    onOpen: (MediaKey) -> Unit,
    onTrash: () -> Unit,
) {
    sectionHeader(key, title, summary)
    item(key = "$key-items") {
        if (items.isEmpty()) Text(stringResource(R.string.cleanup_empty))
        else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ThumbnailRow(items, loader, keep = null, onOpen = onOpen)
            OutlinedButton(onClick = onTrash, enabled = count > 0, modifier = Modifier.testTag("cleanup-trash-$key")) {
                Text(stringResource(R.string.cleanup_trash_all))
            }
        }
    }
}

@Composable
private fun ThumbnailRow(
    items: List<MediaKey>,
    loader: ThumbnailLoader?,
    keep: MediaKey?,
    onOpen: (MediaKey) -> Unit,
) {
    val openLabel = stringResource(R.string.cleanup_open_item)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = { "${it.volumeName}:${it.mediaStoreId}" }) { key ->
            Box(
                Modifier.size(96.dp).clip(MaterialTheme.shapes.medium)
                    .semantics { contentDescription = openLabel }
                    .clickable { onOpen(key) },
            ) {
                PersonThumbnail(key, loader, Modifier.fillMaxSize())
                if (key == keep) Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                ) {
                    Text(
                        stringResource(R.string.cleanup_keep),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}
