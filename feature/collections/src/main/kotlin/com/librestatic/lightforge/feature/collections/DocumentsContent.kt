package com.librestatic.lightforge.feature.collections

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.librestatic.lightforge.core.data.DocumentCategory
import com.librestatic.lightforge.core.data.GalleryDocumentRepository
import com.librestatic.lightforge.core.database.DocumentRow
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.core.thumbnail.ThumbnailRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.librestatic.lightforge.core.designsystem.GalleryIndeterminateProgressIndicator

private fun DocumentRow.key() = MediaKey(media.volumeName, media.mediaStoreId)

private fun MediaKey.saved() = "$volumeName:$mediaStoreId"

private fun String.mediaKey() = MediaKey(substringBeforeLast(':'), substringAfterLast(':').toLong())

internal fun DocumentCategory.label() =
    when (this) {
        DocumentCategory.All -> R.string.documents_all
        DocumentCategory.Suggested -> R.string.documents_suggested
        DocumentCategory.Receipt -> R.string.documents_receipts
        DocumentCategory.Ticket -> R.string.documents_tickets
        DocumentCategory.Note -> R.string.documents_notes
        DocumentCategory.Other -> R.string.documents_other
        DocumentCategory.Excluded -> R.string.documents_excluded
    }

@OptIn(ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun DocumentsContent(
    repository: GalleryDocumentRepository,
    thumbnailLoader: ThumbnailLoader?,
    onPdf: (List<MediaKey>) -> Unit,
    onPdfStudio: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onCreateMemory: ((List<MediaKey>) -> Unit)? = null,
) {
    var showAutoArchive by rememberSaveable { mutableStateOf(false) }
    if (showAutoArchive) {
        DocumentAutoArchiveContent(
            repository.autoArchive,
            thumbnailLoader,
            onBack = { showAutoArchive = false },
            modifier = modifier,
        )
        return
    }
    val context = LocalContext.current
    // Measured container width, so multi-window and foldable postures switch layouts.
    BoxWithConstraints(modifier) {
    val wide =
        com.librestatic.lightforge.core.designsystem.galleryWindowClass(maxWidth) !=
            com.librestatic.lightforge.core.designsystem.GalleryWindowClass.Compact
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var categoryName by rememberSaveable { mutableStateOf(DocumentCategory.All.name) }
    val category = DocumentCategory.valueOf(categoryName)
    var query by rememberSaveable { mutableStateOf("") }
    var focused by rememberSaveable { mutableStateOf<String?>(null) }
    var selection by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    val rows =
        remember(repository, category, query) { repository.pages(category, query) }
            .collectAsLazyPagingItems()
    val failure = stringResource(R.string.documents_error)
    val changed = stringResource(R.string.documents_changed)
    val undo = stringResource(R.string.documents_undo)
    val copied = stringResource(R.string.documents_copied)
    fun mutate(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                snackbar.showSnackbar(failure)
            } finally {
                busy = false
            }
        }
    }
    val detail by
        produceState<DocumentRow?>(null, repository, focused) {
            value = null
            focused?.mediaKey()?.let { repository.observe(it).collect { row -> value = row } }
        }
    BackHandler(focused != null) { focused = null }
    val controls: @Composable ColumnScope.() -> Unit = {
        Text(
            stringResource(R.string.documents_hint),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(120) },
            label = { Text(stringResource(R.string.documents_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DocumentCategory.entries.forEach { filter ->
                FilterChip(
                    selected = category == filter,
                    onClick = { categoryName = filter.name },
                    label = { Text(stringResource(filter.label())) },
                )
            }
        }
        if (selection.isNotEmpty()) {
            if (onCreateMemory != null) OutlinedButton(
                enabled = !busy && selection.size in 1..120,
                onClick = { onCreateMemory(selection.map { it.mediaKey() }) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("documents-create-memory"),
            ) { Text(stringResource(R.string.manual_moment_title)) }

            Button(
                enabled = !busy,
                onClick = {
                    mutate {
                        onPdf(
                            repository.pdfKeys(selection.map { it.mediaKey() })
                        )
                        selection = arrayListOf()
                    }
                },
                modifier =
                    Modifier.fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("documents-create-pdf"),
            ) {
                Text(
                    stringResource(
                        R.string.documents_prepare_pdf,
                        selection.size,
                    )
                )
            }
            TextButton(
                onClick = { selection = arrayListOf() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.documents_clear_selection))
            }
        } else {
            DocumentActionButton(
                wide,
                com.librestatic.lightforge.core.designsystem.GalleryIcons.PictureAsPdf,
                stringResource(R.string.documents_studio),
                onClick = onPdfStudio,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            )
        }
        DocumentActionButton(
            wide,
            com.librestatic.lightforge.core.designsystem.GalleryIcons.Archive,
            stringResource(R.string.document_auto_title),
            onClick = { showAutoArchive = true },
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("documents-auto-archive"),
        )
        Text(
            stringResource(R.string.documents_selection_hint),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 6.dp),
        )
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = Modifier.fillMaxSize().testTag("documents-screen").semantics { testTagsAsResourceId = true },
    ) { padding ->
        if (wide && focused == null) {
            Column(Modifier.padding(padding).fillMaxSize()) {
                com.librestatic.lightforge.core.designsystem.GalleryTopAppBar(
                    title = stringResource(R.string.documents_title),
                    onBack = onBack,
                    navigationContentDescription = stringResource(R.string.documents_back),
                    windowInsets = WindowInsets(0, 0, 0, 0),
                )
                Row(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    Column(
                        Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(end = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) { controls() }
                    LazyVerticalGrid(
                        GridCells.Adaptive(260.dp),
                        Modifier.weight(1f).fillMaxHeight().testTag("documents-list"),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                stringResource(category.label()) + " · " + rows.itemCount,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(vertical = 4.dp).semantics { heading() },
                            )
                        }
                        if (rows.loadState.refresh is LoadState.Loading && rows.itemCount == 0)
                            item(span = { GridItemSpan(maxLineSpan) }) { GalleryIndeterminateProgressIndicator() }
                        if (rows.loadState.refresh is LoadState.Error)
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                TextButton(onClick = { rows.retry() }) { Text(stringResource(R.string.documents_retry)) }
                            }
                        if (rows.itemCount == 0 && rows.loadState.refresh is LoadState.NotLoading)
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(stringResource(R.string.documents_empty), Modifier.padding(vertical = 24.dp))
                            }
                        items(rows.itemCount, key = rows.itemKey { it.key().saved() }) { index ->
                            rows[index]?.let { row ->
                                val id = row.key().saved()
                                val name = row.media.displayName ?: stringResource(R.string.documents_untitled)
                                Card(
                                    Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.extraLarge,
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                    ),
                                ) {
                                    Box {
                                        DocumentImage(
                                            row,
                                            thumbnailLoader,
                                            Modifier.fillMaxWidth().height(220.dp).clickable { focused = id },
                                        )
                                        Checkbox(
                                            checked = id in selection,
                                            enabled = !busy && (id in selection || selection.size < 24),
                                            onCheckedChange = { selected ->
                                                selection = ArrayList(if (selected) selection + id else selection - id)
                                            },
                                            modifier = Modifier.align(Alignment.TopStart).semantics { contentDescription = name },
                                        )
                                    }
                                    Column(
                                        Modifier.clickable { focused = id }.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                        AssistChip(
                                            onClick = { focused = id },
                                            label = {
                                                Text(
                                                    stringResource(
                                                        (row.category?.let { DocumentCategory.valueOf(it) } ?: DocumentCategory.Suggested).label(),
                                                    ) + if (row.archived) " · " + stringResource(R.string.documents_archived) else "",
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        if (rows.loadState.append is LoadState.Loading)
                            item(span = { GridItemSpan(maxLineSpan) }) { GalleryIndeterminateProgressIndicator() }
                        if (rows.loadState.append is LoadState.Error)
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                TextButton(onClick = { rows.retry() }) { Text(stringResource(R.string.documents_retry)) }
                            }
                    }
                }
            }
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize()) {
        com.librestatic.lightforge.core.designsystem.GalleryTopAppBar(
            title = stringResource(R.string.documents_title),
            onBack = { if (focused == null) onBack() else focused = null },
            navigationContentDescription = stringResource(R.string.documents_back),
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
        Column(Modifier.weight(1f).fillMaxWidth().widthIn(max = 1200.dp).padding(horizontal = 16.dp)) {
            if (focused == null) {
                LazyColumn(
                    Modifier.weight(1f).testTag("documents-list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    item {
                        Column { controls() }
                    }
                    if (rows.loadState.refresh is LoadState.Loading && rows.itemCount == 0)
                        item { GalleryIndeterminateProgressIndicator() }
                    if (rows.loadState.refresh is LoadState.Error)
                        item {
                            TextButton(onClick = { rows.retry() }) {
                                Text(stringResource(R.string.documents_retry))
                            }
                        }
                    if (rows.itemCount == 0 && rows.loadState.refresh is LoadState.NotLoading)
                        item {
                            Text(
                                stringResource(R.string.documents_empty),
                                Modifier.padding(vertical = 24.dp),
                            )
                        }
                    items(rows.itemCount, key = rows.itemKey { it.key().saved() }) { index ->
                        rows[index]?.let { row ->
                            val id = row.key().saved()
                            val selectionLabel =
                                row.media.displayName ?: stringResource(R.string.documents_untitled)
                            Card(Modifier.fillMaxWidth()) {
                                Row(
                                    Modifier.fillMaxWidth().padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = id in selection,
                                        enabled = !busy && (id in selection || selection.size < 24),
                                        onCheckedChange = { selected ->
                                            selection =
                                                ArrayList(
                                                    if (selected) selection + id else selection - id
                                                )
                                        },
                                        modifier =
                                            Modifier.semantics {
                                                contentDescription = selectionLabel
                                            },
                                    )
                                    Row(
                                        Modifier.weight(1f)
                                            .clickable { focused = id }
                                            .padding(4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        DocumentImage(row, thumbnailLoader, Modifier.size(64.dp))
                                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                            Text(
                                                row.media.displayName
                                                    ?: stringResource(R.string.documents_untitled),
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                stringResource(
                                                    (row.category?.let {
                                                            DocumentCategory.valueOf(it)
                                                        } ?: DocumentCategory.Suggested)
                                                        .label()
                                                ),
                                                style = MaterialTheme.typography.labelMedium,
                                            )
                                            if (row.archived)
                                                Text(
                                                    stringResource(R.string.documents_archived),
                                                    style = MaterialTheme.typography.labelMedium,
                                                )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (rows.loadState.append is LoadState.Loading)
                        item { GalleryIndeterminateProgressIndicator() }
                    if (rows.loadState.append is LoadState.Error)
                        item {
                            TextButton(onClick = { rows.retry() }) {
                                Text(stringResource(R.string.documents_retry))
                            }
                        }
                }
            } else {
                val row = detail
                if (row == null)
                    Text(stringResource(R.string.documents_unavailable), Modifier.padding(16.dp))
                else
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            row.media.displayName ?: stringResource(R.string.documents_untitled),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        DocumentImage(row, thumbnailLoader, Modifier.fillMaxWidth().height(220.dp))
                        Text(
                            stringResource(R.string.documents_category),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                    DocumentCategory.Receipt,
                                    DocumentCategory.Ticket,
                                    DocumentCategory.Note,
                                    DocumentCategory.Other,
                                )
                                .forEach { value ->
                                    FilterChip(
                                        selected = row.category == value.name,
                                        enabled = !busy,
                                        onClick = {
                                            mutate { repository.classify(listOf(row.key()), value) }
                                        },
                                        label = { Text(stringResource(value.label())) },
                                    )
                                }
                        }
                        Text(
                            stringResource(R.string.documents_text),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            row.ocrText?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.documents_no_text),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val documentsTextLabel = stringResource(R.string.documents_text)
                        OutlinedButton(
                            enabled = !row.ocrText.isNullOrBlank(),
                            onClick = {
                                val clip =
                                    ClipData.newPlainText(
                                        documentsTextLabel,
                                        row.ocrText,
                                    )
                                clip.description.extras =
                                    android.os.PersistableBundle().apply {
                                        putBoolean("android.content.extra.IS_SENSITIVE", true)
                                    }
                                context
                                    .getSystemService(ClipboardManager::class.java)
                                    .setPrimaryClip(clip)
                                scope.launch { snackbar.showSnackbar(copied) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.documents_copy))
                        }
                        Button(
                            enabled = !busy,
                            onClick = { mutate { onPdf(repository.pdfKeys(listOf(row.key()))) } },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.documents_prepare_pdf, 1))
                        }
                        OutlinedButton(
                            enabled = !busy,
                            onClick = {
                                mutate {
                                    val token = repository.archive(row.key(), !row.archived)
                                    // The snackbar waits for its dismissal; keep it out of the busy
                                    // window so the screen controls stay usable meanwhile.
                                    scope.launch {
                                        try {
                                            snackbar.currentSnackbarData?.dismiss()
                                            if (
                                                snackbar.showSnackbar(
                                                    changed,
                                                    actionLabel = undo,
                                                    duration = SnackbarDuration.Long,
                                                ) == SnackbarResult.ActionPerformed &&
                                                    !repository.undo(token)
                                            )
                                                snackbar.showSnackbar(failure)
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (_: Exception) {
                                            snackbar.showSnackbar(failure)
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(
                                    if (row.archived) R.string.documents_unarchive
                                    else R.string.documents_archive
                                )
                            )
                        }
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                mutate {
                                    repository.classify(
                                        listOf(row.key()),
                                        DocumentCategory.Excluded,
                                    )
                                    focused = null
                                    selection = ArrayList(selection - row.key().saved())
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.documents_exclude))
                        }
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                mutate {
                                    repository.clearClassification(row.key())
                                    focused = null
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.documents_reset))
                        }
                        Spacer(Modifier.height(16.dp))
                    }
            }
        }
        }
    }
    }
}

@Composable
private fun DocumentImage(row: DocumentRow, loader: ThumbnailLoader?, modifier: Modifier) =
    DocumentImage(row.key(), row.media.generationModified, loader, modifier)

@Composable
internal fun DocumentImage(
    key: MediaKey,
    generation: Long,
    loader: ThumbnailLoader?,
    modifier: Modifier,
) {
    val request = remember(key, generation) { ThumbnailRequest(key, generation, 512, 512) }
    val bitmap by
        produceState<android.graphics.Bitmap?>(null, request, loader) {
            value = null
            try {
                value = loader?.load(request)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
    Surface(
        modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        val image = bitmap
        if (image == null)
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    com.librestatic.lightforge.core.designsystem.GalleryIcons.Image,
                    stringResource(R.string.documents_photo),
                    modifier = Modifier.size(24.dp),
                )
            }
        else
            Image(image.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit)
    }
}

/** Primary document actions: tonal cards in the wide panel so they outweigh the filter chips. */
@Composable
private fun DocumentActionButton(
    tonal: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    if (!tonal) {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(title) }
        return
    }
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Icon(icon, null, Modifier.padding(10.dp).size(24.dp))
            }
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Icon(com.librestatic.lightforge.core.designsystem.GalleryIcons.ChevronForward, null)
        }
    }
}
