package com.librestatic.lightforge.core.model.folders

/**
 * A folder in a hierarchy derived from "DCIM/Camera/" style relative paths.
 *
 * Nodes without [direct] are synthetic intermediate folders (a parent that only has sub-folders).
 * Path-less items are roots with a null [relativePath].
 */
data class FolderNode<T>(
    val volumeName: String,
    val relativePath: String?,
    val name: String,
    val direct: T?,
    val children: List<FolderNode<T>>,
) {
    /** Direct payloads of this node and all descendants, parent first. */
    val allDirect: List<T> = buildList {
        direct?.let(::add)
        children.forEach { addAll(it.allDirect) }
    }
}

/** One segment of a breadcrumb trail. */
data class FolderCrumb(val name: String, val relativePath: String)

/**
 * Folds a payload over this node's whole subtree. [selfOf] runs for nodes that have a direct payload;
 * synthetic nodes contribute [empty].
 */
fun <T, R> FolderNode<T>.aggregate(empty: R, selfOf: (T) -> R, combine: (R, R) -> R): R {
    var result = direct?.let(selfOf) ?: empty
    children.forEach { result = combine(result, it.aggregate(empty, selfOf, combine)) }
    return result
}

fun <T> FolderNode<T>.find(volumeName: String, relativePath: String): FolderNode<T>? {
    if (this.volumeName == volumeName && this.relativePath == relativePath) return this
    return children.firstNotNullOfOrNull { it.find(volumeName, relativePath) }
}

fun <T> List<FolderNode<T>>.find(volumeName: String, relativePath: String): FolderNode<T>? =
    firstNotNullOfOrNull { it.find(volumeName, relativePath) }

/** Breadcrumb for a normalised "A/B/C/" path: A/, A/B/, A/B/C/. Empty for a null or blank path. */
fun breadcrumb(relativePath: String?): List<FolderCrumb> {
    val segments = relativePath?.trim('/')?.split('/')?.filter(String::isNotEmpty).orEmpty()
    return segments.mapIndexed { index, segment ->
        FolderCrumb(segment, segments.take(index + 1).joinToString(separator = "/", postfix = "/"))
    }
}

/**
 * VS Code style "compact folders": a chain of nodes that each have exactly one child and no direct
 * payload collapses into one node named `A/B/C` that carries the deepest node's path, payload and
 * children. A node with direct items ends the chain (it is the deepest node of the chain).
 */
fun <T> FolderNode<T>.compactSingleChildChains(): FolderNode<T> {
    var name = name
    var node = this
    while (node.direct == null && node.children.size == 1) {
        val only = node.children.single()
        name = "$name/${only.name}"
        node = only
    }
    return node.copy(name = name, children = node.children.map { it.compactSingleChildChains() })
}

fun <T> List<FolderNode<T>>.compactSingleChildChains(): List<FolderNode<T>> =
    map { it.compactSingleChildChains() }
