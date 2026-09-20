package com.ugallery.feature.collections

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.ugallery.core.data.DocumentCategory
import com.ugallery.core.data.GalleryDocumentRepository
import com.ugallery.core.database.DocumentRow
import com.ugallery.core.model.MediaKey
import com.ugallery.core.thumbnail.ThumbnailLoader
import com.ugallery.core.thumbnail.ThumbnailRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier.testTag("documents-screen").semantics { testTagsAsResourceId = true },
    ) { padding ->
        Column(
            Modifier.padding(padding)
                .fillMaxSize()
                .widthIn(max = 1200.dp)
                .padding(horizontal = 16.dp)
        ) {
            TextButton(
                onClick = { if (focused == null) onBack() else focused = null },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.documents_back))
            }
            Text(
                stringResource(R.string.documents_title),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { heading() },
            )
            if (focused == null) {
                LazyColumn(
                    Modifier.weight(1f).testTag("documents-list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    item {
                        Column {
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
                                OutlinedButton(
                                    onClick = onPdfStudio,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                ) {
                                    Text(stringResource(R.string.documents_studio))
                                }
                            }
                            OutlinedButton(
                                onClick = { showAutoArchive = true },
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .testTag("documents-auto-archive"),
                            ) {
                                Text(stringResource(R.string.document_auto_title))
                            }
                            Text(
                                stringResource(R.string.documents_selection_hint),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 6.dp),
                            )
                        }
                    }
                    if (rows.loadState.refresh is LoadState.Loading)
                        item { CircularProgressIndicator() }
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
                        item { CircularProgressIndicator() }
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
                        OutlinedButton(
                            enabled = !row.ocrText.isNullOrBlank(),
                            onClick = {
                                val clip =
                                    ClipData.newPlainText(
                                        context.getString(R.string.documents_text),
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
                                    if (
                                        snackbar.showSnackbar(changed, actionLabel = undo) ==
                                            SnackbarResult.ActionPerformed &&
                                            !repository.undo(token)
                                    )
                                        snackbar.showSnackbar(failure)
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
                    com.ugallery.core.designsystem.GalleryIcons.Image,
                    stringResource(R.string.documents_photo),
                    modifier = Modifier.size(24.dp),
                )
            }
        else
            Image(image.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit)
    }
}
