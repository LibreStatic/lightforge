package com.librestatic.lightforge.feature.folders

/** What the file order of a folder looks at. */
data class FileOrderKey(val sortMillis: Long, val name: String, val sizeBytes: Long)

internal fun HiddenItemUi.orderKey() = FileOrderKey(dateMillis, name, sizeBytes)

/** The hidden items the folder's file filter lets through. */
fun filterHiddenItems(items: List<HiddenItemUi>, filter: FolderFileFilter): List<HiddenItemUi> = when (filter) {
    FolderFileFilter.All -> items
    FolderFileFilter.Images -> items.filterNot { it.isVideo }
    FolderFileFilter.Videos -> items.filter { it.isVideo }
}

/** Orders two files the way the folder's [sort] does; negative when [a] comes first. */
fun compareFileOrder(sort: FolderFileSort, a: FileOrderKey, b: FileOrderKey): Int = when (sort) {
    FolderFileSort.NewestFirst -> b.sortMillis.compareTo(a.sortMillis)
    FolderFileSort.OldestFirst -> a.sortMillis.compareTo(b.sortMillis)
    FolderFileSort.NameAscending -> naturalCompare(a.name, b.name)
    FolderFileSort.NameDescending -> naturalCompare(b.name, a.name)
    FolderFileSort.SizeAscending -> a.sizeBytes.compareTo(b.sizeBytes)
    FolderFileSort.SizeDescending -> b.sizeBytes.compareTo(a.sizeBytes)
}

/** A hidden item and where it sits in the merged file grid. */
data class HiddenPlacement(val mergedIndex: Int, val item: HiddenItemUi)

/**
 * Places hidden files among the paged library files so the grid reads in one order. The library pages are
 * already sorted by [sort]; each hidden item goes in front of the first paged file that comes after it (a tie
 * keeps the library file first). While more pages may still load ([allPagedLoaded] false), a hidden item that
 * sorts after every loaded file waits, so it does not jump when the next page arrives. [pagedKeyAt] reads the
 * key of a loaded file. Returned in grid order; [HiddenPlacement.mergedIndex] counts the hidden items before it.
 */
fun placeHiddenItems(
    hidden: List<HiddenItemUi>,
    sort: FolderFileSort,
    pagedCount: Int,
    allPagedLoaded: Boolean,
    pagedKeyAt: (Int) -> FileOrderKey?,
): List<HiddenPlacement> {
    if (hidden.isEmpty()) return emptyList()
    val ordered = hidden.sortedWith { a, b -> compareFileOrder(sort, a.orderKey(), b.orderKey()) }
    val result = ArrayList<HiddenPlacement>(ordered.size)
    var pointer = 0
    for (item in ordered) {
        val key = item.orderKey()
        while (pointer < pagedCount) {
            val paged = pagedKeyAt(pointer) ?: break
            if (compareFileOrder(sort, paged, key) > 0) break
            pointer++
        }
        if (pointer >= pagedCount && !allPagedLoaded) break
        result += HiddenPlacement(pointer + result.size, item)
    }
    return result
}
