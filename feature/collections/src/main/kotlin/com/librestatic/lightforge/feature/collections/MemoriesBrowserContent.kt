package com.librestatic.lightforge.feature.collections

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.librestatic.lightforge.core.data.MemoriesFilter
import com.librestatic.lightforge.core.data.MomentRepository
import com.librestatic.lightforge.core.database.MomentEntity
import com.librestatic.lightforge.core.database.MomentSummaryRow
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GalleryTopAppBar
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.core.thumbnail.ThumbnailRequest
import kotlinx.coroutines.CancellationException

/** Paged access to every saved/suggested story, independent of the Collections preview limit. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
fun MemoriesBrowserContent(
    repository: MomentRepository,
    thumbnailLoader: ThumbnailLoader?,
    onBack: () -> Unit,
    onMomentClick: (MomentEntity) -> Unit,
    modifier: Modifier = Modifier,
    momentPlaceLabels: ((String) -> kotlinx.coroutines.flow.Flow<String?>)? = null,
) {
    var filter by rememberSaveable { mutableStateOf(MemoriesFilter.All) }
    BackHandler(onBack = onBack)
    Surface(
        modifier.fillMaxSize().testTag("memories-browser-screen").semantics {
            testTagsAsResourceId = true
        },
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 840.dp).fillMaxSize()) {
                GalleryTopAppBar(
                    title = stringResource(R.string.memories_browser_title),
                    onBack = onBack,
                    navigationContentDescription = stringResource(R.string.memories_browser_back),
                )
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MemoriesFilter.entries.forEach { choice ->
                        FilterChip(
                            selected = choice == filter,
                            onClick = { filter = choice },
                            label = {
                                Text(
                                    stringResource(
                                        when (choice) {
                                            MemoriesFilter.All -> R.string.memories_browser_all
                                            MemoriesFilter.Saved -> R.string.memories_browser_saved
                                            MemoriesFilter.Suggested ->
                                                R.string.memories_browser_suggested
                                        }
                                    )
                                )
                            },
                            modifier =
                                Modifier.testTag("memories-filter-${choice.name.lowercase()}"),
                        )
                    }
                }
                key(filter) {
                    val pages =
                        remember(repository, filter) { repository.browser(filter) }
                            .collectAsLazyPagingItems()
                    val scroll = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
                    LazyColumn(
                        Modifier.fillMaxWidth().weight(1f).testTag("memories-browser-list"),
                        state = scroll,
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item("description") {
                            Text(
                                stringResource(R.string.memories_browser_body),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (pages.loadState.refresh is LoadState.Loading)
                            item("loading") {
                                LinearProgressIndicator(
                                    Modifier.fillMaxWidth().testTag("memories-browser-loading")
                                )
                                Text(stringResource(R.string.memories_browser_loading))
                            }
                        if (pages.loadState.refresh is LoadState.NotLoading && pages.itemCount == 0)
                            item("empty") {
                                Text(
                                    stringResource(R.string.memories_browser_empty),
                                    Modifier.testTag("memories-browser-empty"),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(stringResource(R.string.memories_browser_empty_body))
                            }
                        items(pages.itemCount, key = pages.itemKey { it.moment.momentId }) { index
                            ->
                            pages[index]?.let { row ->
                                MemoryBrowserCard(row, repository, thumbnailLoader, onMomentClick, momentPlaceLabels)
                            }
                        }
                        if (
                            pages.loadState.refresh is LoadState.Error ||
                                pages.loadState.append is LoadState.Error
                        )
                            item("error") {
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                ) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                        Text(
                                            stringResource(R.string.memories_browser_error),
                                            Modifier.testTag("memories-browser-error"),
                                        )
                                        TextButton(
                                            onClick = { pages.retry() },
                                            colors =
                                                ButtonDefaults.textButtonColors(
                                                    contentColor = LocalContentColor.current
                                                ),
                                            modifier = Modifier.testTag("memories-browser-retry"),
                                        ) {
                                            Text(stringResource(R.string.memories_browser_retry))
                                        }
                                    }
                                }
                            }
                        if (pages.loadState.append is LoadState.Loading)
                            item("more") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryBrowserCard(
    row: MomentSummaryRow,
    repository: MomentRepository,
    loader: ThumbnailLoader?,
    onOpen: (MomentEntity) -> Unit,
    placeLabels: ((String) -> kotlinx.coroutines.flow.Flow<String?>)?,
) {
    OutlinedCard(
        onClick = { onOpen(row.moment) },
        modifier = Modifier.fillMaxWidth().testTag("memories-open-${row.moment.momentId}"),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val key =
                row.coverVolumeName?.let { volume ->
                    row.coverMediaStoreId?.let { MediaKey(volume, it) }
                }
            MemoryBrowserCover(key, repository, loader, Modifier.size(72.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    momentDisplayTitle(row.moment, placeLabels),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(
                        if (row.moment.state == "SAVED") R.string.memories_browser_saved
                        else R.string.memories_browser_suggested
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    stringResource(R.string.memories_browser_photo_count, row.memberCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (row.memberCount == 0L)
                    Text(
                        stringResource(R.string.memories_browser_hidden),
                        style = MaterialTheme.typography.bodySmall,
                    )
            }
        }
    }
}

@Composable
private fun MemoryBrowserCover(
    key: MediaKey?,
    repository: MomentRepository,
    loader: ThumbnailLoader?,
    modifier: Modifier,
) {
    val media =
        if (key != null)
            remember(repository, key) { repository.browserCover(key) }
                .collectAsState(initial = null)
                .value
        else null
    val request =
        media
            ?.takeIf { it.isAccessible && !it.isTrashed }
            ?.let { ThumbnailRequest(checkNotNull(key), it.generationModified, 192, 192) }
    val image by
        produceState<Bitmap?>(null, request, loader) {
            value = null
            try {
                value = request?.let { loader?.load(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
    Surface(
        modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            image?.let {
                Image(
                    it.asImageBitmap(),
                    null,
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } ?: Icon(GalleryIcons.Image, null, Modifier.size(28.dp))
        }
    }
}
