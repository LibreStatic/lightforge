package com.ugallery.feature.collections

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.ugallery.core.designsystem.GalleryIcons
import com.ugallery.core.designsystem.GalleryTopAppBar
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.MediaKind
import com.ugallery.core.model.TimelineMedia
import com.ugallery.core.thumbnail.NativeImageDecoder
import com.ugallery.core.thumbnail.ThumbnailLoader
import com.ugallery.core.thumbnail.ThumbnailRequest
import kotlinx.coroutines.CancellationException

/** Draft-only UI. The caller owns source revalidation and atomic, idempotent persistence. */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ManualMomentContent(
    draftId: String,
    sources: List<TimelineMedia>,
    busy: Boolean,
    error: Boolean,
    onCreate: (title: String, orderedKeys: List<MediaKey>, includeSpecialMedia: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    var title by rememberSaveable(draftId) { mutableStateOf("") }
    var includeSpecial by rememberSaveable(draftId) { mutableStateOf(false) }
    // Only bounded primitive identities enter SavedState, never media objects or bitmaps.
    var order by rememberSaveable(draftId) { mutableStateOf(sources.map { manualSourceId(it.key) }.distinct()) }
    var submitting by remember(draftId) { mutableStateOf(false) }
    // A rejected callback may finish before one Compose frame observes busy=true. Include
    // submitting so repeated fast failures (same error=true) cannot strand the draft disabled.
    LaunchedEffect(busy, error, submitting) { if (!busy) submitting = false }
    val working = busy || submitting
    val byId = sources.associateBy { manualSourceId(it.key) }
    val valid = sources.size in 1..120 && byId.size == sources.size && order.isNotEmpty() &&
        order.distinct().size == order.size && order.all { id ->
            byId[id]?.let { it.kind == MediaKind.Image && !it.isTrashed } == true
        }
    val context = LocalContext.current.applicationContext
    val loader = remember(context) {
        ThumbnailLoader.native(context, NativeImageDecoder(context.contentResolver), 4L * 1024 * 1024)
    }
    DisposableEffect(loader) { onDispose { loader.close() } }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val imeVisible = WindowInsets.isImeVisible
    BackHandler {
        // A system Back dismisses the live keyboard before it can discard this review.
        // Keep this handler installed: falling through can invoke the caller's route Back.
        if (imeVisible) {
            keyboard?.hide()
            focusManager.clearFocus()
        } else if (!busy && !submitting) onCancel()
    }
    fun move(index: Int, delta: Int) {
        if (!busy && !submitting && index + delta in order.indices) {
            order = order.toMutableList().apply { add(index + delta, removeAt(index)) }
        }
    }
    Surface(
        Modifier.fillMaxSize().testTag("manual-moment-screen").semantics { testTagsAsResourceId = true },
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            GalleryTopAppBar(
                title = stringResource(R.string.manual_moment_title),
                onBack = { if (!busy && !submitting) onCancel() },
                navigationContentDescription = stringResource(R.string.manual_moment_cancel),
            )
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f).testTag("manual-moment-list"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item("intro") { Text(stringResource(R.string.manual_moment_explanation)) }
                item("title") {
                    OutlinedTextField(title, { if (!busy && !submitting) title = it.take(120) },
                        modifier = Modifier.fillMaxWidth().testTag("manual-moment-title"),
                        label = { Text(stringResource(R.string.manual_moment_title_label)) },
                        placeholder = { Text(stringResource(R.string.manual_moment_title_hint)) },
                        singleLine = true, enabled = !working)
                }
                item("consent") {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().toggleable(value = includeSpecial, enabled = !working,
                                role = Role.Checkbox, onValueChange = { if (!busy && !submitting) includeSpecial = it })
                                .padding(vertical = 8.dp).testTag("manual-moment-include-special"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = includeSpecial, onCheckedChange = null, enabled = !working)
                            Text(stringResource(R.string.manual_moment_include_special), Modifier.weight(1f))
                        }
                        Text(stringResource(R.string.manual_moment_include_special_body), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (error || !valid) item("error") {
                    Surface(color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.small) {
                        Text(stringResource(if (order.isEmpty()) R.string.manual_moment_empty else R.string.manual_moment_error),
                            Modifier.fillMaxWidth().padding(12.dp).testTag("manual-moment-error")
                                .semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
                itemsIndexed(order, key = { _, id -> id }) { index, id ->
                    val media = byId[id]
                    Card(Modifier.fillMaxWidth().testTag("manual-moment-source-$id")) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ManualMomentImage(media, loader, id, Modifier.fillMaxWidth().height(156.dp))
                            Text(stringResource(R.string.manual_moment_photo, index + 1, order.size),
                                style = MaterialTheme.typography.labelLarge)
                            if (media?.displayName != null) Text(media.displayName!!, style = MaterialTheme.typography.bodyMedium)
                            else if (media == null) Text(stringResource(R.string.manual_moment_unavailable))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { move(index, -1) }, enabled = !working && index > 0,
                                    modifier = Modifier.testTag("manual-moment-up-$id")) { Text(stringResource(R.string.manual_moment_up)) }
                                TextButton(onClick = { move(index, 1) }, enabled = !working && index < order.lastIndex,
                                    modifier = Modifier.testTag("manual-moment-down-$id")) { Text(stringResource(R.string.manual_moment_down)) }
                                TextButton(onClick = { if (!busy && !submitting && order.size > 1) order = order.filterNot { it == id } },
                                    enabled = !working && order.size > 1,
                                    modifier = Modifier.testTag("manual-moment-remove-$id")) { Text(stringResource(R.string.manual_moment_remove)) }
                            }
                        }
                    }
                }
            }
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("manual-moment-progress"))
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { if (!busy && !submitting) onCancel() }, enabled = !working,
                    modifier = Modifier.testTag("manual-moment-cancel")) { Text(stringResource(R.string.manual_moment_cancel)) }
                Button(onClick = {
                    if (!busy && !submitting && valid) {
                        submitting = true
                        onCreate(title.trim(), order.map { checkNotNull(byId[it]).key }, includeSpecial)
                    }
                }, enabled = !working && valid, modifier = Modifier.testTag("manual-moment-save")) {
                    Text(stringResource(if (working) R.string.manual_moment_working else R.string.manual_moment_save))
                }
            }
        }
    }
}

private fun manualSourceId(key: MediaKey): String = "${key.volumeName}:${key.mediaStoreId}"

@Composable
private fun ManualMomentImage(media: TimelineMedia?, loader: ThumbnailLoader, id: String, modifier: Modifier) {
    key(media?.key, media?.generationModified, loader) {
        val bitmap by produceState<ImageBitmap?>(null, media?.key, media?.generationModified, loader) {
            if (media != null && media.kind == MediaKind.Image && !media.isTrashed) try {
                value = loader.load(ThumbnailRequest(media.key, media.generationModified, 384, 384)).asImageBitmap()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { value = null }
        }
        Box(modifier, contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(bitmap!!, contentDescription = null,
                modifier = Modifier.fillMaxSize().testTag("manual-moment-preview-$id"), contentScale = ContentScale.Crop)
            else Icon(GalleryIcons.Image, contentDescription = stringResource(R.string.manual_moment_unavailable), modifier = Modifier.size(48.dp))
        }
    }
}
