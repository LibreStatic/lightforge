package com.librestatic.lightforge.feature.privatealbum

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.librestatic.lightforge.core.designsystem.GalleryProgressIndicator
import com.librestatic.lightforge.core.designsystem.GalleryIndeterminateProgressIndicator
import com.librestatic.lightforge.core.designsystem.GalleryProgressSlot

enum class PrivateKeyProtectionPhase { Confirmation, Working, AuthenticationRequired, CredentialsRequired, Failed, Complete }

/**
 * Presentation only: the caller owns authentication, key migration and verified completion.
 * onConfirm means Protect/Continue authentication/Retry. Enter Working synchronously in that
 * callback to prevent duplicate operations. Dismiss never mutates keys or resets the album.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun PrivateKeyProtectionContent(
    phase: PrivateKeyProtectionPhase,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    automatic: Boolean = false,
) {
    val working = phase == PrivateKeyProtectionPhase.Working
    val complete = phase == PrivateKeyProtectionPhase.Complete
    val body = when (phase) {
        PrivateKeyProtectionPhase.Confirmation -> R.string.private_key_protection_body
        PrivateKeyProtectionPhase.Working ->
            if (automatic) R.string.private_key_protection_auto_working else R.string.private_key_protection_working
        PrivateKeyProtectionPhase.AuthenticationRequired -> R.string.private_key_protection_authentication_required
        PrivateKeyProtectionPhase.CredentialsRequired -> R.string.private_key_protection_credentials_required
        PrivateKeyProtectionPhase.Failed ->
            if (automatic) R.string.private_key_protection_auto_failed else R.string.private_key_protection_failed
        PrivateKeyProtectionPhase.Complete -> R.string.private_key_protection_complete
    }
    val confirm = when (phase) {
        PrivateKeyProtectionPhase.Confirmation -> R.string.private_key_protection_protect
        PrivateKeyProtectionPhase.Working -> R.string.private_key_protection_working_button
        PrivateKeyProtectionPhase.AuthenticationRequired -> R.string.private_key_protection_continue
        PrivateKeyProtectionPhase.CredentialsRequired,
        PrivateKeyProtectionPhase.Failed -> R.string.private_key_protection_retry
        PrivateKeyProtectionPhase.Complete -> R.string.private_key_protection_done
    }
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        modifier = Modifier.testTag("private-key-protection-dialog").semantics { testTagsAsResourceId = true },
        properties = DialogProperties(dismissOnBackPress = !working, dismissOnClickOutside = !working,
            securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(stringResource(if (complete) R.string.private_key_protection_complete_title else R.string.private_key_protection_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(body), Modifier.testTag("private-key-protection-status").semantics { liveRegion = LiveRegionMode.Polite })
                GalleryProgressSlot(working) { GalleryIndeterminateProgressIndicator(Modifier.testTag("private-key-protection-progress")) }
                Text(stringResource(R.string.private_key_protection_index_scope))
                if (!complete) Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.small) {
                    Text(stringResource(R.string.private_key_protection_risk), Modifier.padding(12.dp), color = LocalContentColor.current)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (complete) onDismiss() else if (!working) onConfirm() }, enabled = !working,
                modifier = Modifier.testTag(if (complete) "private-key-protection-done" else "private-key-protection-confirm")) {
                Text(stringResource(confirm))
            }
        },
        dismissButton = if (!working && !complete) ({
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("private-key-protection-cancel")) {
                Text(stringResource(R.string.private_key_protection_cancel))
            }
        }) else null,
    )
}
