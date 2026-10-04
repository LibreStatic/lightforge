package com.librestatic.lightforge.feature.search

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
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.search.MediaSearchHit
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GalleryGridMetrics
import com.librestatic.lightforge.core.designsystem.GalleryContentWidths
import com.librestatic.lightforge.core.designsystem.GalleryWindowClass
import com.librestatic.lightforge.core.designsystem.galleryWindowClass
import com.librestatic.lightforge.core.designsystem.MediaTileBadges
import com.librestatic.lightforge.core.designsystem.GalleryExpressiveIconButton
import com.librestatic.lightforge.core.designsystem.GalleryLoadingIndicator
import com.librestatic.lightforge.core.designsystem.GallerySpacing
import com.librestatic.lightforge.core.designsystem.galleryBottomContentPadding
import com.librestatic.lightforge.core.designsystem.RetainGridThumbnailViewport
import com.librestatic.lightforge.core.designsystem.VideoDurationBadge
import com.librestatic.lightforge.core.designsystem.galleryAdaptiveLayoutInfo
import com.librestatic.lightforge.core.designsystem.videoDurationDescription
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.core.thumbnail.ThumbnailPrefetchCandidate
import com.librestatic.lightforge.core.thumbnail.ThumbnailRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest

private const val PAGINATION_PREFETCH_DISTANCE = 3
internal const val SEARCH_RESULTS_GRID_TEST_TAG = "search_results_grid"
internal const val SEARCH_LOADING_ROW_TEST_TAG = "search_loading_row"
internal const val SEARCH_LOADING_INDICATOR_TEST_TAG = "search_loading_indicator"
internal const val SEARCH_CONTENT_COLUMN_TEST_TAG = "search_content_column"

