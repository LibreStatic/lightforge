package com.librestatic.lightforge.feature.settings

import androidx.compose.runtime.Composable
import com.librestatic.lightforge.core.designsystem.LightforgeTheme

// Debug-only, zero-argument entry point for tools/compose-driver: the real migration dialog.

/** The one-time offer to switch an upgraded install to the advanced storage mode. */
@Composable
fun StorageAccessMigrationDialogPreview() = LightforgeTheme {
    StorageAccessMigrationDialog(onSwitch = {}, onNotNow = {})
}
