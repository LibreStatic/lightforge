package com.ugallery.feature.search

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ugallery.core.search.MediaSearchHit
import com.ugallery.core.designsystem.GalleryIcons
import com.ugallery.core.designsystem.GalleryExpressiveIconButton
import com.ugallery.core.designsystem.GalleryLoadingIndicator
import com.ugallery.core.designsystem.GallerySpacing
import com.ugallery.core.designsystem.VideoDurationBadge
import com.ugallery.core.designsystem.galleryAdaptiveLayoutInfo
import com.ugallery.core.designsystem.videoDurationDescription
import com.ugallery.core.model.MediaKind
import com.ugallery.core.thumbnail.ThumbnailLoader
import com.ugallery.core.thumbnail.ThumbnailRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val PAGINATION_PREFETCH_DISTANCE = 3
internal const val SEARCH_RESULTS_GRID_TEST_TAG = "search_results_grid"
internal const val SEARCH_LOADING_ROW_TEST_TAG = "search_loading_row"
internal const val SEARCH_LOADING_INDICATOR_TEST_TAG = "search_loading_indicator"
internal const val SEARCH_CONTENT_COLUMN_TEST_TAG = "search_content_column"
internal const val SEARCH_EXPANDED_DISCOVERY_HEADING_TEST_TAG = "search_expanded_discovery_heading"

