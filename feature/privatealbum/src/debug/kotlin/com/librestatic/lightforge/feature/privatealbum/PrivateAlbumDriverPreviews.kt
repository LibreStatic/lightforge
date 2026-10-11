package com.librestatic.lightforge.feature.privatealbum

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.librestatic.lightforge.core.designsystem.LightforgeTheme

// Debug-only, zero-argument entry points for tools/compose-driver and Android Studio previews.
// Each one feeds fake state into the real production composable.

/** The warning shown before a portable export that would carry one RAW photo with a virtual crop. */
@Preview
@Composable
fun PrivatePortableVirtualCropDialogPreview() = PrivateFrame {
    PrivatePortableVirtualCropDialog(count = 1, onContinue = {}, onCancel = {})
}

/** The same warning for several photos. */
@Preview
@Composable
fun PrivatePortableVirtualCropDialogManyPreview() = PrivateFrame {
    PrivatePortableVirtualCropDialog(count = 14, onContinue = {}, onCancel = {})
}

@Composable
private fun PrivateFrame(content: @Composable () -> Unit) {
    LightforgeTheme {
        Surface(
            Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) { content() }
    }
}
