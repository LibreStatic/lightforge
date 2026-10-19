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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
    // Opening the system picker on the folder itself saves the user from browsing to it.
    val chooseFolder: (String) -> Unit = { path ->
        treeGrantPath = path
        val initial = treeDocumentIdOf(path)?.let {
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", it)
        }
        runCatching { treePicker.launch(initial) }.onFailure { treeGrantPath = null }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xl)) {
        if (state.permissionRevoked) {
            RevokedWarning(state, actions, context)
        }
        state.message?.let { message ->
            NoticeCard(
                text = when (message.kind) {
                    StorageAccessMessageKind.PermissionNeeded -> stringResource(R.string.storage_access_message_permission)
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
        AlertDialog(
            onDismissRequest = { confirmTurnOff = false },
            title = {
                Text(pluralStringResource(R.plurals.storage_access_turn_off_title, state.hidden.size, state.hidden.size))
            },
            text = {
                Text(
                    stringResource(R.string.storage_access_turn_off_body) +
                        if (state.allFilesGranted) "" else "\n\n" + stringResource(R.string.storage_access_turn_off_no_permission),
                )
            },
            // Three actions do not fit on one row; Material stacks them as separate text buttons.
            confirmButton = {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    TextButton(
                        onClick = { confirmTurnOff = false; actions.onUnhideAndTurnOff() },
                        modifier = Modifier.testTag("storage-access-unhide-and-turn-off"),
                    ) { Text(stringResource(R.string.storage_access_turn_off_unhide)) }
                    TextButton(
                        onClick = { confirmTurnOff = false; actions.onSetEnabled(false) },
                        modifier = Modifier.testTag("storage-access-keep-and-turn-off"),
                    ) { Text(stringResource(R.string.storage_access_turn_off_keep)) }
                    TextButton(onClick = { confirmTurnOff = false }) { Text(stringResource(R.string.storage_access_cancel)) }
                }
            },
        )
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
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = GallerySpacing.Md).semantics { heading() },
    )
}

@Composable
private fun SectionBody(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = GallerySpacing.Md),
    )
}

@Composable
private fun StatusRow(label: String, value: String, modifier: Modifier = Modifier) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = modifier.fillMaxWidth().clip(MaterialTheme.shapes.large),
        supportingContent = { Text(value) },
    ) { Text(label) }
}