internal fun searchHorizontalGutter(width: Dp) =
    maxOf(GallerySpacing.Xl, galleryAdaptiveLayoutInfo(width).gutter)

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SearchContent(
    query: String,
    hits: List<MediaSearchHit>,
    loading: Boolean,
    terminal: Boolean,
    partialIndex: Boolean,
    onRetry: () -> Unit = {},
    error: Boolean,
    semanticUnavailable: Boolean = false,
    detectedContentEnabled: Boolean,
    petCollection: Pair<String, Long>? = null,
    onOpenPetCollection: ((String) -> Unit)? = null,
    onOpenPlaces: (() -> Unit)? = null,
    thumbnailLoader: ThumbnailLoader?,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onVoiceSearch: (() -> Unit)? = null,
    onPresetSearch: (String) -> Unit,
    onLoadMore: () -> Unit,
    onHit: (MediaSearchHit) -> Unit,
    onEnableDetectedContent: () -> Unit,
    onPauseDetectedContent: () -> Unit,
    // People and faces is a separate, explicit opt-in: Enable above only covers content and text.
    peopleAnalysisEnabled: Boolean = true,
    onEnablePeopleAnalysis: () -> Unit = {},
    isArchived: (MediaSearchHit) -> Boolean = { false },
    onDeleteDetectedContent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDetectedContent by rememberSaveable { mutableStateOf(false) }
    var confirmDeleteDetected by rememberSaveable { mutableStateOf(false) }
    val textFieldState = rememberTextFieldState(query)
    val searchBarState = rememberSearchBarState()
    val focusManager = LocalFocusManager.current
    val currentQuery by rememberUpdatedState(query)
    // Values sent to onQueryChange that the caller has not echoed back yet. While typing fast,
    // a stale echo ("le") can arrive after newer keystrokes ("lec"); writing it back into the
    // field dropped characters (R-04), so only genuinely external queries replace the text.
    val pendingEchoes = remember { ArrayDeque<String>() }
    LaunchedEffect(query) {
        val echoIndex = pendingEchoes.indexOf(query)
        if (echoIndex >= 0) {
            repeat(echoIndex + 1) { pendingEchoes.removeFirst() }
        } else if (textFieldState.text.toString() != query) {
            pendingEchoes.clear()
            textFieldState.edit { replace(0, length, query) }
        }
    }
    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }.collectLatest { text ->
            if (text != currentQuery) {
                pendingEchoes.addLast(text)
                onQueryChange(text)
            }
        }
    }
    val inputField: @Composable () -> Unit = {
        // A single field that never expands: the collapsed->expanded swap moved the IME
        // connection mid-typing and dropped or reordered keystrokes (R-04). Discovery is
        // already shown inline below the field while the query is blank.
        SearchBarDefaults.InputField(
            state = textFieldState,
            expanded = false,
            onExpandedChange = {},
            onSearch = {
                if (it.isNotBlank()) {
                    onSearch()
                    focusManager.clearFocus()
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
      val wideDiscovery = galleryWindowClass(maxWidth) != GalleryWindowClass.Compact
      Column(
          Modifier
              .fillMaxSize()
              .widthIn(max = GalleryContentWidths.Browsing)
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
        val presetChips: List<Pair<Int, (String) -> Unit>> = listOf(
            R.string.search_photos to onPresetSearch,
            R.string.search_videos to onPresetSearch,
            R.string.search_documents to onPresetSearch,
            R.string.search_local_analysis to { _ -> showDetectedContent = true },
        )
        val presetChip: @Composable (Pair<Int, (String) -> Unit>) -> Unit = { (res, action) ->
            val label = stringResource(res)
            AssistChip(onClick = { action(label) }, label = { Text(label) })
        }
        if (wideDiscovery) {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { presetChips.forEach { presetChip(it) } }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 16.dp)) {
                items(presetChips) { presetChip(it) }
            }
        }
        if (partialIndex) {
            Text(stringResource(R.string.search_partial_index), color = MaterialTheme.colorScheme.primary)
        }
        Text(stringResource(R.string.search_privacy), style = MaterialTheme.typography.bodySmall)
        if (query.isBlank()) {
            SearchDiscovery(
                onPresetSearch = onPresetSearch,
                onOpenPlaces = onOpenPlaces,
                modifier = Modifier.weight(1f),
                wide = wideDiscovery,
            )
        }
        if (semanticUnavailable && !error && query.isNotBlank()) {
            Text(
                stringResource(R.string.search_semantic_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("search-semantic-unavailable"),
            )
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
                    if (!detectedContentEnabled) {
                        // Content queries (people, places, topics) find nothing until analysis has run.
                        Text(
                            stringResource(R.string.search_empty_analysis_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { showDetectedContent = true }) {
                            Text(stringResource(R.string.search_local_analysis))
                        }
                    }
                }
            }
            else -> SearchResultsGrid(
                hits = hits,
                loading = loading,
                terminal = terminal,
                thumbnailLoader = thumbnailLoader,
                onLoadMore = onLoadMore,
                onHit = onHit,
                isArchived = isArchived,
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
            Column(horizontalAlignment = Alignment.End) {
                if (detectedContentEnabled && !peopleAnalysisEnabled) {
                    TextButton(
                        onClick = onEnablePeopleAnalysis,
                        modifier = Modifier.testTag("search_analysis_enable_people"),
                    ) {
                        Text(stringResource(R.string.search_analysis_enable_people))
                    }
                }
                if (detectedContentEnabled) TextButton(onClick = { confirmDeleteDetected = true }) {
                    Text(stringResource(R.string.search_analysis_delete))
                }
                TextButton(onClick = { showDetectedContent = false }) { Text(stringResource(R.string.search_close)) }
            }
        },
    )
    if (confirmDeleteDetected) AlertDialog(
        onDismissRequest = { confirmDeleteDetected = false },
        title = { Text(stringResource(R.string.search_analysis_delete_title)) },
        text = { Text(stringResource(R.string.search_analysis_delete_body)) },
        confirmButton = {
            TextButton(onClick = { confirmDeleteDetected = false; onDeleteDetectedContent() }) {
                Text(stringResource(R.string.search_analysis_delete_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { confirmDeleteDetected = false }) { Text(stringResource(R.string.search_cancel)) }
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
    isArchived: (MediaSearchHit) -> Boolean,
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
    BoxWithConstraints(modifier) {
        val gap = 8.dp
        val columns = GalleryGridMetrics.adaptiveColumns(maxWidth)
        val thumbnailSizePx = with(LocalDensity.current) {
            ((maxWidth - gap * (columns - 1)) / columns).roundToPx()
        }.coerceAtLeast(1)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = Modifier.fillMaxSize().testTag(SEARCH_RESULTS_GRID_TEST_TAG),
            // Results scroll under the floating navigation; the last row still clears it.
            contentPadding = PaddingValues(bottom = galleryBottomContentPadding(GallerySpacing.Lg)),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            items(hits, key = { "${it.key.volumeName}:${it.key.mediaStoreId}" }) { hit ->
                SearchResultCard(hit, thumbnailLoader, thumbnailSizePx, isArchived(hit)) { onHit(hit) }
            }
            if (loading && !terminal) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SearchLoadingIndicatorRow(Modifier.padding(vertical = 12.dp))
                }
            }
        }
        if (thumbnailLoader != null) {
            RetainGridThumbnailViewport(
                state = gridState,
                loader = thumbnailLoader,
                columns = columns,
                itemCount = hits.size,
                contentKey = hits,
                itemAtIndex = { index ->
                    val hit = hits.getOrNull(index) ?: return@RetainGridThumbnailViewport null
                    ThumbnailPrefetchCandidate(
                        request = hit.thumbnailRequest(thumbnailSizePx),
                        sourceWidth = hit.width,
                        sourceHeight = hit.height,
                        distanceFromViewportCenter = 0,
                    )
                },
            )
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
    onOpenPlaces: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = galleryBottomContentPadding(GallerySpacing.Lg)),
    headingModifier: Modifier = Modifier,
    wide: Boolean = false,
) {
    val people = listOf(
        R.string.search_me to GalleryIcons.User,
        R.string.search_people to GalleryIcons.User,
        R.string.search_cats to GalleryIcons.Pet,
        R.string.search_dogs to GalleryIcons.Pet,
    )
    val places = listOf(R.string.search_coast, R.string.search_mountain, R.string.search_city, R.string.search_rain)
    val contentTypes = listOf(R.string.search_documents, R.string.search_screenshots, R.string.search_video, R.string.search_camera)
    val topics = listOf(R.string.search_landscapes, R.string.search_food)
    val peopleSection: @Composable (Modifier) -> Unit = { m ->
        SearchDiscoverySection(stringResource(R.string.search_people_pets), m, wide, null) {
            SearchPeopleRow(people, wide, onPresetSearch)
        }
    }
    val placesSection: @Composable (Modifier) -> Unit = { m ->
        SearchDiscoverySection(stringResource(R.string.search_places), m, wide, onOpenPlaces) {
            SearchChipRow(places, GalleryIcons.Image, wide, onPresetSearch)
        }
    }
    val contentTypesSection: @Composable (Modifier) -> Unit = { m ->
        SearchDiscoverySection(stringResource(R.string.search_content_types), m, wide, null) {
            SearchChipRow(contentTypes, GalleryIcons.Collections, wide, onPresetSearch)
        }
    }
    val topicsSection: @Composable (Modifier) -> Unit = { m ->
        SearchDiscoverySection(stringResource(R.string.search_topics), m, wide, null) {
            SearchChipRow(topics, null, wide, onPresetSearch)
        }
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(if (wide) 12.dp else 10.dp),
    ) {
        if (wide) {
            // Width is plentiful: two tonal cards per row instead of stacked scrolling rows.
            item {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    peopleSection(Modifier.weight(1f).fillMaxHeight())
                    placesSection(Modifier.weight(1f).fillMaxHeight())
                }
            }
            item {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    contentTypesSection(Modifier.weight(1f).fillMaxHeight())
                    topicsSection(Modifier.weight(1f).fillMaxHeight())
                }
            }
        } else {
            item { peopleSection(headingModifier) }
            item { placesSection(headingModifier) }
            item { contentTypesSection(headingModifier) }
            item { topicsSection(headingModifier) }
        }
        item { Box(Modifier.size(1.dp)) }
    }
}

/** A discovery section: a plain heading + row on phones, a tonal card on wide layouts. */
@Composable
private fun SearchDiscoverySection(
    title: String,
    modifier: Modifier,
    tonal: Boolean,
    onOpen: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    val heading: @Composable () -> Unit = {
        if (onOpen != null) {
            // Same start edge and typography as the other section headers (R-07); a chevron
            // marks it as the entry to Places instead of TextButton padding.
            Row(
                Modifier
                    .semantics { heading() }
                    .testTag("search-open-places")
                    .clip(MaterialTheme.shapes.small)
                    .clickable(role = Role.Button, onClick = onOpen)
                    .heightIn(min = 48.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Icon(GalleryIcons.ChevronForward, contentDescription = null)
            }
        } else {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        }
    }
    if (tonal) {
        Surface(
            modifier = modifier,
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                heading()
                content()
            }
        }
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            heading()
            content()
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SearchPeopleRow(
    people: List<Pair<Int, androidx.compose.ui.graphics.vector.ImageVector>>,
    wrap: Boolean,
    onPresetSearch: (String) -> Unit,
) {
    val entry: @Composable (Pair<Int, androidx.compose.ui.graphics.vector.ImageVector>) -> Unit = { (labelResource, icon) ->
        val label = stringResource(labelResource)
        Card(onClick = { onPresetSearch(label) }) {
            Column(
                Modifier.padding(10.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(52.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.extraLarge),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    if (wrap) {
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) { people.forEach { entry(it) } }
    } else {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(end = 16.dp)) {
            items(people) { entry(it) }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SearchChipRow(
    labels: List<Int>,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    wrap: Boolean,
    onPresetSearch: (String) -> Unit,
) {
    val chip: @Composable (Int) -> Unit = { labelResource ->
        val label = stringResource(labelResource)
        AssistChip(
            onClick = { onPresetSearch(label) },
            label = { Text(label) },
            leadingIcon = icon?.let { vector -> { Icon(vector, contentDescription = null) } },
        )
    }
    if (wrap) {
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { labels.forEach { chip(it) } }
    } else {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 16.dp)) {
            items(labels) { chip(it) }
        }
    }
}

@Composable
private fun SearchResultCard(
    hit: MediaSearchHit,
    loader: ThumbnailLoader?,
    sizePx: Int,
    archived: Boolean,
    onClick: () -> Unit,
) {
    val request = hit.thumbnailRequest(sizePx)
    val bitmap by produceState(loader?.cached(request), request, loader) {
        if (value == null && loader != null) value = runCatching { loader.load(request) }.getOrNull()
    }
    val isVideo = hit.kind == MediaKind.Video
    val mediaTypeDescription = if (isVideo) videoDurationDescription(hit.durationMillis) else null
    val fallbackDescription = stringResource(R.string.search_result)
    val description = com.librestatic.lightforge.core.designsystem.mediaTileDescription(
        base = listOfNotNull(hit.displayName ?: fallbackDescription, mediaTypeDescription).joinToString(", "),
        isFavorite = hit.favorite,
        displayName = hit.displayName,
        isVideo = isVideo,
        isArchived = archived,
    )
    Card(
        Modifier.clickable(onClick = onClick).clearAndSetSemantics {
            contentDescription = description
            this.onClick { onClick(); true }
        },
    ) {
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
            MediaTileBadges(
                isFavorite = hit.favorite,
                displayName = hit.displayName,
                isVideo = isVideo,
                isArchived = archived,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
            )
            if (isVideo) {
                VideoDurationBadge(
                    durationMillis = hit.durationMillis,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                )
            }
        }
        Text(
            hit.displayName ?: fallbackDescription,
            Modifier.padding(8.dp),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun MediaSearchHit.thumbnailRequest(sizePx: Int) = ThumbnailRequest(
    mediaKey = key,
    generationModified = generationModified,
    widthPx = sizePx,
    heightPx = sizePx,
)
