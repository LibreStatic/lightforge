package com.librestatic.lightforge

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.librestatic.lightforge.core.designsystem.GalleryIcons

/**
 * Top-bar actions of the "On device" screen: New folder (advanced mode only) and an overflow with
 * "Show hidden items for now" (advanced mode only), "Include subfolders" and Refresh.
 */
@Composable
internal fun RowScope.DeviceFoldersActions(
    chrome: FolderChrome,
    onNewFolder: () -> Unit,
    onTemporaryShowHiddenChange: (Boolean) -> Unit,
    onIncludeSubfoldersChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    if (chrome.canCreateFolder) {
        IconButton(onClick = onNewFolder, modifier = Modifier.testTag("device-folders-new-folder")) {
            Icon(GalleryIcons.CreateNewFolder, contentDescription = stringResource(R.string.device_folders_new_folder))
        }
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("device-folders-overflow")) {
            Icon(GalleryIcons.More, contentDescription = stringResource(R.string.device_folders_more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (chrome.canShowHidden) {
                CheckedItem(
                    text = stringResource(R.string.device_folders_show_hidden),
                    checked = chrome.temporaryShowHidden,
                    tag = "device-folders-show-hidden",
                ) { expanded = false; onTemporaryShowHiddenChange(!chrome.temporaryShowHidden) }
            }
            CheckedItem(
                text = stringResource(R.string.device_folders_include_subfolders),
                checked = chrome.includeSubfolders,
                tag = "device-folders-include-subfolders",
            ) { expanded = false; onIncludeSubfoldersChange(!chrome.includeSubfolders) }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.device_folders_refresh)) },
                onClick = { expanded = false; onRefresh() },
                modifier = Modifier.testTag("device-folders-refresh"),
            )
        }
    }
}

@Composable
private fun CheckedItem(text: String, checked: Boolean, tag: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        trailingIcon = if (checked) {
            { Icon(GalleryIcons.Check, contentDescription = stringResource(R.string.device_folders_checked)) }
        } else null,
        onClick = onClick,
        modifier = Modifier.testTag(tag),
    )
}
