package com.librestatic.lightforge.feature.folders

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GallerySelectionAction
import com.librestatic.lightforge.core.designsystem.GallerySelectionBar
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.TimelineMedia
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader

/**
 * What the explorer offers in every mode, with or without All files access: pinning, the folder rules
 * (exclude or include a folder and everything below it) and the search field.
 */
class FolderBrowseActions(
    val onTogglePin: (FolderCardUi) -> Unit,
    /** Excludes ([excluded] true) or includes the folder at the location, together with everything below it. */
    val onSetExcluded: (FolderLocation, Boolean) -> Unit,
    val onQueryChange: (String) -> Unit,
    val onCloseSearch: () -> Unit,
    /** The search field is open. */
    val searching: Boolean = false,
)

/**
 * Mixed selection. Folder cards are selected here (by [FolderCardUi.key]); media tiles are selected in the
 * gallery's own selection, whose bar carries the file actions, so only the lookup is needed.
 */
class ExplorerSelection(
    val folders: Set<String> = emptySet(),
    val mediaSelecting: Boolean = false,
    val isMediaSelected: (MediaKey) -> Boolean = { false },
    val onToggleFolder: (FolderCardUi) -> Unit = {},
    val onToggleMedia: (TimelineMedia) -> Unit = {},
    val onSelectAllFolders: () -> Unit = {},
    val onClearFolders: () -> Unit = {},
    /** The bulk folder actions; each acts on the selected cards. */
    val onPinFolders: (Boolean) -> Unit = {},
    val onExcludeFolders: (Boolean) -> Unit = {},
    val onHideFolders: ((Boolean) -> Unit)? = null,
    val onDeleteFolders: (() -> Unit)? = null,
) {
    val selectingFolders: Boolean get() = folders.isNotEmpty()
    val selecting: Boolean get() = selectingFolders || mediaSelecting

    companion object {
        val None = ExplorerSelection()
    }
}

/** Remembers where each folder's grid was scrolled; the host keeps one so it also survives the process. */
interface ScrollMemory {
    fun get(key: String): Pair<Int, Int>?
    fun set(key: String, position: Pair<Int, Int>)
}

class InMemoryScrollMemory : ScrollMemory {
    private val positions = HashMap<String, Pair<Int, Int>>()
    override fun get(key: String) = positions[key]
    override fun set(key: String, position: Pair<Int, Int>) {
        positions[key] = position
    }
}

/** Encodes scroll positions as "key\tindex\toffset" lines for saved state. */
fun encodeScrollPositions(positions: Map<String, Pair<Int, Int>>): List<String> =
    positions.entries.toList().takeLast(MaxRememberedScrolls).map { (key, value) -> "$key\t${value.first}\t${value.second}" }

fun decodeScrollPositions(lines: List<String>?): Map<String, Pair<Int, Int>> =
    lines.orEmpty().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size != 3) return@mapNotNull null
        val index = parts[1].toIntOrNull() ?: return@mapNotNull null
        val offset = parts[2].toIntOrNull() ?: return@mapNotNull null
        parts[0] to (index to offset)
    }.toMap()

private const val MaxRememberedScrolls = 64

/** A ring and check that mark a selected card or tile. Uses the primary pair only. */
@Composable
internal fun SelectionCheck(selected: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = if (selected) colors.primary else colors.surfaceContainerHighest,
        contentColor = if (selected) colors.onPrimary else colors.onSurfaceVariant,
        modifier = modifier.size(24.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (selected) GalleryIcons.Check else GalleryIcons.Unchecked,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
internal fun ExcludedBadge(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.secondaryContainer,
        contentColor = colors.onSecondaryContainer,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        modifier = modifier.testTag("folder-excluded-badge"),
    ) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(GalleryIcons.Block, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(stringResource(R.string.folders_excluded_badge), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun PinnedBadge(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val description = stringResource(R.string.folders_pinned_description)
    Surface(
        color = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer,
        shape = CircleShape,
        modifier = modifier.size(24.dp).testTag("folder-pinned-badge").semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(GalleryIcons.Pin, contentDescription = null, modifier = Modifier.size(14.dp))
        }
    }
}

/** The quick folder-name filter above the breadcrumb: a labelled field with a clear button. */
@Composable
internal fun FolderSearchField(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        label = { Text(stringResource(R.string.folders_search_label)) },
        leadingIcon = { Icon(GalleryIcons.Search, contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = onClose, modifier = Modifier.testTag("folder-search-close")) {
                Icon(GalleryIcons.Close, contentDescription = stringResource(R.string.folders_search_close))
            }
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).focusRequester(focus).testTag("folder-search-field"),
    )
}

/** Type or paste a path to jump to; the host clamps it to the nearest existing folder. */
@Composable
internal fun PathEntryDialog(currentPath: String, onDismiss: () -> Unit, onGo: (String) -> Unit) {
    var text by remember { mutableStateOf(currentPath) }
    val parsed = parseTypedPath(text, "x")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folders_path_dialog_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.folders_path_field)) },
                supportingText = { Text(stringResource(R.string.folders_path_example)) },
                singleLine = true,
                isError = text.isNotBlank() && parsed == null,
                modifier = Modifier.fillMaxWidth().testTag("folder-path-field"),
            )
        },
        confirmButton = {
            TextButton(onClick = { onGo(text) }, enabled = parsed != null, modifier = Modifier.testTag("folder-path-go")) {
                Text(stringResource(R.string.folders_path_go))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.folders_cancel)) } },
    )
}

