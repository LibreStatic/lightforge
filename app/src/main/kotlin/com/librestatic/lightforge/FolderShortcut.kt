package com.librestatic.lightforge

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.librestatic.lightforge.feature.folders.FolderLocation
import com.librestatic.lightforge.feature.folders.normalizedFolderPath

/** Action of the intent a pinned folder shortcut sends; it opens the explorer at that folder. */
internal const val ACTION_OPEN_FOLDER = "com.librestatic.lightforge.action.OPEN_FOLDER"
internal const val EXTRA_FOLDER_VOLUME = "com.librestatic.lightforge.extra.FOLDER_VOLUME"
internal const val EXTRA_FOLDER_PATH = "com.librestatic.lightforge.extra.FOLDER_PATH"

/** Whether the launcher can pin a shortcut at all. */
internal fun canPinFolderShortcut(context: Context): Boolean = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

/**
 * The folder an intent asks to open, or null when it is no folder shortcut. A volume without a path is the
 * volume root; a path is stored normalised ("A/B/"). The intent can come from any app, so nothing in it is
 * trusted beyond naming a place in the explorer, which falls back to the nearest folder that exists.
 */
internal fun folderLocationFromShortcut(action: String?, volume: String?, path: String?): FolderLocation? {
    if (action != ACTION_OPEN_FOLDER) return null
    val volumeName = volume?.takeIf { it.isNotBlank() && it.length <= MaxShortcutField } ?: return null
    val relative = path?.takeIf { it.length <= MaxShortcutField }?.let(::normalizedFolderPath)?.takeIf { it.isNotEmpty() }
    return FolderLocation(volumeName, relative)
}

internal fun Intent.folderShortcutLocation(): FolderLocation? =
    folderLocationFromShortcut(action, getStringExtra(EXTRA_FOLDER_VOLUME), getStringExtra(EXTRA_FOLDER_PATH))

/** Asks the launcher to pin a shortcut that opens [location]; false when the launcher cannot. */
internal fun pinFolderShortcut(context: Context, name: String, location: FolderLocation): Boolean {
    val volume = location.volumeName ?: return false
    if (!canPinFolderShortcut(context)) return false
    val intent = Intent(ACTION_OPEN_FOLDER)
        .setClass(context, MainActivity::class.java)
        .putExtra(EXTRA_FOLDER_VOLUME, volume)
        .putExtra(EXTRA_FOLDER_PATH, location.relativePath.orEmpty())
    val id = "folder:$volume|${location.relativePath.orEmpty()}"
    val shortcut = ShortcutInfoCompat.Builder(context, id)
        .setShortLabel(name.take(MaxShortLabel))
        .setLongLabel(name)
        .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
        .setIntent(intent)
        .build()
    return ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
}

private const val MaxShortcutField = 1024
private const val MaxShortLabel = 24
