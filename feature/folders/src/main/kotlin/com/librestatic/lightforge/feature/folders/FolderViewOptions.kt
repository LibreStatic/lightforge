package com.librestatic.lightforge.feature.folders

/** What the folder cards of a level are ordered by. Names compare naturally ("IMG 2" before "IMG 10"). */
enum class FolderSortKey { Name, Items, Size, Date }

/** Whether folder cards are shown as a grid of covers or as a one-row-per-folder list. */
enum class FolderViewMode { Grid, List }

/**
 * How one level of the explorer is presented. Pinned folders always come first, then the sort applies.
 * Stored per folder; a folder without its own entry uses the default entry.
 */
data class FolderViewOptions(
    val sort: FolderSortKey = FolderSortKey.Name,
    val descending: Boolean = false,
    val view: FolderViewMode = FolderViewMode.Grid,
) {
    /** Compact text form kept in preferences. */
    fun encode(): String = "${sort.name}|${if (descending) 1 else 0}|${view.name}"

    companion object {
        fun decode(text: String?): FolderViewOptions? {
            val parts = text?.split('|') ?: return null
            if (parts.size != 3) return null
            return FolderViewOptions(
                sort = FolderSortKey.entries.firstOrNull { it.name == parts[0] } ?: return null,
                descending = parts[1] == "1",
                view = FolderViewMode.entries.firstOrNull { it.name == parts[2] } ?: return null,
            )
        }

        /** Preference key for a folder's own options, or the default entry for a null location. */
        fun keyOf(location: FolderLocation?): String =
            if (location?.volumeName == null) "default" else "${location.volumeName}|${normalizedFolderPath(location.relativePath.orEmpty())}"
    }
}

/** The identity a pin is stored under. */
fun pinKeyOf(location: FolderLocation): String? {
    val volume = location.volumeName ?: return null
    val path = normalizedFolderPath(location.relativePath.orEmpty())
    return if (path.isEmpty()) null else "$volume|$path"
}

/** Orders [cards]: pinned first, then by [options]; ties fall back to the natural name order. */
fun sortFolderCards(cards: List<FolderCardUi>, options: FolderViewOptions): List<FolderCardUi> {
    val byKey: Comparator<FolderCardUi> = when (options.sort) {
        FolderSortKey.Name -> Comparator { a, b -> naturalCompare(a.name, b.name) }
        FolderSortKey.Items -> compareBy { it.itemCount }
        FolderSortKey.Size -> compareBy { it.sizeBytes }
        FolderSortKey.Date -> compareBy { it.maxSortMillis ?: Long.MIN_VALUE }
    }
    val directed = if (options.descending) byKey.reversed() else byKey
    val tieBreak = Comparator<FolderCardUi> { a, b -> naturalCompare(a.name, b.name) }
    return cards.sortedWith(compareByDescending<FolderCardUi> { it.isPinned }.then(directed).then(tieBreak))
}

/** Case-insensitive comparison where runs of digits compare by value. */
fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var ie = i
            while (ie < a.length && a[ie].isDigit()) ie++
            var je = j
            while (je < b.length && b[je].isDigit()) je++
            val na = a.substring(i, ie).trimStart('0')
            val nb = b.substring(j, je).trimStart('0')
            if (na.length != nb.length) return na.length.compareTo(nb.length)
            val cmp = na.compareTo(nb)
            if (cmp != 0) return cmp
            i = ie
            j = je
        } else {
            val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (cmp != 0) return cmp
            i++
            j++
        }
    }
    return (a.length - i).compareTo(b.length - j)
}

/**
 * Where a typed or pasted path leads: the folder inside [volumeName], or null when [text] is blank.
 * A leading `/storage/emulated/0`, `/sdcard`, `/storage/<id>` or `Internal storage` prefix is dropped,
 * backslashes and repeated slashes are tolerated and `..` segments are refused (they return null).
 */
fun parseTypedPath(text: String, volumeName: String): FolderLocation? {
    var path = text.trim().replace('\\', '/')
    if (path.isEmpty()) return null
    val prefixes = listOf(Regex("^/storage/emulated/\\d+"), Regex("^/storage/[^/]+"), Regex("^/sdcard"), Regex("^/mnt/sdcard"))
    for (prefix in prefixes) {
        val match = prefix.find(path)
        if (match != null) {
            path = path.substring(match.range.last + 1)
            break
        }
    }
    val segments = path.split('/').filter { it.isNotBlank() }
    if (segments.any { it == ".." }) return null
    val clean = segments.filter { it != "." }
    return FolderLocation(volumeName, clean.takeIf { it.isNotEmpty() }?.joinToString("/", postfix = "/"))
}

/** The ancestors of [location], the folder itself first and the top-level folder last; the volume root is never listed. */
fun excludableChain(location: FolderLocation): List<FolderLocation> {
    val volume = location.volumeName ?: return emptyList()
    val segments = normalizedFolderPath(location.relativePath.orEmpty()).trim('/').split('/').filter { it.isNotEmpty() }
    return (segments.size downTo 1).map { n -> FolderLocation(volume, segments.take(n).joinToString("/", postfix = "/")) }
}
