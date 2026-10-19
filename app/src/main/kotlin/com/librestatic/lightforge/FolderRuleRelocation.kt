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

/**
 * The key (`volume|path/`, see the explorer's pin key) of a folder after the folder at [oldPrefix] was renamed
 * or moved to [newPrefix]: a key at or under the old prefix gets the new one, any other key is returned as is.
 */
internal fun relocateFolderKey(key: String, oldVolume: String, oldPrefix: String, newVolume: String, newPrefix: String): String {
    val volume = key.substringBefore('|', "")
    val path = key.substringAfter('|', "")
    val old = oldPrefix.trim('/') + "/"
    val new = newPrefix.trim('/') + "/"
    if (volume != oldVolume || !path.startsWith(old)) return key
    return "$newVolume|" + new + path.removePrefix(old)
}

/** Keeps pins and other keyed sets pointing at a renamed or moved folder; a key already at the new place wins. */
internal fun relocateFolderKeys(keys: Set<String>, oldVolume: String, oldPrefix: String, newVolume: String, newPrefix: String): Set<String> =
    keys.mapTo(LinkedHashSet()) { relocateFolderKey(it, oldVolume, oldPrefix, newVolume, newPrefix) }

/** Same for maps keyed by folder (covers); an entry already set on the new location wins over a carried one. */
internal fun <V> relocateFolderKeyedMap(
    map: Map<String, V>,
    oldVolume: String,
    oldPrefix: String,
    newVolume: String,
    newPrefix: String,
): Map<String, V> {
    if (map.isEmpty()) return map
    val result = LinkedHashMap<String, V>()
    val moved = LinkedHashMap<String, V>()
    for ((key, value) in map) {
        val target = relocateFolderKey(key, oldVolume, oldPrefix, newVolume, newPrefix)
        if (target == key) result[key] = value else moved[target] = value
    }
    for ((key, value) in moved) result.putIfAbsent(key, value)
    return result
}
