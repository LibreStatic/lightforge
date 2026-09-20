package com.ugallery.feature.album

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

// Match GalleryAlbumRepository's name policy without adding a data-layer UI dependency.
internal fun normalizedAlbumName(name: String): String = name.trim().replace(Regex("\\s+"), " ")

/**
 * Controlled draft: the host binds name/error/work to the exact virtual album and owns persistence.
 * onConfirm receives only a valid normalized name; the host must synchronously gate repeat submits.
 * No dismissal while a write is pending, and no repository/media operation occurs in this component.
 */
@Composable
fun RenameAlbumDialog(
    name: String,
    onNameChange: (String) -> Unit,
    working: Boolean,
    saveFailed: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val normalized = normalizedAlbumName(name)
    val validation = when {
        normalized.isEmpty() -> R.string.album_rename_empty
        normalized.length > 200 -> R.string.album_rename_too_long
        else -> null
    }
    fun submit() {
        if (!working && validation == null) onConfirm(normalized)
    }
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !working, dismissOnClickOutside = !working),
        title = { Text(stringResource(R.string.album_rename_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.album_rename_body))
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (!working) onNameChange(it) },
                    enabled = !working,
                    label = { Text(stringResource(R.string.album_name_label)) },
                    isError = validation != null,
                    supportingText = if (validation != null) { { Text(stringResource(validation)) } } else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().testTag("album-rename-name"),
                )
                if (saveFailed) Text(
                    stringResource(R.string.album_rename_failed),
                    modifier = Modifier.testTag("album-rename-error"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit, enabled = !working && validation == null,
                modifier = Modifier.testTag("album-rename-save")) {
                Text(stringResource(if (working) R.string.album_rename_saving else R.string.album_rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = { if (!working) onDismiss() }, enabled = !working,
                modifier = Modifier.testTag("album-rename-cancel")) {
                Text(stringResource(R.string.album_rename_cancel))
            }
        },
    )
}
