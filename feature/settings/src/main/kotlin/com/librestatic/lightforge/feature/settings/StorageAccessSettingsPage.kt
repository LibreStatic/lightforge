package com.librestatic.lightforge.feature.settings

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GallerySpacing
import java.text.DateFormat
import java.util.Date

/**
 * "Storage access" settings page (ADR-046). Content only: the caller wraps it in the settings sub-page
 * chrome. The Resume refresh lives with the state owner, which also re-reads the system permissions.
 */
@Composable
fun StorageAccessSettingsContent(
    state: StorageAccessUiState,
    actions: StorageAccessActions,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var confirmEnable by rememberSaveable { mutableStateOf(false) }
    var confirmTurnOff by rememberSaveable { mutableStateOf(false) }
    var hiddenListUnlocked by rememberSaveable { mutableStateOf(false) }
    var treeGrantPath by rememberSaveable { mutableStateOf<String?>(null) }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val path = treeGrantPath
        treeGrantPath = null
        if (uri != null && path != null) actions.onUnhideWithTree(path, uri)
    }
    val includePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) actions.onIncludeFolder(uri)
    }
    // Opening the system picker on the folder itself saves the user from browsing to it.
    val chooseFolder: (String) -> Unit = { path ->
        treeGrantPath = path
        val initial = treeDocumentIdOf(path)?.let {
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", it)
        }
        runCatching { treePicker.launch(initial) }.onFailure { treeGrantPath = null }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xxl)) {
        state.message?.let { message ->
            NoticeCard(
                text = when (message.kind) {
                    StorageAccessMessageKind.PermissionNeeded -> stringResource(R.string.storage_access_message_permission)
                    StorageAccessMessageKind.IncludeFailed -> stringResource(R.string.storage_access_include_failed)
                    StorageAccessMessageKind.NotAllUnhidden -> pluralStringResource(
                        R.plurals.storage_access_message_not_unhidden, message.count, message.count,
                    )
                },
                action = if (message.kind == StorageAccessMessageKind.PermissionNeeded && state.advancedSupported) {
                    stringResource(R.string.storage_access_open_system) to { context.startSafely(actions.permissionIntent()) }
                } else null,
                tag = "storage-access-message",
            )
        }
        if (state.manageMediaSupported) ManageMediaSection(state, actions, context)
        if (state.advancedSupported) {
            AdvancedSection(
                state = state,
                actions = actions,
                context = context,
                onRequestEnable = { confirmEnable = true },
                requireUnlock = actions.requireUnlock,
                onRequestTurnOff = {
                    when (turnOffAction(state.hidden.size)) {
                        TurnOffAction.TurnOffDirectly -> actions.onSetEnabled(false)
                        TurnOffAction.AskAboutHiddenFolders -> confirmTurnOff = true
                    }
                },
            )
        }
        if (state.advancedActive) {
            ScannedFoldersSection(
                state = state,
                actions = actions,
                onAdd = { runCatching { includePicker.launch(null) } },
            )
        }
        if (state.hidden.isNotEmpty()) {
            if (state.hiddenItemsProtected && !hiddenListUnlocked) {
                LockedHiddenFoldersSection(onShow = { actions.requireUnlock { hiddenListUnlocked = true } })
            } else {
                HiddenFoldersSection(state, actions, chooseFolder)
            }
        }
    }

    if (confirmEnable) {
        AlertDialog(
            onDismissRequest = { confirmEnable = false },
            title = { Text(stringResource(R.string.storage_access_enable_title)) },
            text = { Text(stringResource(R.string.storage_access_enable_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmEnable = false
                        actions.onSetEnabled(true)
                        if (!state.allFilesGranted) context.startSafely(actions.permissionIntent())
                    },
                    modifier = Modifier.testTag("storage-access-enable-confirm"),
                ) { Text(stringResource(R.string.storage_access_enable_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmEnable = false }) { Text(stringResource(R.string.storage_access_cancel)) }
            },
        )
    }
    if (confirmTurnOff) {
        TurnOffDialog(
            state = state,
            onDismiss = { confirmTurnOff = false },
            onConfirm = { unhide ->
                confirmTurnOff = false
                if (unhide) actions.onUnhideAndTurnOff() else actions.onSetEnabled(false)
            },
        )
    }
}

/**
 * Asks what to do with the items Lightforge hid before the mode goes off. Without the permission nothing can be
 * unhidden, so the checkbox is off and disabled and the dialog says why.
 */