internal fun searchHorizontalGutter(width: Dp) =
    maxOf(GallerySpacing.Xl, galleryAdaptiveLayoutInfo(width).gutter)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchContent(
    query: String,
    hits: List<MediaSearchHit>,
    loading: Boolean,
    terminal: Boolean,
    partialIndex: Boolean,
    semanticUnavailable: Boolean = false,
    onRetry: () -> Unit = {},
    error: Boolean,
    detectedContentEnabled: Boolean,
    petCollection: Pair<String, Long>? = null,
    onOpenPetCollection: ((String) -> Unit)? = null,
    thumbnailLoader: ThumbnailLoader?,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onVoiceSearch: (() -> Unit)? = null,
    onPresetSearch: (String) -> Unit,
    onLoadMore: () -> Unit,
    onHit: (MediaSearchHit) -> Unit,
    onEnableDetectedContent: () -> Unit,
    onPauseDetectedContent: () -> Unit,
    onDeleteDetectedContent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDetectedContent by rememberSaveable { mutableStateOf(false) }
    val textFieldState = rememberTextFieldState(query)
    val searchBarState = rememberSearchBarState()
    val coroutineScope = rememberCoroutineScope()
    val currentQuery by rememberUpdatedState(query)
    LaunchedEffect(query) {
        if (textFieldState.text.toString() != query) {
            textFieldState.edit { replace(0, length, query) }
        }
    }
    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }.collectLatest { text ->
            if (text != currentQuery) onQueryChange(text)
        }
    }
    val inputField: @Composable () -> Unit = {
        SearchBarDefaults.InputField(
            textFieldState = textFieldState,
            searchBarState = searchBarState,
            onSearch = {
                if (it.isNotBlank()) {
                    onSearch()
                    coroutineScope.launch { searchBarState.animateToCollapsed() }
                }
            },
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(GalleryIcons.Search, contentDescription = null) },
            trailingIcon = {
                onVoiceSearch?.let { voiceSearch ->
                    GalleryExpressiveIconButton(onClick = voiceSearch) {
                        Icon(GalleryIcons.Mic, contentDescription = stringResource(R.string.search_voice))
                    }
                }
            },
        )
    }
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
      val horizontalGutter = searchHorizontalGutter(maxWidth)
      Column(
          Modifier
              .fillMaxSize()
              .widthIn(max = 1_200.dp)
              .padding(horizontal = horizontalGutter)
              .testTag(SEARCH_CONTENT_COLUMN_TEST_TAG),
          verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text(
            stringResource(R.string.search_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 12.dp).semantics { heading() },
        )
        SearchBar(
            state = searchBarState,
            inputField = inputField,
            modifier = Modifier.fillMaxWidth(),
        )
        ExpandedFullScreenSearchBar(
            state = searchBarState,
            inputField = inputField,
            modifier = Modifier.background(MaterialTheme.colorScheme.surface),
            colors = SearchBarDefaults.colors(
                containerColor = Color.Transparent,
            ),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = horizontalGutter),
            ) {
                SearchDiscovery(
                    onPresetSearch = { label ->
                        onPresetSearch(label)
                        coroutineScope.launch { searchBarState.animateToCollapsed() }
                    },
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = GallerySpacing.Lg),
                    headingModifier = Modifier.testTag(SEARCH_EXPANDED_DISCOVERY_HEADING_TEST_TAG),
                )
            }
        }
        if (semanticUnavailable) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Icon(
                        GalleryIcons.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        stringResource(R.string.search_semantic_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 16.dp)) {
            item {
                val label = stringResource(R.string.search_photos)
                AssistChip(onClick = { onPresetSearch(label) }, label = { Text(label) })
            }
            item {
                val label = stringResource(R.string.search_videos)
                AssistChip(onClick = { onPresetSearch(label) }, label = { Text(label) })
            }
            item {
                val label = stringResource(R.string.search_documents)
                AssistChip(onClick = { onPresetSearch(label) }, label = { Text(label) })
            }
            item { AssistChip(onClick = { showDetectedContent = true }, label = { Text(stringResource(R.string.search_local_analysis)) }) }
        }
        if (partialIndex) {
            Text(stringResource(R.string.search_partial_index), color = MaterialTheme.colorScheme.primary)
        }
        Text(stringResource(R.string.search_privacy), style = MaterialTheme.typography.bodySmall)
        if (query.isBlank()) {
            SearchDiscovery(onPresetSearch = onPresetSearch, modifier = Modifier.weight(1f))
        }
        if (query.isNotBlank() || hits.isNotEmpty()) when {
            error -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.search_error), color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = onRetry) { Text(stringResource(R.string.search_retry)) }
            }
            loading && !terminal && hits.isEmpty() -> SearchLoadingIndicatorRow()
            !terminal && hits.isEmpty() -> Unit
            hits.isEmpty() -> {
                val collection = petCollection
                if (collection != null && collection.second > 0 && onOpenPetCollection != null) {
                    Card(modifier = Modifier.fillMaxWidth().clickable { onOpenPetCollection(collection.first) }) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.search_empty_collection_title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.search_empty_collection_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    Text(stringResource(R.string.search_empty))
                }
            }
            else -> SearchResultsGrid(
                hits = hits,
                loading = loading,
                terminal = terminal,
                thumbnailLoader = thumbnailLoader,
                onLoadMore = onLoadMore,
                onHit = onHit,
                modifier = Modifier.weight(1f),
            )
        }
      }
    }
    if (showDetectedContent) AlertDialog(
        onDismissRequest = { showDetectedContent = false },
        title = { Text(stringResource(R.string.search_analysis_title)) },
        text = { Text(stringResource(R.string.search_analysis_body)) },
        confirmButton = {
            TextButton(onClick = if (detectedContentEnabled) onPauseDetectedContent else onEnableDetectedContent) {
                Text(stringResource(if (detectedContentEnabled) R.string.search_analysis_pause else R.string.search_analysis_enable))
            }
        },
        dismissButton = {
            Row {
                if (detectedContentEnabled) TextButton(onClick = onDeleteDetectedContent) {
                    Text(stringResource(R.string.search_analysis_delete))
                }
                TextButton(onClick = { showDetectedContent = false }) { Text(stringResource(R.string.search_close)) }
            }
        },
    )
}

@Composable
private fun SearchResultsGrid(
    hits: List<MediaSearchHit>,
    loading: Boolean,
    terminal: Boolean,
    thumbnailLoader: ThumbnailLoader?,
    onLoadMore: () -> Unit,
    onHit: (MediaSearchHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    RequestNextPageOnApproachingEnd(
        gridState = gridState,
        resultCount = hits.size,
        loading = loading,
        terminal = terminal,
        onLoadMore = onLoadMore,
    )
    LazyVerticalGrid(
        columns = GridCells.Adaptive(128.dp),
        state = gridState,
        modifier = modifier.testTag(SEARCH_RESULTS_GRID_TEST_TAG),
        contentPadding = PaddingValues(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(hits, key = { "${it.key.volumeName}:${it.key.mediaStoreId}" }) { hit ->
            SearchResultCard(hit, thumbnailLoader) { onHit(hit) }
        }
        if (loading && !terminal) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SearchLoadingIndicatorRow(Modifier.padding(vertical = 12.dp))
            }
        }
    }
}

