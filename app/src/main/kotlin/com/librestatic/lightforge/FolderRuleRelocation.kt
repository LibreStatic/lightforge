package com.librestatic.lightforge

import com.librestatic.lightforge.core.preferences.FolderSelectionTarget

/**
 * Folder rules follow a folder that was renamed or moved: a path rule at or under the old prefix gets
 * the new prefix, and a bucket rule gets the folder's new bucket id. Without this an excluded or
 * private folder would lose its rule on rename and reappear in the timeline.
 *
 * [bucketMap] maps each old bucket id to the id the same folder has after the move; a bucket that is
 * not in it (no item was found under the new path) keeps its rule, because MediaStore may yet reuse it.
 */
internal fun relocateFolderRules(
    rules: Map<FolderSelectionTarget, Boolean>,
    oldVolume: String,
    oldPrefix: String,
    newVolume: String,
    newPrefix: String,
    bucketMap: Map<Long, Long>,
): Map<FolderSelectionTarget, Boolean> {
    if (rules.isEmpty()) return rules
    val old = oldPrefix.trim('/') + "/"
    val new = newPrefix.trim('/') + "/"
    val result = LinkedHashMap<FolderSelectionTarget, Boolean>()
    val moved = LinkedHashMap<FolderSelectionTarget, Boolean>()
    for ((target, selected) in rules) {
        val relocated = when (target) {
            is FolderSelectionTarget.Path ->
                if (target.volumeName == oldVolume && target.relativePath.startsWith(old)) {
                    FolderSelectionTarget.Path(newVolume, new + target.relativePath.removePrefix(old))
                } else {
                    null
                }
            is FolderSelectionTarget.Bucket ->
                if (target.volumeName == oldVolume) {
                    bucketMap[target.bucketId]?.let { FolderSelectionTarget.Bucket(newVolume, it) }
                } else {
                    null
                }
        }
        if (relocated == null) result[target] = selected else moved[relocated] = selected
    }
    // A rule already set on the new location wins over one that was carried over.
    for ((target, selected) in moved) result.putIfAbsent(target, selected)
    return result
}

/** Old bucket id to new bucket id for the folders of [before] (relative path to bucket) that moved. */
internal fun bucketRelocation(
    before: Map<String, Long>,
    oldPrefix: String,
    newPrefix: String,
    after: Map<String, Long>,
): Map<Long, Long> {
    val old = oldPrefix.trim('/') + "/"
    val new = newPrefix.trim('/') + "/"
    val map = HashMap<Long, Long>()
    for ((path, bucket) in before) {
        if (!path.startsWith(old)) continue
        val newBucket = after[new + path.removePrefix(old)] ?: continue
        map[bucket] = newBucket
    }
    return map
}
