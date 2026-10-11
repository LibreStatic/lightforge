package com.librestatic.lightforge.feature.photoeditor

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GallerySpacing
import com.librestatic.lightforge.core.model.EditOperation
import kotlin.math.roundToInt

/** A filter the user made from a reference photo; [id] is a `custom:<uuid>` look id. */
data class PhotoCustomLook(val id: String, val name: String)

/** Built-in looks in carousel order with our own localized names. The ids are fixed by the renderer. */
internal val PhotoBuiltinLooks: List<Pair<String, Int>> = listOf(
    "builtin:vivid" to R.string.photo_editor_filter_vivid,
    "builtin:natural" to R.string.photo_editor_filter_natural,
    "builtin:warm" to R.string.photo_editor_look_warm,
    "builtin:cool" to R.string.photo_editor_look_cool,
    "builtin:golden" to R.string.photo_editor_look_golden,
    "builtin:film" to R.string.photo_editor_look_film,
    "builtin:fade" to R.string.photo_editor_look_fade,
    "builtin:teal_orange" to R.string.photo_editor_look_teal_orange,
    "builtin:pastel" to R.string.photo_editor_look_pastel,
    "builtin:dramatic" to R.string.photo_editor_look_dramatic,
    "builtin:mono" to R.string.photo_editor_filter_mono,
    "builtin:noir" to R.string.photo_editor_look_noir,
)

private val CardWidth = 92.dp

/** Filters tab: Filters / My filters pills, a thumbnail carousel and the intensity slider of the selected look. */
@Composable
internal fun FiltersControls(
    state: PhotoEditorContentState,
    tab: PhotoLooksTab,
    onTabChange: (PhotoLooksTab) -> Unit,
    actions: PhotoPanelActions,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
        FilterChip(
            selected = tab == PhotoLooksTab.Filters,
            onClick = { onTabChange(PhotoLooksTab.Filters) },
            label = { Text(stringResource(R.string.photo_editor_filters)) },
            modifier = Modifier.testTag("photo-looks-tab-filters"),
        )
        FilterChip(
            selected = tab == PhotoLooksTab.Mine,
            onClick = { onTabChange(PhotoLooksTab.Mine) },
            label = { Text(stringResource(R.string.photo_editor_looks_mine)) },
            modifier = Modifier.testTag("photo-looks-tab-mine"),
        )
    }
    val plain = state.originalPreview ?: state.preview
    fun select(look: EditOperation.Look?) {
        if (look?.id == state.look?.id) return
        actions.onLookPreview(look)
        actions.onLookChangeFinished()
    }
    when (tab) {
        PhotoLooksTab.Filters -> LazyRow(horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
            item(key = "none") {
                LookCard(
                    thumbnail = plain,
                    label = stringResource(R.string.photo_editor_look_none),
                    selected = state.look == null,
                    testTag = "photo-look-none",
                    onClick = { select(null) },
                )
            }
            items(PhotoBuiltinLooks, key = { it.first }) { (id, label) ->
                LookCard(
                    thumbnail = state.lookThumbnails[id] ?: plain,
                    label = stringResource(label),
                    selected = state.look?.id == id,
                    testTag = "photo-look-$id",
                    onClick = { select(EditOperation.Look(id, 1f)) },
                )
            }
        }
        PhotoLooksTab.Mine -> MyLooks(state, plain, ::select, actions)
    }
    state.look?.let { look ->
        LookIntensity(
            name = lookName(look.id, state.customLooks),
            intensity = look.intensity,
            onChange = { actions.onLookPreview(EditOperation.Look(look.id, it)) },
            onFinished = actions.onLookChangeFinished,
        )
    }
}