/** Which of the folder and its ancestors to exclude (the user's "exclude the parent?" choice). */
@Composable
internal fun ExcludeDialog(folder: FolderCardUi, onDismiss: () -> Unit, onConfirm: (FolderLocation) -> Unit) {
    val chain = remember(folder.location) { excludableChain(folder.location) }
    var chosen by remember(folder.location) { mutableStateOf(chain.firstOrNull()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folders_exclude_title)) },
        text = {
            Column {
                Text(stringResource(R.string.folders_exclude_body), style = MaterialTheme.typography.bodyMedium)
                Column(Modifier.selectableGroup().padding(top = 8.dp)) {
                    chain.forEachIndexed { index, location ->
                        val label = location.relativePath.orEmpty().trim('/')
                        ListItem(
                            headlineContent = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(stringResource(if (index == 0) R.string.folders_exclude_this else R.string.folders_exclude_parent))
                            },
                            leadingContent = { RadioButton(selected = chosen == location, onClick = null) },
                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                            modifier = Modifier
                                .selectable(selected = chosen == location, role = Role.RadioButton) { chosen = location }
                                .testTag("folder-exclude-option-$index"),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { chosen?.let(onConfirm) },
                enabled = chosen != null,
                modifier = Modifier.testTag("folder-exclude-confirm"),
            ) { Text(stringResource(R.string.folders_exclude_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.folders_cancel)) } },
    )
}

@Composable
internal fun IncludeDialog(folder: FolderCardUi, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folders_include_title)) },
        text = { Text(stringResource(R.string.folders_include_body, folder.name)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("folder-include-confirm")) {
                Text(stringResource(R.string.folders_include_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.folders_cancel)) } },
    )
}

/** Sort key, direction and grid or list for the level; every change applies at once and is stored per folder. */
@Composable
fun FolderViewOptionsDialog(options: FolderViewOptions, onChange: (FolderViewOptions) -> Unit, onDismiss: () -> Unit) {
    val transparent = androidx.compose.ui.graphics.Color.Transparent
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folders_view_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.folders_view_sort_header),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Column(Modifier.selectableGroup()) {
                    FolderSortKey.entries.forEach { key ->
                        ListItem(
                            headlineContent = { Text(stringResource(key.labelRes())) },
                            leadingContent = { RadioButton(selected = options.sort == key, onClick = null) },
                            colors = ListItemDefaults.colors(containerColor = transparent),
                            modifier = Modifier
                                .selectable(selected = options.sort == key, role = Role.RadioButton) { onChange(options.copy(sort = key)) }
                                .testTag("folder-sort-${key.name}"),
                        )
                    }
                }
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.folders_view_descending)) },
                    supportingContent = { Text(stringResource(R.string.folders_view_descending_supporting)) },
                    trailingContent = { Switch(checked = options.descending, onCheckedChange = null) },
                    colors = ListItemDefaults.colors(containerColor = transparent),
                    modifier = Modifier
                        .clickable(role = Role.Switch) { onChange(options.copy(descending = !options.descending)) }
                        .testTag("folder-sort-descending"),
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.folders_view_as_list)) },
                    supportingContent = { Text(stringResource(R.string.folders_view_as_list_supporting)) },
                    trailingContent = { Switch(checked = options.view == FolderViewMode.List, onCheckedChange = null) },
                    colors = ListItemDefaults.colors(containerColor = transparent),
                    modifier = Modifier
                        .clickable(role = Role.Switch) {
                            onChange(options.copy(view = if (options.view == FolderViewMode.List) FolderViewMode.Grid else FolderViewMode.List))
                        }
                        .testTag("folder-view-list"),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("folder-view-done")) { Text(stringResource(R.string.folders_view_done)) } },
    )
}

