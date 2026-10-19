package com.librestatic.lightforge.feature.folders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GalleryIcons

/**
 * The tree on the left of the large-screen explorer (plan 7.11). [rows] come from [buildFolderTreeRows];
 * [onToggle] opens or closes a row; a folder row is also a drop target for [drag]. Stateless.
 */
@Composable
internal fun FolderTreePane(
    rows: List<TreeRowUi>,
    onEnter: (FolderLocation) -> Unit,
    onToggle: (TreeRowUi) -> Unit,
    drag: ExplorerDragState,
    accepts: (ExplorerDragPayload, FolderLocation) -> Boolean,
    onDrop: ((ExplorerDragPayload, FolderLocation) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val paneLabel = stringResource(R.string.folders_tree_pane)
    val colors = MaterialTheme.colorScheme
    LazyColumn(
        modifier
            .background(colors.surfaceContainerLow)
            .testTag("folder-tree-pane")
            .semantics {
                contentDescription = paneLabel
                collectionInfo = CollectionInfo(rowCount = rows.size, columnCount = 1)
            },
        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(rows, key = { it.key }) { row ->
            TreeRow(row, onEnter, onToggle, drag, accepts, onDrop)
        }
    }
}

@Composable
private fun TreeRow(
    row: TreeRowUi,
    onEnter: (FolderLocation) -> Unit,
    onToggle: (TreeRowUi) -> Unit,
    drag: ExplorerDragState,
    accepts: (ExplorerDragPayload, FolderLocation) -> Boolean,
    onDrop: ((ExplorerDragPayload, FolderLocation) -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val title = row.name ?: volumeName(row.volumeName, row.volumeLabel)
    val expandedState = stringResource(R.string.folders_tree_expanded)
    val collapsedState = stringResource(R.string.folders_tree_collapsed)
    val toggleLabel = stringResource(if (row.expanded) R.string.folders_tree_collapse else R.string.folders_tree_expand, title)
    val dropModifier = if (onDrop != null) {
        Modifier.explorerDropTarget(drag, row.location, { accepts(it, row.location) }, onDrop)
    } else {
        Modifier
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.large)
            .background(if (row.isCurrent) colors.secondaryContainer else colors.surfaceContainerLow)
            .then(dropModifier)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !row.hasChildren) return@onKeyEvent false
                when {
                    event.key == Key.DirectionRight && !row.expanded -> { onToggle(row); true }
                    event.key == Key.DirectionLeft && row.expanded -> { onToggle(row); true }
                    else -> false
                }
            }
            .clickable(role = Role.Button) { onEnter(row.location) }
            .padding(start = (8 + 16 * row.depth).dp)
            .testTag("folder-tree-row-${row.key}")
            .semantics {
                selected = row.isCurrent
                if (row.hasChildren) stateDescription = if (row.expanded) expandedState else collapsedState
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.hasChildren) {
            IconButton(onClick = { onToggle(row) }, modifier = Modifier.size(40.dp).testTag("folder-tree-toggle-${row.key}")) {
                Icon(
                    GalleryIcons.ChevronForward,
                    contentDescription = toggleLabel,
                    modifier = Modifier.rotate(if (row.expanded) 90f else 0f),
                    tint = if (row.isCurrent) colors.onSecondaryContainer else colors.onSurfaceVariant,
                )
            }
        } else {
            Box(Modifier.width(40.dp))
        }
        Icon(
            GalleryIcons.Folder,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (row.isCurrent) colors.onSecondaryContainer else colors.onSurfaceVariant,
        )
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (row.isCurrent) colors.onSecondaryContainer else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp).weight(1f),
        )
        if (row.isHidden) HiddenBadge(Modifier.padding(end = 8.dp))
    }
}