@Composable
private fun lookName(id: String, custom: List<PhotoCustomLook>): String =
    PhotoBuiltinLooks.firstOrNull { it.first == id }?.let { stringResource(it.second) }
        ?: custom.firstOrNull { it.id == id }?.name
        ?: stringResource(R.string.photo_editor_filters)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MyLooks(
    state: PhotoEditorContentState,
    plain: Bitmap?,
    onSelect: (EditOperation.Look?) -> Unit,
    actions: PhotoPanelActions,
) {
    var renaming by remember { mutableStateOf<PhotoCustomLook?>(null) }
    var deleting by remember { mutableStateOf<PhotoCustomLook?>(null) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
        item(key = "create") {
            Surface(
                onClick = actions.onCreateCustomLook,
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.width(CardWidth).testTag("photo-look-create"),
            ) {
                Column(
                    Modifier.padding(GallerySpacing.Sm).size(width = CardWidth, height = CardWidth + 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs, Alignment.CenterVertically),
                ) {
                    Icon(GalleryIcons.Plus, contentDescription = null)
                    Text(
                        stringResource(R.string.photo_editor_look_create),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
        items(state.customLooks, key = { it.id }) { look ->
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                LookCard(
                    thumbnail = state.lookThumbnails[look.id] ?: plain,
                    label = look.name,
                    selected = state.look?.id == look.id,
                    testTag = "photo-look-${look.id}",
                    onClick = { onSelect(EditOperation.Look(look.id, 1f)) },
                    onLongClick = { menuOpen = true },
                )
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(32.dp),
                ) {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.testTag("photo-look-menu-${look.id}"),
                    ) {
                        Icon(
                            GalleryIcons.More,
                            contentDescription = stringResource(R.string.photo_editor_look_options, look.name),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.photo_editor_look_rename)) },
                        leadingIcon = { Icon(GalleryIcons.Rename, contentDescription = null) },
                        onClick = { menuOpen = false; renaming = look },
                        modifier = Modifier.testTag("photo-look-rename"),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.photo_editor_look_delete)) },
                        leadingIcon = { Icon(GalleryIcons.Trash, contentDescription = null) },
                        onClick = { menuOpen = false; deleting = look },
                        modifier = Modifier.testTag("photo-look-delete"),
                    )
                }
            }
        }
    }
    renaming?.let { look ->
        RenameLookDialog(
            initial = look.name,
            onDismiss = { renaming = null },
            onConfirm = { actions.onRenameCustomLook(look.id, it); renaming = null },
        )
    }
    deleting?.let { look ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.photo_editor_look_delete_title)) },
            text = { Text(stringResource(R.string.photo_editor_look_delete_body, look.name)) },
            confirmButton = {
                TextButton(
                    onClick = { actions.onDeleteCustomLook(look.id); deleting = null },
                    modifier = Modifier.testTag("photo-look-delete-confirm"),
                ) { Text(stringResource(R.string.photo_editor_look_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.photo_editor_cancel)) }
            },
        )
    }
}

@Composable
private fun RenameLookDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.photo_editor_look_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                singleLine = true,
                label = { Text(stringResource(R.string.photo_editor_look_name)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().testTag("photo-look-name-field"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag("photo-look-rename-confirm"),
            ) { Text(stringResource(R.string.photo_editor_look_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.photo_editor_cancel)) }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LookCard(
    thumbnail: Bitmap?,
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier
            .width(CardWidth)
            .testTag(testTag)
            .semantics { this.selected = selected }
            .combinedClickable(role = Role.RadioButton, onClick = onClick, onLongClick = onLongClick),
    ) {
        Column {
            val image = remember(thumbnail) { thumbnail?.asImageBitmap() }
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(CardWidth),
                )
            } else {
                Box(Modifier.size(CardWidth).background(MaterialTheme.colorScheme.surfaceVariant))
            }
            Text(
                label,
                modifier = Modifier.padding(horizontal = GallerySpacing.Sm, vertical = GallerySpacing.Xs).fillMaxWidth(),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                minLines = 2,
            )
        }
    }
}

@Composable
private fun LookIntensity(name: String, intensity: Float, onChange: (Float) -> Unit, onFinished: () -> Unit) {
    val label = stringResource(R.string.photo_editor_look_intensity)
    Column(verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("$label · $name", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text("${(intensity * 100).roundToInt()}", style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = intensity * 100f,
            onValueChange = { onChange(it.roundToInt() / 100f) },
            onValueChangeFinished = onFinished,
            valueRange = 0f..100f,
            modifier = Modifier.fillMaxWidth().testTag("photo-look-intensity").semantics { contentDescription = label },
        )
    }
}
