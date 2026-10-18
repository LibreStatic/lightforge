package com.librestatic.lightforge.core.model.folders

private class MutableFolderNode<T>(
    val volumeName: String,
    val relativePath: String?,
    val name: String,
) {
    var direct: T? = null
    val children = linkedMapOf<String, MutableFolderNode<T>>()

    fun freeze(order: Comparator<String>): FolderNode<T> = FolderNode(
        volumeName = volumeName,
        relativePath = relativePath,
        name = name,
        direct = direct,
        children = children.values.map { it.freeze(order) }.sortedWith(compareBy(order) { it.name }),
    )
}

/**
 * Builds a forest of folders from flat items.
 *
 * Items whose normalised path is null become roots keyed by volume and [fallbackKeyOf]; the others
 * are placed along their path, creating synthetic intermediate nodes. Roots sort with
 * `external_primary` first, then by volume name, then by [nameOrder]; children sort by [nameOrder].
 */
fun <T> buildFolderForest(
    items: List<T>,
    volumeOf: (T) -> String,
    relativePathOf: (T) -> String?,
    fallbackKeyOf: (T) -> String,
    fallbackNameOf: (T) -> String,
    normalizeRelativePath: (String?) -> String? = ::normalizeFolderPath,
    nameOrder: Comparator<String> = String.CASE_INSENSITIVE_ORDER,
): List<FolderNode<T>> {
    val roots = linkedMapOf<String, MutableFolderNode<T>>()
    items.forEach { item ->
        val volume = volumeOf(item)
        val normalizedPath = normalizeRelativePath(relativePathOf(item))
        if (normalizedPath == null) {
            roots["$volume|#${fallbackKeyOf(item)}"] = MutableFolderNode<T>(volume, null, fallbackNameOf(item))
                .also { it.direct = item }
            return@forEach
        }
        var parentChildren = roots
        val segments = normalizedPath.trimEnd('/').split('/')
        segments.forEachIndexed { index, segment ->
            val path = segments.take(index + 1).joinToString(separator = "/", postfix = "/")
            val node = parentChildren.getOrPut("$volume|$path") { MutableFolderNode(volume, path, segment) }
            if (index == segments.lastIndex) node.direct = item
            parentChildren = node.children
        }
    }
    return roots.values.map { it.freeze(nameOrder) }
        .sortedWith(
            compareBy<FolderNode<T>> { if (it.volumeName == PRIMARY_VOLUME) 0 else 1 }
                .thenBy { it.volumeName }
                .thenBy(nameOrder) { it.name },
        )
}

private const val PRIMARY_VOLUME = "external_primary"

/** Default path normalisation: backslashes become slashes, blank segments drop, trailing slash added. */
fun normalizeFolderPath(value: String?): String? {
    val segments = value?.replace('\\', '/')?.split('/')?.filter(String::isNotBlank).orEmpty()
    return segments.takeIf(List<String>::isNotEmpty)?.joinToString(separator = "/", postfix = "/")
}

/** Case-insensitive order where digit runs compare numerically: "img2" < "img10". */
val NaturalNameOrder: Comparator<String> = Comparator { a, b ->
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        if (a[i].isDigit() && b[j].isDigit()) {
            var ie = i
            while (ie < a.length && a[ie].isDigit()) ie++
            var je = j
            while (je < b.length && b[je].isDigit()) je++
            val x = a.substring(i, ie).trimStart('0')
            val y = b.substring(j, je).trimStart('0')
            val byLength = x.length.compareTo(y.length)
            if (byLength != 0) return@Comparator byLength
            val byDigits = x.compareTo(y)
            if (byDigits != 0) return@Comparator byDigits
            i = ie
            j = je
        } else {
            val byChar = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
            if (byChar != 0) return@Comparator byChar
            i++
            j++
        }
    }
    val rest = (a.length - i).compareTo(b.length - j)
    if (rest != 0) rest else String.CASE_INSENSITIVE_ORDER.compare(a, b).let { if (it != 0) it else a.compareTo(b) }
}