@Composable
private fun TurnOffDialog(state: StorageAccessUiState, onDismiss: () -> Unit, onConfirm: (unhide: Boolean) -> Unit) {
    var unhideNow by rememberSaveable { mutableStateOf(true) }
    val canUnhide = state.allFilesGranted
    val checked = unhideNow && canUnhide
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("storage-access-turn-off-dialog"),
        title = { Text(stringResource(R.string.storage_access_turn_off_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
                Text(hiddenCountSentence(state.hidden))
                if (!canUnhide) {
                    Text(
                        stringResource(R.string.storage_access_turn_off_no_permission),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .toggleable(
                            value = checked,
                            enabled = canUnhide,
                            role = Role.Checkbox,
                            onValueChange = { unhideNow = it },
                        )
                        .testTag("storage-access-turn-off-unhide-checkbox"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null, enabled = canUnhide, modifier = Modifier.padding(end = GallerySpacing.Md))
                    Text(
                        stringResource(R.string.storage_access_turn_off_unhide_now),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (canUnhide) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(checked) }, modifier = Modifier.testTag("storage-access-turn-off-confirm")) {
                Text(stringResource(R.string.storage_access_turn_off_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.storage_access_cancel)) }
        },
    )
}

/** "Lightforge hid 2 folders and 1 file." with the right noun for each kind that is present. */
@Composable
private fun hiddenCountSentence(hidden: List<HiddenFolderUi>): String {
    val files = hidden.count { it.isFile }
    val folders = hidden.size - files
    val folderText = if (folders > 0) pluralStringResource(R.plurals.storage_access_count_folders, folders, folders) else null
    val fileText = if (files > 0) pluralStringResource(R.plurals.storage_access_count_files, files, files) else null
    return if (folderText != null && fileText != null) {
        stringResource(R.string.storage_access_turn_off_hid_both, folderText, fileText)
    } else {
        stringResource(R.string.storage_access_turn_off_hid, folderText ?: fileText.orEmpty())
    }
}

/** Short status for the settings list row. */
@Composable
fun StorageAccessUiState.categorySummary(): String = when {
    revokedWithHidden > 0 -> pluralStringResource(R.plurals.storage_access_summary_action_needed, revokedWithHidden, revokedWithHidden)
    advancedSupported -> advancedStatusLabel(advancedStatus)
    else -> stringResource(if (manageMediaGranted) R.string.storage_access_allowed else R.string.storage_access_not_allowed)
}

@Composable
private fun advancedStatusLabel(status: AdvancedStatus) = stringResource(
    when (status) {
        AdvancedStatus.Off -> R.string.storage_access_status_off
        AdvancedStatus.Active -> R.string.storage_access_status_active
        AdvancedStatus.WaitingForPermission -> R.string.storage_access_status_waiting
    },
)

@Composable
private fun ManageMediaSection(state: StorageAccessUiState, actions: StorageAccessActions, context: Context) {
    StorageAccessSection(title = stringResource(R.string.storage_access_permissions_title)) {
        row {
            StorageAccessRow(
                title = stringResource(R.string.storage_access_media_title),
                icon = Icons.Outlined.PermMedia,
                supporting = stringResource(R.string.storage_access_media_body),
                onClick = { context.startSafely(actions.manageMediaIntent()) },
                modifier = Modifier.testTag("storage-access-media-status"),
                trailing = {
                    Text(
                        stringResource(if (state.manageMediaGranted) R.string.storage_access_allowed else R.string.storage_access_not_allowed),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (state.manageMediaGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
    }
}

@Composable
private fun AdvancedSection(
    state: StorageAccessUiState,
    actions: StorageAccessActions,
    context: Context,
    onRequestEnable: () -> Unit,
    onRequestTurnOff: () -> Unit,
    requireUnlock: (onSuccess: () -> Unit) -> Unit,
) {
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    val modeActive = state.advancedActive && !state.busy
    StorageAccessSection(title = stringResource(R.string.storage_access_advanced_title)) {
        row {
            StorageAccessSwitchRow(
                title = stringResource(R.string.storage_access_advanced_switch),
                icon = Icons.Outlined.FolderSpecial,
                checked = state.advancedEnabled,
                supporting = stringResource(R.string.storage_access_advanced_summary),
                enabled = !state.busy,
                modifier = Modifier.testTag("storage-access-advanced-switch"),
                onCheckedChange = { enable -> if (enable) onRequestEnable() else onRequestTurnOff() },
            )
        }
        if (state.advancedStatus == AdvancedStatus.WaitingForPermission) {
            row {
                PermissionOffRow(
                    hasHidden = state.hidden.isNotEmpty(),
                    onAllow = { context.startSafely(actions.permissionIntent()) },
                )
            }
        }
        row {
            StorageAccessSwitchRow(
                title = stringResource(R.string.storage_access_show_hidden),
                icon = Icons.Outlined.Visibility,
                checked = state.showHidden,
                supporting = stringResource(R.string.storage_access_show_hidden_supporting),
                enabled = modeActive,
                modifier = Modifier.testTag("storage-access-show-hidden-switch"),
                // Showing hidden items is the protected step; hiding them again never asks.
                onCheckedChange = { on ->
                    if (on && state.hiddenItemsProtected) requireUnlock { actions.onSetShowHidden(true) } else actions.onSetShowHidden(on)
                },
            )
        }
        row {
            StorageAccessSwitchRow(
                title = stringResource(R.string.storage_access_delete_empty),
                icon = Icons.Outlined.DeleteSweep,
                checked = state.deleteEmptyFolders,
                supporting = stringResource(R.string.storage_access_delete_empty_supporting),
                enabled = modeActive,
                modifier = Modifier.testTag("storage-access-delete-empty-switch"),
                onCheckedChange = { on -> actions.onSetDeleteEmptyFolders(on) },
            )
        }
        row {
            StorageAccessRow(
                title = stringResource(R.string.storage_access_learn_more),
                icon = Icons.Outlined.Info,
                supporting = if (detailsExpanded) stringResource(R.string.storage_access_advanced_supporting) else null,
                supportingMaxLines = Int.MAX_VALUE,
                onClick = { detailsExpanded = !detailsExpanded },
                modifier = Modifier.testTag("storage-access-learn-more"),
                trailing = {
                    Icon(
                        if (detailsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
    }
}

/** Inline warning inside the advanced group: the mode is on but the system permission is not. */
@Composable
private fun PermissionOffRow(hasHidden: Boolean, onAllow: () -> Unit) {
    // errorContainer pairs with onErrorContainer.
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().padding(GallerySpacing.Sm).testTag("storage-access-revoked-warning"),
    ) {
        Row(
            Modifier.padding(start = GallerySpacing.Lg, top = GallerySpacing.Md, bottom = GallerySpacing.Md, end = GallerySpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Lg),
        ) {
            Icon(Icons.Outlined.Warning, contentDescription = null, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.storage_access_permission_off_title), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(if (hasHidden) R.string.storage_access_permission_off_hidden else R.string.storage_access_permission_off_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // The inverse of the container pair keeps the button readable on the error container.
            Button(
                onClick = onAllow,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onErrorContainer,
                    contentColor = MaterialTheme.colorScheme.errorContainer,
                ),
                modifier = Modifier.testTag("storage-access-permission-button"),
            ) { Text(stringResource(R.string.storage_access_allow)) }
        }
    }
}

/** Folders MediaStore does not index that the user added to the library (ADR-046). */
@Composable
private fun ScannedFoldersSection(state: StorageAccessUiState, actions: StorageAccessActions, onAdd: () -> Unit) {
    StorageAccessSection(
        title = stringResource(R.string.storage_access_included_title),
        footer = stringResource(R.string.storage_access_included_body),
    ) {
        state.scannedFolders.forEach { path ->
            row {
                val readable = readableHiddenPath(path)
                val name = path.trimEnd('/').substringAfterLast('/').ifEmpty { readable }
                val description = stringResource(R.string.storage_access_included_remove_description, name)
                StorageAccessRow(
                    title = name,
                    icon = Icons.Outlined.Folder,
                    supporting = readable,
                    supportingMaxLines = 1,
                    modifier = Modifier.testTag("storage-access-included-row"),
                    trailing = {
                        IconButton(
                            onClick = { actions.onRemoveScannedFolder(path) },
                            enabled = !state.busy,
                            modifier = Modifier.semantics { contentDescription = description },
                        ) { Icon(Icons.Outlined.Close, contentDescription = null) }
                    },
                )
            }
        }
        row {
            StorageAccessRow(
                title = stringResource(R.string.storage_access_included_add),
                icon = Icons.Outlined.Add,
                enabled = !state.busy,
                onClick = onAdd,
                modifier = Modifier.testTag("storage-access-included-add"),
            )
        }
        if (state.scannedFolders.isNotEmpty()) {
            row {
                StorageAccessRow(
                    title = stringResource(R.string.storage_access_included_rescan),
                    icon = Icons.Outlined.Refresh,
                    enabled = !state.busy,
                    onClick = actions.onRescanScannedFolders,
                    modifier = Modifier.testTag("storage-access-included-rescan"),
                )
            }
        }
    }
}

@Composable
private fun HiddenFoldersSection(state: StorageAccessUiState, actions: StorageAccessActions, chooseFolder: (String) -> Unit) {
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    StorageAccessSection(
        title = stringResource(R.string.storage_access_hidden_title),
        footer = stringResource(R.string.storage_access_hidden_body),
    ) {
        state.hidden.forEach { entry ->
            row {
                val name = hiddenItemName(entry)
                val needsFolder = entry.absolutePath in state.treeGrantPaths
                val description = stringResource(
                    if (needsFolder) R.string.storage_access_unhide_choose_folder_description else R.string.storage_access_unhide_description,
                    name,
                )
                StorageAccessRow(
                    title = name,
                    icon = if (entry.isFile) Icons.Outlined.Image else Icons.Outlined.FolderOff,
                    supporting = stringResource(
                        R.string.storage_access_hidden_row_supporting,
                        hiddenItemParent(entry),
                        dateFormat.format(Date(entry.createdAtMillis)),
                    ),
                    modifier = Modifier.testTag("storage-access-hidden-row"),
                    trailing = {
                        IconButton(
                            onClick = { if (needsFolder) chooseFolder(entry.absolutePath) else actions.onUnhide(entry.absolutePath) },
                            enabled = !state.busy,
                            modifier = Modifier.semantics { contentDescription = description },
                        ) { Icon(Icons.Outlined.Visibility, contentDescription = null) }
                    },
                )
            }
        }
        if (state.hidden.size > 1) {
            row {
                StorageAccessRow(
                    title = stringResource(R.string.storage_access_unhide_all),
                    icon = Icons.Outlined.VisibilityOff,
                    enabled = !state.busy,
                    onClick = actions.onUnhideAll,
                    modifier = Modifier.testTag("storage-access-unhide-all"),
                )
            }
        }
    }
}

/** The hidden items list behind the app lock: the same group with one row that unlocks it. */
@Composable
private fun LockedHiddenFoldersSection(onShow: () -> Unit) {
    StorageAccessSection(title = stringResource(R.string.storage_access_hidden_title)) {
        row {
            StorageAccessRow(
                title = stringResource(R.string.storage_access_hidden_show_list),
                icon = Icons.Outlined.Lock,
                supporting = stringResource(R.string.storage_access_hidden_locked),
                onClick = onShow,
                modifier = Modifier.testTag("storage-access-hidden-show-list"),
            )
        }
    }
}

/**
 * Offers to unhide the folders left behind when an import or reset turned the mode off. Hosted at the app root so it
 * appears on launch and wherever the change happened, not only inside Settings.
 */
@Composable
fun StorageAccessUnhidePrompt(state: StorageAccessUiState, actions: StorageAccessActions) {
    if (state.unhidePromptCount <= 0) return
    AlertDialog(
        onDismissRequest = actions.onDismissUnhidePrompt,
        title = { Text(pluralStringResource(R.plurals.storage_access_prompt_title, state.unhidePromptCount, state.unhidePromptCount)) },
        text = { Text(stringResource(R.string.storage_access_prompt_body)) },
        confirmButton = {
            TextButton(
                onClick = { actions.onDismissUnhidePrompt(); actions.onUnhideAll() },
                modifier = Modifier.testTag("storage-access-prompt-unhide"),
            ) { Text(stringResource(R.string.storage_access_unhide)) }
        },
        dismissButton = {
            TextButton(onClick = actions.onKeepHidden) { Text(stringResource(R.string.storage_access_keep_hidden)) }
        },
    )
}

@Composable
private fun NoticeCard(text: String, action: Pair<String, () -> Unit>?, tag: String) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    ) {
        Column(Modifier.padding(GallerySpacing.Lg), verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            action?.let { (label, onClick) -> TextButton(onClick = onClick, colors = onContainerButtonColors()) { Text(label) } }
        }
    }
}

/** Opens a system screen; a device without that screen, or no intent at all, is silently ignored. */
private fun Context.startSafely(intent: Intent?) {
    if (intent == null) return
    runCatching { startActivity(intent) }
}

/** Text buttons on a tinted container inherit its content colour instead of the unrelated primary. */
@Composable
private fun onContainerButtonColors() = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current)