@Composable
private fun ManageMediaSection(state: StorageAccessUiState, actions: StorageAccessActions, context: Context) {
    Column(verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
        SectionTitle(stringResource(R.string.storage_access_media_title))
        SectionBody(stringResource(R.string.storage_access_media_body))
        StatusRow(
            label = stringResource(R.string.storage_access_permission_label),
            value = stringResource(if (state.manageMediaGranted) R.string.storage_access_allowed else R.string.storage_access_not_allowed),
            modifier = Modifier.testTag("storage-access-media-status"),
        )
        FilledTonalButton(
            onClick = { context.startSafely(actions.manageMediaIntent()) },
            modifier = Modifier.padding(horizontal = GallerySpacing.Md).testTag("storage-access-media-button"),
        ) {
            Text(
                stringResource(
                    if (state.manageMediaGranted) R.string.storage_access_change_system else R.string.storage_access_open_system,
                ),
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
    Column(verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
        SectionTitle(stringResource(R.string.storage_access_advanced_title))
        SettingsSwitchRow(
            label = stringResource(R.string.storage_access_advanced_switch),
            checked = state.advancedEnabled,
            supporting = stringResource(R.string.storage_access_advanced_supporting),
            enabled = !state.busy,
            modifier = Modifier.testTag("storage-access-advanced-switch"),
        ) { enable -> if (enable) onRequestEnable() else onRequestTurnOff() }
        if (state.advancedEnabled) {
            StatusRow(
                label = stringResource(R.string.storage_access_status_label),
                value = advancedStatusLabel(state.advancedStatus),
                modifier = Modifier.testTag("storage-access-advanced-status"),
            )
            if (state.advancedActive) {
                SettingsSwitchRow(
                    label = stringResource(R.string.storage_access_show_hidden),
                    checked = state.showHidden,
                    supporting = stringResource(R.string.storage_access_show_hidden_supporting),
                    enabled = !state.busy,
                    modifier = Modifier.testTag("storage-access-show-hidden-switch"),
                ) { on ->
                    // Showing hidden items is the protected step; hiding them again never asks.
                    if (on && state.hiddenItemsProtected) requireUnlock { actions.onSetShowHidden(true) } else actions.onSetShowHidden(on)
                }
                SettingsSwitchRow(
                    label = stringResource(R.string.storage_access_delete_empty),
                    checked = state.deleteEmptyFolders,
                    supporting = stringResource(R.string.storage_access_delete_empty_supporting),
                    enabled = !state.busy,
                    modifier = Modifier.testTag("storage-access-delete-empty-switch"),
                ) { on -> actions.onSetDeleteEmptyFolders(on) }
            }
            if (state.advancedStatus == AdvancedStatus.WaitingForPermission) {
                SectionBody(stringResource(R.string.storage_access_waiting_body))
                // The explanation comes first, also for a mode that arrived through an import.
                FilledTonalButton(
                    onClick = onRequestEnable,
                    modifier = Modifier.padding(horizontal = GallerySpacing.Md).testTag("storage-access-permission-button"),
                ) { Text(stringResource(R.string.storage_access_open_system)) }
            }
        }
    }
}

@Composable
private fun HiddenFoldersSection(state: StorageAccessUiState, actions: StorageAccessActions, chooseFolder: (String) -> Unit) {
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    Column(verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
        SectionTitle(stringResource(R.string.storage_access_hidden_title))
        SectionBody(stringResource(R.string.storage_access_hidden_body))
        state.hidden.forEach { entry ->
            val name = readableHiddenPath(entry.absolutePath)
            ListItem(
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).testTag("storage-access-hidden-row"),
                supportingContent = {
                    Text(stringResource(R.string.storage_access_hidden_on, dateFormat.format(Date(entry.createdAtMillis))))
                },
                trailingContent = {
                    val needsFolder = entry.absolutePath in state.treeGrantPaths
                    val description = stringResource(
                        if (needsFolder) R.string.storage_access_unhide_choose_folder_description else R.string.storage_access_unhide_description,
                        name,
                    )
                    TextButton(
                        onClick = { if (needsFolder) chooseFolder(entry.absolutePath) else actions.onUnhide(entry.absolutePath) },
                        enabled = !state.busy,
                        modifier = Modifier.semantics { contentDescription = description },
                    ) {
                        Text(stringResource(if (needsFolder) R.string.storage_access_unhide_choose_folder else R.string.storage_access_unhide))
                    }
                },
            ) { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        if (state.hidden.size > 1) {
            FilledTonalButton(
                onClick = actions.onUnhideAll,
                enabled = !state.busy,
                modifier = Modifier.padding(horizontal = GallerySpacing.Md).testTag("storage-access-unhide-all"),
            ) { Text(stringResource(R.string.storage_access_unhide_all)) }
        }
    }
}

/** The hidden folders list behind the app lock: a labelled section with its own explanation and action. */
@Composable
private fun LockedHiddenFoldersSection(onShow: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
        SectionTitle(stringResource(R.string.storage_access_hidden_title))
        SectionBody(stringResource(R.string.storage_access_hidden_locked))
        FilledTonalButton(
            onClick = onShow,
            modifier = Modifier.padding(horizontal = GallerySpacing.Md).testTag("storage-access-hidden-show-list"),
        ) { Text(stringResource(R.string.storage_access_hidden_show_list)) }
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
private fun RevokedWarning(state: StorageAccessUiState, actions: StorageAccessActions, context: Context) {
    // errorContainer/onErrorContainer is the matching pair; the buttons inherit the container content colour.
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().testTag("storage-access-revoked-warning"),
    ) {
        Column(Modifier.padding(GallerySpacing.Lg), verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
            Text(
                stringResource(R.string.storage_access_revoked_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                pluralStringResource(R.plurals.storage_access_revoked_body, state.hidden.size, state.hidden.size),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
                TextButton(onClick = { context.startSafely(actions.permissionIntent()) }, colors = onContainerButtonColors()) {
                    Text(stringResource(R.string.storage_access_open_system))
                }
                TextButton(onClick = actions.onUnhideAll, enabled = !state.busy, colors = onContainerButtonColors()) {
                    Text(stringResource(R.string.storage_access_unhide_all))
                }
            }
        }
    }
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
