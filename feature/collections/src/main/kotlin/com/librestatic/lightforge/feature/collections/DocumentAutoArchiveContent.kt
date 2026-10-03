package com.librestatic.lightforge.feature.collections

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.data.*
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.librestatic.lightforge.core.designsystem.GalleryIndeterminateProgressIndicator
import com.librestatic.lightforge.core.designsystem.GalleryProgressSlot

@OptIn(ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun DocumentAutoArchiveContent(
    repository: DocumentAutoArchiveRepository,
    thumbnailLoader: ThumbnailLoader?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by remember(repository) { repository.state() }.collectAsState(initial = null)
    var categoryName by rememberSaveable { mutableStateOf<String?>(null) }
    var age by rememberSaveable { mutableStateOf<Int?>(null) }
    var preview by remember { mutableStateOf<DocumentArchivePreview?>(null) }
    var keep by remember { mutableStateOf(emptySet<MediaKey>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val category = DocumentCategory.valueOf(categoryName ?: state?.category ?: "Receipt")
    val minimumAge = age ?: state?.minimumAgeDays ?: 30
    fun mutate(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: DocumentArchivePreviewChanged) {
                preview = null
                error = R.string.document_auto_stale
            } catch (_: Exception) {
                error = R.string.documents_error
            } finally {
                busy = false
            }
        }
    }
    BackHandler { if (preview != null) preview = null else onBack() }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier =
            modifier.testTag("document-auto-screen").semantics { testTagsAsResourceId = true },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding)
                .fillMaxSize()
                .testTag(if (state == null) "document-auto-loading" else "document-auto-list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                TextButton(
                    onClick = { if (preview != null) preview = null else onBack() },
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.documents_back))
                }
                Text(
                    stringResource(R.string.document_auto_title),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
            }
            item { Text(stringResource(R.string.document_auto_explanation)) }
            if (state == null) item { GalleryIndeterminateProgressIndicator() }
            else {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                stringResource(
                                    if (state!!.enabled) R.string.document_auto_enabled
                                    else R.string.document_auto_paused
                                ),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                stringResource(
                                    R.string.document_auto_active_rule,
                                    stringResource(
                                        DocumentCategory.valueOf(state!!.category).label()
                                    ),
                                    state!!.minimumAgeDays,
                                )
                            )
                            if (state!!.lastRunId != null)
                                Text(
                                    pluralStringResource(R.plurals.document_auto_last_run, state!!.lastRunCount.toInt(), state!!.lastRunCount)
                                )
                        }
                    }
                }
                if (preview == null) {
                    item {
                        Text(
                            stringResource(R.string.documents_category),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DocumentCategory.entries
                                .filter { it != DocumentCategory.Excluded }
                                .forEach { item ->
                                    FilterChip(
                                        selected = category == item,
                                        enabled = !busy,
                                        onClick = { categoryName = item.name },
                                        label = { Text(stringResource(item.label())) },
                                    )
                                }
                        }
                        Text(
                            stringResource(R.string.document_auto_age),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DocumentAutoArchiveRepository.Ages.forEach { days ->
                                FilterChip(
                                    selected = minimumAge == days,
                                    enabled = !busy,
                                    onClick = { age = days },
                                    label = {
                                        Text(
                                            if (days == 0)
                                                stringResource(R.string.document_auto_any_age)
                                            else pluralStringResource(R.plurals.document_auto_days, days.toInt(), days)
                                        )
                                    },
                                )
                            }
                        }
                    }
                    item {
                        Button(
                            onClick = {
                                mutate {
                                    keep = emptySet()
                                    preview = repository.preview(category, minimumAge)
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().testTag("document-auto-review"),
                        ) {
                            Text(stringResource(R.string.document_auto_review))
                        }
                    }
                    if (state!!.enabled)
                        item {
                            OutlinedButton(
                                onClick = { mutate { repository.pause() } },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth().testTag("document-auto-pause"),
                            ) {
                                Text(stringResource(R.string.document_auto_pause))
                            }
                        }
                    if (state!!.lastRunId != null)
                        item {
                            OutlinedButton(
                                onClick = { mutate { repository.undoLastRun() } },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth().testTag("document-auto-undo"),
                            ) {
                                Text(stringResource(R.string.document_auto_undo))
                            }
                        }
                } else {
                    val snapshot = preview!!
                    item {
                        Text(
                            stringResource(
                                R.string.document_auto_preview,
                                snapshot.items.size - keep.size,
                            ),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(stringResource(R.string.document_auto_preview_rule,
                            stringResource(snapshot.category.label()), snapshot.minimumAgeDays))
                        Text(stringResource(R.string.document_auto_future))
                        if (snapshot.hasMore) Text(stringResource(R.string.document_auto_more))
                        if (snapshot.items.isEmpty())
                            Text(stringResource(R.string.document_auto_empty))
                    }
                    items(snapshot.items, key = { "${it.volumeName}:${it.mediaStoreId}" }) { row ->
                        val key = row.key()
                        val keepLabel =
                            stringResource(
                                R.string.document_auto_keep_named,
                                row.displayName ?: stringResource(R.string.documents_untitled),
                            )
                        Card(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                DocumentImage(
                                    key,
                                    row.generationModified,
                                    thumbnailLoader,
                                    Modifier.size(56.dp),
                                )
                                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                    Text(
                                        row.displayName
                                            ?: stringResource(R.string.documents_untitled)
                                    )
                                    Text(
                                        stringResource(R.string.document_auto_keep),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                                Checkbox(
                                    checked = key in keep,
                                    enabled = !busy,
                                    onCheckedChange = { keep = if (it) keep + key else keep - key },
                                    modifier = Modifier.semantics { contentDescription = keepLabel },
                                )
                            }
                        }
                    }
                    item {
                        Button(
                            onClick = {
                                mutate {
                                    repository.enable(snapshot, keep)
                                    preview = null
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().testTag("document-auto-confirm"),
                        ) {
                            Text(stringResource(R.string.document_auto_confirm))
                        }
                        TextButton(
                            onClick = { preview = null },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.document_auto_cancel))
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
}
