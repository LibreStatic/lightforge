package com.librestatic.lightforge.feature.folders

import androidx.compose.ui.input.key.Key
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.folders.FolderNode
import com.librestatic.lightforge.core.model.folders.FolderStats
import com.librestatic.lightforge.core.model.folders.FolderTree
import com.librestatic.lightforge.core.model.folders.breadcrumb
import com.librestatic.lightforge.core.model.folders.compactSingleChildChains

// The pure side of the large-screen explorer (plan 7.11): the rows of the tree pane, what a drag carries and
// where it may be dropped, and the keyboard shortcuts. The composables only draw what these decide.

/** The explorer shows the tree pane next to the content once the available width reaches this many dp. */
const val TreePaneMinWidthDp = 720

/**
 * One row of the tree pane. A null [name] is a volume root, whose label the UI resolves from [volumeName]
 * and [volumeLabel].
 */
data class TreeRowUi(
    val key: String,
    val name: String?,
    val volumeName: String,
    val volumeLabel: String?,
    val location: FolderLocation,
    val depth: Int,
    val hasChildren: Boolean,
    val expanded: Boolean,
    /** The folder the content pane shows. */
    val isCurrent: Boolean,
    val isHidden: Boolean = false,
)

/** The key a location has in the set of expanded tree rows. */
fun treeKeyOf(location: FolderLocation): String = "${location.volumeName.orEmpty()}|${location.relativePath.orEmpty()}"

/**
 * The tree pane as a flat list: every volume root, and below an expanded row its folders (single-child
 * chains merged like the cards when [compactChains], so a row and a card lead to the same place). A row
 * is expanded when its [treeKeyOf] is in [expanded].
 */
fun buildFolderTreeRows(
    tree: FolderTree,
    current: FolderLocation,
    expanded: Set<String>,
    spaces: Map<String, VolumeSpace> = emptyMap(),
    compactChains: Boolean = true,
): List<TreeRowUi> {
    val resolved = resolveLocation(tree, current)
    val rows = ArrayList<TreeRowUi>()
    fun walk(node: FolderNode<FolderStats>, depth: Int, label: String?) {
        val location = FolderLocation(node.volumeName, node.relativePath)
        val key = treeKeyOf(location)
        val isOpen = key in expanded && node.children.isNotEmpty()
        rows += TreeRowUi(
            key = key,
            name = if (node.relativePath == null) null else node.name,
            volumeName = node.volumeName,
            volumeLabel = label,
            location = location,
            depth = depth,
            hasChildren = node.children.isNotEmpty(),
            expanded = isOpen,
            isCurrent = location == resolved,
            isHidden = node.direct?.isHidden == true,
        )
        if (isOpen) {
            node.children.map { if (compactChains) it.compactSingleChildChains() else it }.forEach { walk(it, depth + 1, null) }
        }
    }
    tree.volumes.forEach { walk(it, 0, spaces[it.volumeName]?.label) }
    return rows
}

/** The rows to open so that [location] is visible: its volume root and every folder above it. */
fun treeAncestorKeys(location: FolderLocation): Set<String> {
    val volume = location.volumeName ?: return emptySet()
    val keys = LinkedHashSet<String>()
    keys += treeKeyOf(FolderLocation(volume, null))
    breadcrumb(location.relativePath).dropLast(1).forEach { keys += treeKeyOf(FolderLocation(volume, it.relativePath)) }
    return keys
}

/** What a drag carries: folder cards, files, or both. */
data class ExplorerDragPayload(
    val folders: List<FolderLocation> = emptyList(),
    /** The files that were dragged by themselves. */
    val files: List<MediaKey> = emptyList(),
    /** The ticked files travel along (the dragged item was one of the ticked ones); the host resolves them. */
    val withSelectedFiles: Boolean = false,
) {
    val isEmpty: Boolean get() = folders.isEmpty() && files.isEmpty() && !withSelectedFiles
}

/** The payload of a drag that starts on a folder card: the ticked cards when it is one of them, else only it. */
fun dragPayloadForFolder(card: FolderCardUi, cards: List<FolderCardUi>, selection: ExplorerSelection): ExplorerDragPayload =
    if (card.key in selection.folders) {
        ExplorerDragPayload(
            folders = cards.filter { it.key in selection.folders }.map { it.location },
            withSelectedFiles = selection.fileCount > 0,
        )
    } else {
        ExplorerDragPayload(folders = listOf(card.location))
    }

/** The payload of a drag that starts on a file: everything ticked when it is ticked, else only it. */
fun dragPayloadForFile(
    key: MediaKey,
    cards: List<FolderCardUi>,
    selection: ExplorerSelection,
): ExplorerDragPayload =
    if (selection.isMediaSelected(key)) {
        ExplorerDragPayload(
            folders = cards.filter { it.key in selection.folders }.map { it.location },
            withSelectedFiles = true,
        )
    } else {
        ExplorerDragPayload(files = listOf(key))
    }

/**
 * Whether the drag may be dropped on [destination], which is a move: the folder rules of the destination
 * picker apply (not into itself, a descendant or its parent; not across volumes; never into Android/), the
 * standard top-level folders and the Android tree cannot be dragged, and files dropped on the folder they
 * already sit in ([filesIn]) change nothing. Folders can only be moved with the advanced storage mode
 * ([advanced]).
 */
fun ExplorerDragPayload.canDropOn(destination: FolderLocation, filesIn: FolderLocation?, advanced: Boolean): Boolean {
    if (isEmpty || destination.volumeName == null) return false
    val hasFiles = files.isNotEmpty() || withSelectedFiles
    if (folders.isNotEmpty()) {
        if (!advanced) return false
        if (folders.any { isStandardFolder(it.relativePath) || it.relativePath?.let(::isAndroidAppTree) == true }) return false
    }
    if (folders.isEmpty() && hasFiles && destination == filesIn) return false
    val rules = ExplorerMode.PickDestination(PickPurpose.Move, folders, withFiles = hasFiles)
    return rules.canChoose(destination)
}

/** What a key press does in the explorer. */
enum class ExplorerShortcut { Up, SelectAll, Delete, ClearSelection }

/**
 * The shortcut for a key press, or null. Arrow keys and Enter are left to the focus system, which moves
 * between the folders and files and opens the focused one.
 */
fun explorerShortcutFor(key: Key, ctrl: Boolean, alt: Boolean, selecting: Boolean): ExplorerShortcut? = when {
    key == Key.Backspace && !ctrl && !alt -> ExplorerShortcut.Up
    alt && !ctrl && (key == Key.DirectionUp || key == Key.DirectionLeft) -> ExplorerShortcut.Up
    ctrl && !alt && key == Key.A -> ExplorerShortcut.SelectAll
    key == Key.Delete && !ctrl && !alt -> ExplorerShortcut.Delete
    key == Key.Escape && selecting -> ExplorerShortcut.ClearSelection
    else -> null
}
