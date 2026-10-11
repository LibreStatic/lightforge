package com.librestatic.lightforge.feature.privatealbum

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.librestatic.lightforge.core.security.PrivateAlbumCrypto
import kotlinx.coroutines.CancellationException

/** Plaintext of one private item, read in memory on demand; nothing is written to disk. */
class PrivatePlainReader internal constructor(private val source: PrivateViewerSource) {
    val size: Long get() = source.metadata.plaintextBytes

    /** Exactly [length] bytes at [offset], or null when that range is not entirely inside the item. */
    fun read(offset: Long, length: Int): ByteArray? {
        if (offset < 0 || length < 0 || offset + length > size) return null
        val bytes = ByteArray(length)
        var done = 0
        while (done < length) {
            val count = source.readAt(offset + done, bytes, done, length - done)
            if (count <= 0) return null
            done += count
        }
        return bytes
    }
}

/**
 * What the portable export needs to warn about RAW photos with a virtual crop. The app owns the
 * DNG parser, so this module stays free of the native RAW library.
 */
class PrivateVirtualCropWarning(
    val dismissed: Boolean,
    val onDismiss: () -> Unit,
    val isRaw: (displayName: String?, mimeType: String?) -> Boolean,
    /** True when the DNG framing of the item hides part of the sensor image. */
    val hidesSensorArea: (PrivatePlainReader) -> Boolean,
)

internal data class PrivatePortableProbe(
    val mediaId: Long,
    val displayName: String,
    val mimeType: String,
    val mediaKind: String,
)

/** RAW items inspected per export; each costs one authenticated decrypt pass. */
internal const val MaxVirtualCropInspections = 300

/**
 * How many RAW images may carry a virtual crop. An item whose framing cannot be read counts as
 * cropped, and so does every RAW past [limit], because a warning that errs is cheaper than a
 * silent leak.
 */
internal suspend fun privateRawsWithVirtualCrop(
    probes: List<PrivatePortableProbe>,
    isRaw: (displayName: String?, mimeType: String?) -> Boolean,
    limit: Int = MaxVirtualCropInspections,
    hidesSensorArea: suspend (mediaId: Long) -> Boolean,
): Int {
    val raws = probes.filter { it.mediaKind == "image" && isRaw(it.displayName, it.mimeType) }
    return raws.withIndex().count { (index, probe) ->
        index >= limit || try {
            hidesSensorArea(probe.mediaId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // A locked vault must surface; anything else just leaves this item unverified.
            if (failure is PrivateIndexLockedException || PrivateAlbumCrypto.requiresAuthentication(failure)) throw failure
            true
        }
    }
}

/** Shown before a portable export that would carry [count] RAW photos with a virtual crop. */
@Composable
fun PrivatePortableVirtualCropDialog(
    count: Int,
    onContinue: (dontShowAgain: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    require(count > 0)
    var dontShowAgain by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag("private-portable-virtual-crop-dialog").semantics { testTagsAsResourceId = true },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(stringResource(R.string.private_portable_crop_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(pluralStringResource(R.plurals.private_portable_crop_count, count, count))
                Text(stringResource(R.string.private_portable_crop_body))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(
                            value = dontShowAgain,
                            role = Role.Checkbox,
                            onValueChange = { dontShowAgain = it },
                        )
                        .testTag("private-portable-virtual-crop-dont-show"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(checked = dontShowAgain, onCheckedChange = null)
                    Text(stringResource(R.string.private_portable_crop_dont_show))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onContinue(dontShowAgain) },
                modifier = Modifier.testTag("private-portable-virtual-crop-continue"),
            ) { Text(stringResource(R.string.private_portable_continue)) }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.testTag("private-portable-virtual-crop-cancel"),
            ) { Text(stringResource(R.string.private_cancel)) }
        },
    )
}
