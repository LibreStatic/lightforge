package com.librestatic.lightforge.feature.collections

import android.graphics.Bitmap
import com.librestatic.lightforge.core.designsystem.GalleryEmptyState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.librestatic.lightforge.core.data.*
import com.librestatic.lightforge.core.database.*
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.thumbnail.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.librestatic.lightforge.core.designsystem.GalleryIndeterminateProgressIndicator
import com.librestatic.lightforge.core.designsystem.GalleryProgressSlot

private fun MediaKey.stackKey() = "$volumeName:$mediaStoreId"

@OptIn(ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun PhotoStacksContent(
    repository: GalleryPhotoStackRepository,
    thumbnailLoader: ThumbnailLoader?,
    onBack: () -> Unit,
    onAnalysis: () -> Unit,
    initialStackId: String? = null,
    modifier: Modifier = Modifier,
) {
    var stackId by rememberSaveable { mutableStateOf(initialStackId) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<PhotoStackPreview?>(null) }
    var comparing by rememberSaveable { mutableStateOf(false) }
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf("") }
    var confirmDissolve by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val saved = remember(repository) { repository.saved() }.collectAsLazyPagingItems()
    val suggestions = remember(repository) { repository.suggestions() }.collectAsLazyPagingItems()
    val separated = remember(repository) { repository.separated() }.collectAsLazyPagingItems()
    val stack by
        produceState<PhotoStackEntity?>(null, repository, stackId) {
            value = null
            stackId?.let { repository.observe(it).collect { row -> value = row } }
        }
    val members by
        produceState<List<PhotoStackPhoto>>(emptyList(), repository, stackId) {
            value = emptyList()
            stackId?.let { repository.members(it).collect { rows -> value = rows } }
        }
    fun home() {
        stackId = null
        preview = null
        selectedKey = null
        comparing = false
        renaming = false
        error = null
    }
    fun back() {
        if (comparing) comparing = false
        else if (stackId != null || preview != null) home() else onBack()
    }
    BackHandler { back() }
    fun mutate(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: PhotoStackChanged) {
                error = R.string.stacks_changed
            } catch (_: Exception) {
                error = R.string.stacks_error
            } finally {
                busy = false
            }
        }
    }
    val photos = preview?.photos ?: if (stack == null) emptyList() else members
    val cover =
        photos.firstOrNull { photo ->
            if (preview != null)
                photo.media.volumeName == preview!!.suggestion.recommendedVolumeName &&
                    photo.media.mediaStoreId == preview!!.suggestion.recommendedMediaStoreId
            else
                photo.media.volumeName == stack?.coverVolumeName &&
                    photo.media.mediaStoreId == stack?.coverMediaStoreId
        } ?: photos.firstOrNull()
    val selected = photos.firstOrNull { it.key().stackKey() == selectedKey } ?: cover
    LaunchedEffect(comparing) { if (comparing) listState.animateScrollToItem(2) }
    LaunchedEffect(renaming) {
        if (renaming) listState.animateScrollToItem(if (selected != null && cover != null) 5 else 2)
    }
    if (confirmDissolve && stack != null) {
        val current = stack!!
        AlertDialog(
            onDismissRequest = { confirmDissolve = false },
            title = { Text(stringResource(R.string.stacks_unstack)) },
            text = { Text(stringResource(R.string.stacks_unstack_hint)) },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("stack-confirm-unstack"),
                    enabled = !busy,
                    onClick = {
                        confirmDissolve = false
                        mutate {
                            repository.dissolve(current.stackId, current.revision)
                            home()
                        }
                    },
                ) {
                    Text(stringResource(R.string.stacks_unstack))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDissolve = false }) {
                    Text(stringResource(R.string.stacks_cancel))
                }
            },
        )
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier.testTag("photo-stacks-screen").semantics { testTagsAsResourceId = true },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize().testTag("photo-stacks-list"),
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                TextButton(onClick = { back() }, enabled = !busy) {
                    Text(stringResource(R.string.stacks_back))
                }
                Text(
                    stringResource(R.string.stacks_title),
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    stringResource(R.string.stacks_local),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (stackId == null && preview == null) {
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                                R.string.stacks_saved,
                                R.string.stacks_suggested,
                                R.string.stacks_separated,
                            )
                            .forEachIndexed { index, label ->
                                FilterChip(
                                    selected = tab == index,
                                    onClick = {
                                        tab = index
                                        error = null
                                    },
                                    label = { Text(stringResource(label)) },
                                )
                            }
                    }
                    Text(
                        stringResource(
                            if (tab == 1) R.string.stacks_suggestions_hint
                            else if (tab == 2) R.string.stacks_separated_hint
                            else R.string.stacks_create_hint
                        )
                    )
                    if (tab == 1)
                        TextButton(onClick = onAnalysis, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.stacks_analysis))
                        }
                }
                val load =
                    when (tab) {
                        1 -> suggestions.loadState
                        2 -> separated.loadState
                        else -> saved.loadState
                    }
                val count =
                    when (tab) {
                        1 -> suggestions.itemCount
                        2 -> separated.itemCount
                        else -> saved.itemCount
                    }
                if (load.refresh is LoadState.Loading && count == 0) item { GalleryIndeterminateProgressIndicator() }
                if (load.refresh is LoadState.NotLoading && count == 0)
                    item {
                        GalleryEmptyState(
                            title = stringResource(R.string.stacks_empty),
                            body = stringResource(R.string.stacks_empty_body),
                            icon = GalleryIcons.Layers,
                            hero = true,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
                        )
                    }
                if (load.refresh is LoadState.Error || load.append is LoadState.Error)
                    item {
                        TextButton(
                            onClick = {
                                when (tab) {
                                    1 -> suggestions.retry()
                                    2 -> separated.retry()
                                    else -> saved.retry()
                                }
                            }
                        ) {
                            Text(stringResource(R.string.stacks_retry))
                        }
                    }
                when (tab) {
                    0 ->
                        items(saved.itemCount, key = saved.itemKey { it.stack.stackId }) { index ->
                            saved[index]?.let { row ->
                                Card(
                                    Modifier.fillMaxWidth()
                                        .clickable {
                                            stackId = row.stack.stackId
                                            selectedKey = null
                                        }
                                        .testTag("saved-stack-${row.stack.stackId}")
                                ) {
                                    Row(
                                        Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        val key =
                                            row.displayCoverVolumeName?.let { v ->
                                                row.displayCoverMediaStoreId?.let {
                                                    MediaKey(v, it)
                                                }
                                            }
                                        StackImage(
                                            key,
                                            row.displayCoverGeneration ?: 0,
                                            thumbnailLoader,
                                            Modifier.size(72.dp),
                                            160,
                                        )
                                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                            Text(
                                                row.stack.title
                                                    ?: stringResource(
                                                        R.string.stacks_default_title
                                                    ),
                                                style = MaterialTheme.typography.titleMedium,
                                            )
                                            Text(
                                                stringResource(
                                                    R.string.stacks_available,
                                                    row.availableCount,
                                                    row.totalCount,
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    1 ->
                        items(
                            suggestions.itemCount,
                            key = suggestions.itemKey { "${it.clusterId}:${it.algorithmVersion}" },
                        ) { index ->
                            suggestions[index]?.let { row ->
                                OutlinedCard(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text(
                                            stringResource(
                                                R.string.stacks_suggestion_count,
                                                row.memberCount,
                                            ),
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Button(
                                            enabled = !busy,
                                            onClick = {
                                                mutate {
                                                    preview = repository.preview(row)
                                                    selectedKey = null
                                                }
                                            },
                                            modifier =
                                                Modifier.fillMaxWidth()
                                                    .testTag("review-stack-${row.clusterId}"),
                                        ) {
                                            Text(stringResource(R.string.stacks_review))
                                        }
                                    }
                                }
                            }
                        }
                    else ->
                        items(
                            separated.itemCount,
                            key = separated.itemKey { "${it.volumeName}:${it.mediaStoreId}" },
                        ) { index ->
                            separated[index]?.let { row ->
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(
                                            row.displayName ?: stringResource(R.string.stacks_photo)
                                        )
                                        TextButton(
                                            enabled = !busy,
                                            onClick = {
                                                mutate {
                                                    repository.allowSuggestion(
                                                        MediaKey(row.volumeName, row.mediaStoreId)
                                                    )
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text(stringResource(R.string.stacks_allow))
                                        }
                                    }
                                }
                            }
                        }
                }
                if (load.append is LoadState.Loading) item { GalleryIndeterminateProgressIndicator() }
            } else {
                item {
                    Text(
                        stack?.title
                            ?: stringResource(
                                if (preview != null) R.string.stacks_suggested
                                else R.string.stacks_default_title
                            ),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (selected == null) Text(stringResource(R.string.stacks_unavailable))
                }
                if (selected != null && cover != null) {
                    if (comparing && photos.size >= 2)
                        item {
                            val other =
                                selected.takeIf { it.key() != cover.key() }
                                    ?: photos.first { it.key() != cover.key() }
                            BoxWithConstraints(
                                Modifier.fillMaxWidth().testTag("stack-comparison")
                            ) {
                                if (maxWidth >= 600.dp)
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        StackComparison(
                                            cover,
                                            thumbnailLoader,
                                            zoom,
                                            R.string.stacks_cover,
                                            Modifier.weight(1f),
                                        )
                                        StackComparison(
                                            other,
                                            thumbnailLoader,
                                            zoom,
                                            R.string.stacks_selected,
                                            Modifier.weight(1f),
                                        )
                                    }
                                else
                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        StackComparison(
                                            cover,
                                            thumbnailLoader,
                                            zoom,
                                            R.string.stacks_cover,
                                            Modifier.fillMaxWidth(),
                                        )
                                        StackComparison(
                                            other,
                                            thumbnailLoader,
                                            zoom,
                                            R.string.stacks_selected,
                                            Modifier.fillMaxWidth(),
                                        )
                                    }
                            }
                            val zoomLabel =
                                stringResource(R.string.stacks_zoom, (zoom * 100).toInt())
                            Text(zoomLabel)
                            Slider(
                                value = zoom,
                                onValueChange = { zoom = it },
                                valueRange = 1f..3f,
                                modifier =
                                    Modifier.testTag("stack-compare-zoom").semantics {
                                        contentDescription = zoomLabel
                                    },
                            )
                            TextButton(
                                onClick = { comparing = false },
                                modifier = Modifier.fillMaxWidth().testTag("stack-close-compare"),
                            ) {
                                Text(stringResource(R.string.stacks_close_compare))
                            }
                        }
                    else
                        item {
                            StackImage(
                                selected.key(),
                                selected.media.generationModified,
                                thumbnailLoader,
                                Modifier.fillMaxWidth().height(280.dp).testTag("stack-hero"),
                                1024,
                            )
                            Text(
                                selected.media.displayName ?: stringResource(R.string.stacks_photo)
                            )
                            Text(
                                stringResource(
                                    if (selected.key() == cover.key()) R.string.stacks_cover
                                    else R.string.stacks_selected
                                ),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                stringResource(
                                    R.string.stacks_dimensions,
                                    selected.media.width,
                                    selected.media.height,
                                    selected.media.mimeType ?: "",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            selected.detailScore
                                ?.takeIf { it.isFinite() }
                                ?.let { score ->
                                    Text(
                                        stringResource(R.string.stacks_detail_score, score),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                        }
                    item {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.testTag("stack-filmstrip"),
                        ) {
                            items(photos, key = { it.key().stackKey() }) { photo ->
                                val active = photo.key() == selected.key()
                                val name =
                                    photo.media.displayName ?: stringResource(R.string.stacks_photo)
                                Surface(
                                    color =
                                        if (active) MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainer,
                                    contentColor =
                                        if (active) MaterialTheme.colorScheme.onSecondaryContainer
                                        else MaterialTheme.colorScheme.onSurface,
                                    shape = MaterialTheme.shapes.medium,
                                    modifier =
                                        Modifier.width(100.dp)
                                            .clickable { selectedKey = photo.key().stackKey() }
                                            .testTag("stack-photo-${photo.key().stackKey()}")
                                            .semantics {
                                                this.selected = active
                                                contentDescription = name
                                            },
                                ) {
                                    Column(Modifier.padding(6.dp)) {
                                        StackImage(
                                            photo.key(),
                                            photo.media.generationModified,
                                            thumbnailLoader,
                                            Modifier.size(88.dp),
                                            160,
                                        )
                                        Text(
                                            name,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item {
                        if (preview != null)
                            Button(
                                enabled = !busy,
                                onClick = {
                                    val snapshot = preview!!
                                    val key = selected.key()
                                    mutate {
                                        stackId = repository.save(snapshot, key)
                                        preview = null
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().testTag("stack-save"),
                            ) {
                                Text(stringResource(R.string.stacks_save))
                            }
                        else if (stack != null)
                            Button(
                                enabled =
                                    !busy &&
                                        (selected.media.volumeName != stack?.coverVolumeName ||
                                            selected.media.mediaStoreId !=
                                                stack?.coverMediaStoreId),
                                onClick = {
                                    val current = stack!!
                                    mutate {
                                        repository.setCover(
                                            current.stackId,
                                            current.revision,
                                            selected.key(),
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().testTag("stack-set-cover"),
                            ) {
                                Text(stringResource(R.string.stacks_choose_cover))
                            }
                        OutlinedButton(
                            enabled = photos.size >= 2,
                            onClick = {
                                comparing = !comparing
                                zoom = 1f
                            },
                            modifier = Modifier.fillMaxWidth().testTag("stack-compare"),
                        ) {
                            Text(stringResource(R.string.stacks_compare))
                        }
                        if (stack != null) {
                            val current = stack!!
                            TextButton(
                                enabled = !busy,
                                onClick = {
                                    mutate {
                                        val remains =
                                            repository.separate(
                                                current.stackId,
                                                current.revision,
                                                selected.key(),
                                            )
                                        selectedKey = null
                                        comparing = false
                                        if (!remains) home()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().testTag("stack-separate"),
                            ) {
                                Text(stringResource(R.string.stacks_separate))
                            }
                        }
                    }
                }
                if (stack != null)
                    item {
                        val current = stack!!
                        if (renaming) {
                            OutlinedTextField(
                                value = title,
                                onValueChange = { title = it.take(80) },
                                label = { Text(stringResource(R.string.stacks_name)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                enabled = !busy,
                                onClick = {
                                    mutate {
                                        repository.rename(current.stackId, current.revision, title)
                                        renaming = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.stacks_save_name))
                            }
                        } else
                            TextButton(
                                onClick = {
                                    title = current.title.orEmpty()
                                    renaming = true
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.stacks_rename))
                            }
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { confirmDissolve = true },
                            modifier = Modifier.fillMaxWidth().testTag("stack-unstack"),
                        ) {
                            Text(stringResource(R.string.stacks_unstack))
                        }
                    }
                if (stackId != null && stack == null)
                    item {
                        TextButton(onClick = { home() }) {
                            Text(stringResource(R.string.stacks_saved))
                        }
                    }
            }
            error?.let { id ->
                item {
                    Text(
                        stringResource(id),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            item { GalleryProgressSlot(busy) }
        }
    }
}

@Composable
private fun StackComparison(
    photo: PhotoStackPhoto,
    loader: ThumbnailLoader?,
    zoom: Float,
    label: Int,
    modifier: Modifier,
) {
    Column(
        modifier.testTag(
            if (label == R.string.stacks_cover) "stack-comparison-cover"
            else "stack-comparison-selected"
        )
    ) {
        Text(stringResource(label), style = MaterialTheme.typography.titleMedium)
        StackImage(
            photo.key(),
            photo.media.generationModified,
            loader,
            Modifier.fillMaxWidth().height(230.dp),
            1024,
            zoom,
        )
        Text(
            photo.media.displayName ?: stringResource(R.string.stacks_photo),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Always decode/cache by original source identity and generation, never a previous rendered frame.
 */
@Composable
private fun StackImage(
    key: MediaKey?,
    generation: Long,
    loader: ThumbnailLoader?,
    modifier: Modifier,
    size: Int,
    zoom: Float = 1f,
) {
    val request =
        remember(key, generation, size) {
            key?.let { ThumbnailRequest(it, generation, size, size) }
        }
    val bitmap by
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
        Box(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
            val image = bitmap
            if (image == null)
                Icon(
                    GalleryIcons.Image,
                    stringResource(R.string.stacks_photo),
                    Modifier.size(24.dp),
                )
            else
                Image(
                    image.asImageBitmap(),
                    null,
                    Modifier.fillMaxSize()
                        .testTag("stack-image-loaded-${key?.stackKey()}-$size")
                        .graphicsLayer(scaleX = zoom, scaleY = zoom),
                    contentScale = ContentScale.Fit,
                )
        }
    }
}
