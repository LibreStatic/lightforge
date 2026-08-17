package com.ugallery.feature.search

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.ugallery.core.search.MediaSearchHit
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
    thumbnailLoader: ThumbnailLoader?,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onPresetSearch: (String) -> Unit,
    onLoadMore: () -> Unit,
    onHit: (MediaSearchHit) -> Unit,
    onEnableDetectedContent: () -> Unit,
    onPauseDetectedContent: () -> Unit,
    onDeleteDetectedContent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDetectedContent by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.search_title),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() },
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onSearch, enabled = query.isNotBlank()) { Text(stringResource(R.string.search_action)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { onPresetSearch("fotos") }, label = { Text(stringResource(R.string.search_photos)) })
            AssistChip(onClick = { onPresetSearch("vídeos") }, label = { Text(stringResource(R.string.search_videos)) })
            AssistChip(onClick = { onPresetSearch("documentos") }, label = { Text(stringResource(R.string.search_documents)) })
            AssistChip(onClick = { showDetectedContent = true }, label = { Text(stringResource(R.string.search_local_analysis)) })
        }
        if (partialIndex) {
            Text(stringResource(R.string.search_partial_index), color = MaterialTheme.colorScheme.primary)
        }
        Text(stringResource(R.string.search_privacy), style = MaterialTheme.typography.bodySmall)
        when {
            error -> Text(stringResource(R.string.search_error), color = MaterialTheme.colorScheme.error)
            loading && hits.isEmpty() -> CircularProgressIndicator()
            hits.isEmpty() && query.isNotBlank() -> Text(stringResource(R.string.search_empty))
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