private fun FolderSortKey.labelRes(): Int = when (this) {
    FolderSortKey.Name -> R.string.folders_sort_name
    FolderSortKey.Items -> R.string.folders_sort_items
    FolderSortKey.Size -> R.string.folders_sort_size
    FolderSortKey.Date -> R.string.folders_sort_date
}

/** The bar shown over the folder list while cards are selected. */
@Composable
internal fun FolderSelectionBar(
    selection: ExplorerSelection,
    selected: List<FolderCardUi>,
    modifier: Modifier = Modifier,
) {
    val allPinned = selected.isNotEmpty() && selected.all { it.isPinned }
    val allExcluded = selected.isNotEmpty() && selected.all { it.isExcluded }
    val anyHidden = selected.any { it.isHidden }
    val actions = buildList {
        add(
            GallerySelectionAction(
                stringResource(if (allPinned) R.string.folders_menu_unpin else R.string.folders_menu_pin),
                GalleryIcons.Pin, { selection.onPinFolders(!allPinned) }, testTag = "folder-selection-pin",
            ),
        )
        add(
            GallerySelectionAction(
                stringResource(if (allExcluded) R.string.folders_menu_include else R.string.folders_menu_exclude),
                GalleryIcons.Block, { selection.onExcludeFolders(!allExcluded) }, testTag = "folder-selection-exclude",
            ),
        )
        selection.onHideFolders?.let { hide ->
            add(
                GallerySelectionAction(
                    stringResource(if (anyHidden && selected.all { it.isHidden }) R.string.folders_menu_unhide else R.string.folders_menu_hide),
                    GalleryIcons.VisibilityOff, { hide(!(selected.all { it.isHidden })) }, inline = false, testTag = "folder-selection-hide",
                ),
            )
        }
        selection.onDeleteFolders?.let { delete ->
            add(
                GallerySelectionAction(
                    stringResource(R.string.folders_menu_delete), GalleryIcons.Trash, delete, inline = false, testTag = "folder-selection-delete",
                ),
            )
        }
        add(
            GallerySelectionAction(
                stringResource(R.string.folders_select_all), GalleryIcons.SelectAll, selection.onSelectAllFolders,
                inline = false, testTag = "folder-selection-all",
            ),
        )
    }
    GallerySelectionBar(
        count = selection.folders.size.toLong(),
        onClear = selection.onClearFolders,
        actions = actions,
        modifier = modifier.padding(bottom = 16.dp),
        clearLabel = stringResource(R.string.folders_select_clear),
        clearTestTag = "folder-selection-clear",
    )
}

/** One folder as a row for the list view: small cover, name and summary, badges and the menu slot. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FolderRow(
    folder: FolderCardUi,
    loader: ThumbnailLoader?,
    selecting: Boolean,
    selected: Boolean,
    picking: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    menu: (@Composable () -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val selectedState = stringResource(R.string.folders_selected_state)
    val itemCount = folder.itemCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val items = pluralStringResource(R.plurals.folders_item_count, itemCount, itemCount)
    val emptyText = stringResource(R.string.folders_empty_directory)
    val supporting = buildString {
        append(if (folder.isEmptyDirectory) emptyText else items + " · " + Formatter.formatShortFileSize(context, folder.sizeBytes))
        folder.pathLabel?.let { append(" · ").append(it) }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (folder.isExcluded) 0.6f else 1f)
            .testTag("folder-card-${folder.key}")
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(role = Role.Button, onLongClick = onLongClick, onClick = onClick)
            .semantics { if (selecting) { this.selected = selected; if (selected) stateDescription = selectedState } },
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh),
        border = if (selected) BorderStroke(2.dp, colors.primary) else null,
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box {
                Cover(folder.cover, loader, Modifier.size(56.dp).clip(MaterialTheme.shapes.small))
                if (selecting) SelectionCheck(selected, Modifier.align(Alignment.TopStart).padding(2.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (folder.isPinned) PinnedBadge()
                    Text(folder.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if ((folder.isHidden && !picking) || folder.isExcluded) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (folder.isHidden && !picking) HiddenBadge(Modifier)
                        if (folder.isExcluded) ExcludedBadge()
                    }
                }
            }
            menu?.invoke()
        }
    }
}
