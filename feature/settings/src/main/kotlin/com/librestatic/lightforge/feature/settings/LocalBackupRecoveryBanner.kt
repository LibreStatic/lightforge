package com.librestatic.lightforge.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GalleryIndeterminateProgressIndicator

@Composable
fun LocalBackupRecoveryBanner(
    working: Boolean,
    removed: Int,
    retained: Int,
    failed: Boolean,
    onRetry: () -> Unit,
) {
    if (!working && removed == 0 && retained == 0 && !failed) return
    val caution = failed || retained > 0
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("local-backup-recovery"),
        color =
            if (caution) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer,
        contentColor =
            if (caution) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.local_backup_recovery_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (working) {
                Text(stringResource(R.string.local_backup_recovery_working))
                GalleryIndeterminateProgressIndicator(
                    Modifier.fillMaxWidth(),
                    color = LocalContentColor.current,
                    trackColor =
                        if (caution) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.secondaryContainer,
                )
            }
            if (removed > 0) Text(stringResource(R.string.local_backup_recovery_removed, removed))
            if (retained > 0)
                Text(stringResource(R.string.local_backup_recovery_retained, retained))
            if (failed) Text(stringResource(R.string.local_backup_recovery_failed))
            if (retained > 0 || failed)
                TextButton(
                    onClick = onRetry,
                    enabled = !working,
                    colors =
                        ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
                    modifier = Modifier.testTag("local-backup-recovery-retry"),
                ) {
                    Text(stringResource(R.string.local_backup_recovery_retry))
                }
        }
    }
}
