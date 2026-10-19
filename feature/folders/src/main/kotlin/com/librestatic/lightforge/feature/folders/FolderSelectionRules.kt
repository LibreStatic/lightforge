package com.librestatic.lightforge.feature.folders

/**
 * What the selection bar offers for the ticked folder cards and files (plan 7.8). Pure, so the rules are
 * tested without a screen.
 *
 * @property copy Copy the selection to a destination folder.
 * @property move Move the selection to a destination folder.
 * @property rename Rename the one selected folder.
 * @property delete Delete the folders (and move the selected files to the Trash).
 * @property hide Hide the folders; [unhide] undoes a hide this app made.
 * @property exclude Exclude the folders from the library; [include] puts them back.
 */
data class SelectionCapabilities(
    val pin: Boolean = false,
    val unpin: Boolean = false,
    val exclude: Boolean = false,
    val include: Boolean = false,
    val hide: Boolean = false,
    val unhide: Boolean = false,
    val copy: Boolean = false,
    val move: Boolean = false,
    val rename: Boolean = false,
    val delete: Boolean = false,
    val addToAlbum: Boolean = false,
    val cover: Boolean = false,
    val resetCover: Boolean = false,
    val shortcut: Boolean = false,
    val properties: Boolean = false,
)

/**
 * @param selected the ticked folder cards.
 * @param fileCount how many files are ticked next to them.
 * @param advanced the advanced storage mode is active: folders can only be changed on disk with it.
 * @param canPinShortcut the launcher can pin a shortcut.
 */
fun selectionCapabilities(
    selected: List<FolderCardUi>,
    fileCount: Long,
    advanced: Boolean,
    canPinShortcut: Boolean = false,
): SelectionCapabilities {
    if (selected.isEmpty()) return SelectionCapabilities()
    val hasFiles = fileCount > 0
    val single = selected.size == 1 && !hasFiles
    val inAndroidTree = selected.any { it.location.relativePath?.let(::isAndroidAppTree) == true }
    val standard = selected.any { isStandardFolder(it.location.relativePath) }
    val allPinned = selected.all { it.isPinned }
    val allExcluded = selected.all { it.isExcluded }
    val allHidden = selected.all { it.isHidden }
    val allUnhidable = allHidden && selected.all { it.isHiddenByApp }
    val hideable = advanced && !inAndroidTree && !standard && selected.none { it.isHidden || it.isEmptyDirectory }
    val folderOps = advanced && !inAndroidTree
    return SelectionCapabilities(
        pin = !allPinned,
        unpin = allPinned,
        exclude = !allExcluded,
        include = allExcluded,
        hide = hideable,
        unhide = advanced && !inAndroidTree && allUnhidable,
        copy = folderOps && selected.none { it.isEmptyDirectory },
        move = folderOps && !standard,
        rename = single && folderOps && !standard,
        delete = folderOps && selected.all { it.isDeletable() },
        addToAlbum = selected.any { it.itemCount > 0 } || hasFiles,
        cover = single && selected.single().itemCount > 0,
        resetCover = single && selected.single().hasCustomCover,
        shortcut = single && canPinShortcut,
        properties = true,
    )
}

/** Totals for the properties of several ticked folders. */
data class SelectionTotals(val folders: Int, val items: Long, val bytes: Long, val subfolders: Int)

fun selectionTotals(selected: List<FolderCardUi>): SelectionTotals = SelectionTotals(
    folders = selected.size,
    items = selected.sumOf { it.itemCount },
    bytes = selected.sumOf { it.sizeBytes },
    subfolders = selected.sumOf { it.subfolderCount },
)
