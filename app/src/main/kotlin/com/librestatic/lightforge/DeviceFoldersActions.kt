package com.librestatic.lightforge

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.librestatic.lightforge.core.designsystem.GalleryDropdownMenu
import com.librestatic.lightforge.core.designsystem.GalleryMenuItem
import com.librestatic.lightforge.core.designsystem.GalleryMenuDivider
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Top-bar actions of the "On device" screen: Search, New folder (advanced mode only) and an overflow with "Sort and view",
 * "Show hidden items for now" (advanced mode only), "Include subfolders", "Merge single-folder chains" and Refresh.
 */
@Composable
internal fun RowScope.DeviceFoldersActions(
    chrome: FolderChrome,
    onNewFolder: () -> Unit,
    onSearch: () -> Unit,
    onSortAndView: () -> Unit,
    onSearchInFolder: () -> Unit,
    onTemporaryShowHiddenChange: (Boolean) -> Unit,
    onIncludeSubfoldersChange: (Boolean) -> Unit,
    onCompactChainsChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    if (chrome.canBrowseOptions && !chrome.searching) {
        IconButton(onClick = onSearch, modifier = Modifier.testTag("device-folders-search")) {
            Icon(GalleryIcons.Search, contentDescription = stringResource(R.string.device_folders_search))
        }
    }
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
        GalleryDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (chrome.canShowHidden) {
                CheckedItem(
                    text = stringResource(R.string.device_folders_show_hidden),
                    checked = chrome.temporaryShowHidden,
                    tag = "device-folders-show-hidden",
                    icon = GalleryIcons.Visibility,
                ) { expanded = false; onTemporaryShowHiddenChange(!chrome.temporaryShowHidden) }
            }
            if (chrome.canSearchInFolder) {
                GalleryMenuItem(
                    text = stringResource(R.string.device_folders_search_in_folder),
                    onClick = { expanded = false; onSearchInFolder() },
                    icon = GalleryIcons.Search,
                    testTag = "device-folders-search-in-folder",
                )
            }
            if (chrome.canBrowseOptions) {
                GalleryMenuItem(
                    text = stringResource(R.string.device_folders_sort_view),
                    onClick = { expanded = false; onSortAndView() },
                    icon = GalleryIcons.Sort,
                    testTag = "device-folders-sort-view",
                )
            }
            if (chrome.canShowHidden || chrome.canSearchInFolder || chrome.canBrowseOptions) GalleryMenuDivider()
            CheckedItem(
                text = stringResource(R.string.device_folders_include_subfolders),
                checked = chrome.includeSubfolders,
                tag = "device-folders-include-subfolders",
                icon = GalleryIcons.Folder,
            ) { expanded = false; onIncludeSubfoldersChange(!chrome.includeSubfolders) }
            CheckedItem(
                text = stringResource(R.string.device_folders_compact_chains),
                checked = chrome.compactChains,
                tag = "device-folders-compact-chains",
                icon = GalleryIcons.Layers,
            ) { expanded = false; onCompactChainsChange(!chrome.compactChains) }
            GalleryMenuDivider()
            GalleryMenuItem(
                text = stringResource(R.string.device_folders_refresh),
                onClick = { expanded = false; onRefresh() },
                icon = GalleryIcons.Refresh,
                testTag = "device-folders-refresh",
            )
        }
    }
}

@Composable
private fun CheckedItem(text: String, checked: Boolean, tag: String, icon: ImageVector, onClick: () -> Unit) {
    GalleryMenuItem(
        text = text,
        onClick = onClick,
        icon = icon,
        checked = checked,
        checkedDescription = stringResource(R.string.device_folders_checked),
        testTag = tag,
    )
}
