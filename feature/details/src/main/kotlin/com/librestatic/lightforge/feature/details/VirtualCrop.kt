package com.librestatic.lightforge.feature.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GalleryLoadingIndicator

/**
 * A RAW photo whose DNG framing hides part of the sensor image. The file still holds all of it,
 * so a receiver of the original can uncover what lies outside the frame.
 */
data class VirtualCropInfo(
    val fullWidth: Int,
    val fullHeight: Int,
    val cropWidth: Int,
    val cropHeight: Int,
) {
    val framedSize: String get() = "$cropWidth × $cropHeight"
    val fullSize: String get() = "$fullWidth × $fullHeight"
}

/** Always-visible note for the details pane, independent of the "do not show again" setting. */
@Composable
fun VirtualCropNote(info: VirtualCropInfo, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth().testTag("details-virtual-crop"),
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.tertiary,
                contentColor = MaterialTheme.colorScheme.onTertiary,
                shape = CircleShape,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Rounded.Crop,
                    contentDescription = null,
                    modifier = Modifier.padding(7.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.details_virtual_crop_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    stringResource(R.string.virtual_crop_shows, info.framedSize, info.fullSize),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.details_virtual_crop_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * Shown before the original of one or more RAW photos with a virtual crop is shared. The
 * checkbox state travels with whichever share button is chosen; cancelling never records it,
 * and cancelling while [working] abandons the JPEG preparation.
 */
@Composable
fun VirtualCropShareDialog(
    items: List<VirtualCropInfo>,
    working: Boolean,
    onShareCropped: (dontShowAgain: Boolean) -> Unit,
    onShareOriginal: (dontShowAgain: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    require(items.isNotEmpty())
    var dontShowAgain by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Rounded.Crop, contentDescription = null) },
        title = {
            Text(pluralStringResource(R.plurals.virtual_crop_dialog_title, items.size))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (items.size == 1) {
                    val item = items.single()
                    Text(stringResource(R.string.virtual_crop_shows, item.framedSize, item.fullSize))
                } else {
                    Text(pluralStringResource(R.plurals.virtual_crop_dialog_count, items.size, items.size))
                }
                Text(pluralStringResource(R.plurals.virtual_crop_dialog_body, items.size))
                if (working) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        GalleryLoadingIndicator(Modifier.size(24.dp))
                        Text(stringResource(R.string.virtual_crop_working))
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(
                            value = dontShowAgain,
                            enabled = !working,
                            role = Role.Checkbox,
                            onValueChange = { dontShowAgain = it },
                        )
                        .testTag("virtual-crop-dont-show"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(checked = dontShowAgain, onCheckedChange = null, enabled = !working)
                    Text(stringResource(R.string.virtual_crop_dont_show_again))
                }
            }
        },
        confirmButton = {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onShareCropped(dontShowAgain) },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth().testTag("virtual-crop-share-jpeg"),
                ) { Text(stringResource(R.string.virtual_crop_share_jpeg)) }
                FilledTonalButton(
                    onClick = { onShareOriginal(dontShowAgain) },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth().testTag("virtual-crop-share-original"),
                ) { Text(stringResource(R.string.virtual_crop_share_original)) }
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth().testTag("virtual-crop-cancel"),
                ) { Text(stringResource(R.string.virtual_crop_cancel)) }
            }
        },
    )
}
