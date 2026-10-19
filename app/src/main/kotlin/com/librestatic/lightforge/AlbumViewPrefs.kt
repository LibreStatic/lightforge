package com.librestatic.lightforge

import com.librestatic.lightforge.core.database.AlbumMediaFilter
import com.librestatic.lightforge.core.database.AlbumSort
import com.librestatic.lightforge.core.model.AlbumKey

/**
 * The sort and type filter of one album screen, remembered per album. It shares the settings map the folder
 * explorer keeps its per-folder options in (`folderViewOptions`), under keys no folder identity can take, so a
 * settings export carries both.
 */
internal object AlbumViewPrefs {
    val Default = AlbumMediaFilter.All to AlbumSort.NewestFirst

    fun keyOf(key: AlbumKey): String = when (key) {
        is AlbumKey.Physical -> "album|physical|${key.volumeName}|${key.bucketId}"
        is AlbumKey.Virtual -> "album|virtual|${key.albumId}"
    }

    fun encode(filter: AlbumMediaFilter, sort: AlbumSort): String = "${filter.name}|${sort.name}"

    /** The stored pair, or null when nothing (or something unreadable) is stored. */
    fun decode(text: String?): Pair<AlbumMediaFilter, AlbumSort>? {
        val parts = text?.split('|') ?: return null
        if (parts.size != 2) return null
        val filter = AlbumMediaFilter.entries.firstOrNull { it.name == parts[0] } ?: return null
        val sort = AlbumSort.entries.firstOrNull { it.name == parts[1] } ?: return null
        return filter to sort
    }
}
