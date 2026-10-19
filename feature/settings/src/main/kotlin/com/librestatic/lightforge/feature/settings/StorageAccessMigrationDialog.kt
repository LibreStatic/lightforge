package com.librestatic.lightforge.feature.settings

import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GalleryIcons

/**
 * One-time offer shown to an upgraded install to switch to the advanced storage mode (ADR-046).
 * Back and an outside tap count as "Not now"; the host decides when it may appear.
 */
@Composable
fun StorageAccessMigrationDialog(onSwitch: () -> Unit, onNotNow: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNotNow,
        modifier = Modifier.testTag("advanced-migration-dialog"),
        icon = { Icon(GalleryIcons.Folder, contentDescription = null, modifier = Modifier.size(24.dp)) },
        title = { Text(stringResource(R.string.storage_access_migration_title)) },
        text = { Text(stringResource(R.string.storage_access_migration_body)) },
        confirmButton = {
            TextButton(onClick = onSwitch, modifier = Modifier.testTag("advanced-migration-switch")) {
                Text(stringResource(R.string.storage_access_migration_switch))
            }
        },
        dismissButton = {
            TextButton(onClick = onNotNow, modifier = Modifier.testTag("advanced-migration-not-now")) {
                Text(stringResource(R.string.storage_access_migration_not_now))
            }
        },
    )
}
