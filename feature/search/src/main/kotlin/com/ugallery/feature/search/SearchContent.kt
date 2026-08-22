package com.ugallery.feature.search

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import com.ugallery.core.search.MediaSearchHit
import com.ugallery.core.designsystem.GalleryIcons
import com.ugallery.core.thumbnail.ThumbnailLoader
import com.ugallery.core.thumbnail.ThumbnailRequest

@Composable
fun SearchContent(
    query: String,
    hits: List<MediaSearchHit>,
    loading: Boolean,
    terminal: Boolean,
    partialIndex: Boolean,
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
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
      Column(Modifier.fillMaxSize().widthIn(max = 1_200.dp).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.search_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 12.dp).semantics { heading() },
        )
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text(stringResource(R.string.search_hint)) },
            singleLine = true,
            leadingIcon = { Icon(GalleryIcons.Search, contentDescription = null) },
            trailingIcon = {
                onVoiceSearch?.let { voiceSearch ->
                    IconButton(onClick = voiceSearch) {
                        Icon(GalleryIcons.Mic, contentDescription = stringResource(R.string.search_voice))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) onSearch() }),
            modifier = Modifier.fillMaxWidth(),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            error -> Text(stringResource(R.string.search_error), color = MaterialTheme.colorScheme.error)
            loading && hits.isEmpty() -> CircularProgressIndicator()
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
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(128.dp),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(hits, key = { "${it.key.volumeName}:${it.key.mediaStoreId}" }) { hit ->
                    SearchResultCard(hit, thumbnailLoader) { onHit(hit) }
                }
                if (!terminal && hits.isNotEmpty()) item {
                    Button(onClick = onLoadMore, enabled = !loading) { Text(stringResource(R.string.search_more)) }
                }
            }
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
private fun SearchDiscovery(onPresetSearch: (String) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(stringResource(R.string.search_people_pets), style = MaterialTheme.typography.titleMedium) }
        item { LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                            Modifier.size(52.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) { Icon(icon, contentDescription = null) }
                        Text(label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        } }
        item { Text(stringResource(R.string.search_places), style = MaterialTheme.typography.titleMedium) }
        item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf(R.string.search_coast, R.string.search_mountain, R.string.search_city, R.string.search_rain)) { labelResource ->
                val label = stringResource(labelResource)
                AssistChip(onClick = { onPresetSearch(label) }, label = { Text(label) }, leadingIcon = { Icon(GalleryIcons.Image, contentDescription = null) })
            }
        } }
        item { Text(stringResource(R.string.search_content_types), style = MaterialTheme.typography.titleMedium) }
        item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf(R.string.search_documents, R.string.search_screenshots, R.string.search_video, R.string.search_camera)) { labelResource ->
                val label = stringResource(labelResource)
                AssistChip(onClick = { onPresetSearch(label) }, label = { Text(label) }, leadingIcon = { Icon(GalleryIcons.Collections, contentDescription = null) })
            }
        } }
        item { Text(stringResource(R.string.search_topics), style = MaterialTheme.typography.titleMedium) }
        item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
    val description = hit.displayName ?: stringResource(R.string.search_result)
    Card(Modifier.clickable(onClick = onClick).semantics { contentDescription = description }) {
        val loaded = bitmap
        if (loaded == null) Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant))
        else Image(loaded.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(1f), contentScale = ContentScale.Crop)
        Text(description, Modifier.padding(8.dp), maxLines = 2)
    }
}