@Composable
private fun RequestNextPageOnApproachingEnd(
    gridState: LazyGridState,
    resultCount: Int,
    loading: Boolean,
    terminal: Boolean,
    onLoadMore: () -> Unit,
) {
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(gridState, resultCount, loading, terminal) {
        if (resultCount == 0 || loading || terminal) return@LaunchedEffect
        val loadMoreIndex = (resultCount - PAGINATION_PREFETCH_DISTANCE).coerceAtLeast(0)
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .first { lastVisibleIndex -> lastVisibleIndex >= loadMoreIndex }
        currentOnLoadMore()
    }
}

@Composable
private fun SearchLoadingIndicatorRow(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .testTag(SEARCH_LOADING_ROW_TEST_TAG),
        contentAlignment = Alignment.Center,
    ) {
        GalleryLoadingIndicator(Modifier.testTag(SEARCH_LOADING_INDICATOR_TEST_TAG))
    }
}

@Composable
private fun SearchDiscovery(
    onPresetSearch: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    headingModifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                stringResource(R.string.search_people_pets),
                style = MaterialTheme.typography.titleMedium,
                modifier = headingModifier,
            )
        }
        item { LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(end = 16.dp)) {
            val people = listOf(
                R.string.search_me to GalleryIcons.User,
                R.string.search_people to GalleryIcons.User,
                R.string.search_cats to GalleryIcons.Pet,
                R.string.search_dogs to GalleryIcons.Pet,
            )
            items(people) { (labelResource, icon) ->
                val label = stringResource(labelResource)
                Card(onClick = { onPresetSearch(label) }) {
                    Column(
                        Modifier.padding(10.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(52.dp).background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.extraLarge),
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) { Icon(icon, contentDescription = null) }
                        Text(label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        } }
        item { Text(stringResource(R.string.search_places), style = MaterialTheme.typography.titleMedium) }
        item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 16.dp)) {
            items(listOf(R.string.search_coast, R.string.search_mountain, R.string.search_city, R.string.search_rain)) { labelResource ->
                val label = stringResource(labelResource)
                AssistChip(onClick = { onPresetSearch(label) }, label = { Text(label) }, leadingIcon = { Icon(GalleryIcons.Image, contentDescription = null) })
            }
        } }
        item { Text(stringResource(R.string.search_content_types), style = MaterialTheme.typography.titleMedium) }
        item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 16.dp)) {
            items(listOf(R.string.search_documents, R.string.search_screenshots, R.string.search_video, R.string.search_camera)) { labelResource ->
                val label = stringResource(labelResource)
                AssistChip(onClick = { onPresetSearch(label) }, label = { Text(label) }, leadingIcon = { Icon(GalleryIcons.Collections, contentDescription = null) })
            }
        } }
        item { Text(stringResource(R.string.search_topics), style = MaterialTheme.typography.titleMedium) }
        item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 16.dp)) {
            items(listOf(R.string.search_landscapes, R.string.search_food)) { labelResource ->
                val label = stringResource(labelResource)
                AssistChip(onClick = { onPresetSearch(label) }, label = { Text(label) })
            }
        } }
        item { Box(Modifier.size(1.dp)) }
    }
}

@Composable
private fun SearchResultCard(hit: MediaSearchHit, loader: ThumbnailLoader?, onClick: () -> Unit) {
    val request = ThumbnailRequest(hit.key, hit.generationModified, widthPx = 320, heightPx = 320)
    val bitmap by produceState(loader?.cached(request), request, loader) {
        if (value == null && loader != null) value = runCatching { loader.load(request) }.getOrNull()
    }
    val isVideo = hit.kind == MediaKind.Video
    val mediaTypeDescription = if (isVideo) videoDurationDescription(hit.durationMillis) else null
    val fallbackDescription = stringResource(R.string.search_result)
    val description = listOfNotNull(hit.displayName ?: fallbackDescription, mediaTypeDescription)
        .joinToString(", ")
    Card(Modifier.clickable(onClick = onClick).semantics { contentDescription = description }) {
        val loaded = bitmap
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (loaded != null) {
                Image(
                    loaded.asImageBitmap(),
                    null,
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            if (isVideo) {
                VideoDurationBadge(
                    durationMillis = hit.durationMillis,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                )
            }
        }
        Text(hit.displayName ?: fallbackDescription, Modifier.padding(8.dp), maxLines = 2)
    }
}
