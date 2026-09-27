package com.librestatic.lightforge.feature.remotebackup

import androidx.annotation.StringRes
import com.librestatic.lightforge.core.remotestorage.RemoteProtocol

/** Editable fields of the "Add server" form that can carry a validation message. */
internal enum class RemoteField {
    NAME,
    HOST,
    PORT,
    USERNAME,
    ROOT,
    SHARE,
    KEY,
}

/**
 * Pure mirror of [com.librestatic.lightforge.core.remotestorage.RemoteProfile.validate],
 * [com.librestatic.lightforge.core.remotestorage.RemoteNames.requireChild] and the SFTP connector's root rule
 * (absolute, at most 4096 characters, no backslash), returning one string resource per
 * offending field. An empty map means the draft would build a valid profile, so
 * `R.string.remote_error` stays reserved for genuine connection or IO failures.
 */
internal fun validateRemoteDraft(
    name: String,
    protocol: RemoteProtocol,
    host: String,
    port: String,
    username: String,
    root: String,
    share: String = "",
    keyAuth: Boolean = false,
    hasKey: Boolean = false,
): Map<RemoteField, Int> {
    val errors = LinkedHashMap<RemoteField, Int>()
    if (name.isBlank() || name.length > 120) errors[RemoteField.NAME] = R.string.remote_error_name_required
    val trimmedHost = host.trim()
    @StringRes
    val hostError: Int? =
        when {
            trimmedHost.isBlank() -> R.string.remote_error_host_required
            trimmedHost.length > 253 -> R.string.remote_error_host_invalid
            trimmedHost.any { it.isWhitespace() || it in "/\\@?#" || it.code < 32 } ->
                R.string.remote_error_host_invalid
            else -> null
        }
    if (hostError != null) errors[RemoteField.HOST] = hostError
    val portNumber = port.trim().toIntOrNull()
    if (portNumber == null || portNumber !in 1..65535)
        errors[RemoteField.PORT] = R.string.remote_error_port_invalid
    if (username.isBlank() || username.length > 256 || username.any { it.code < 32 })
        errors[RemoteField.USERNAME] = R.string.remote_error_user_required
    val rootInvalid =
        root.length > 4096 ||
            root.any { it.code < 32 || it == '' } ||
            root.split('/', '\\').any { it == ".." } ||
            (protocol == RemoteProtocol.SMB && (root.startsWith("\\\\") || ':' in root))
    if (rootInvalid) errors[RemoteField.ROOT] = R.string.remote_error_folder_invalid
    else if (
        protocol == RemoteProtocol.SFTP &&
            (!root.startsWith('/') || '\\' in root || root.split('/').any { it == "." })
    )
        errors[RemoteField.ROOT] = R.string.remote_error_folder_sftp_absolute
    if (protocol == RemoteProtocol.SMB && !isValidShare(share))
        errors[RemoteField.SHARE] = R.string.remote_error_share_required
    if (keyAuth && !hasKey) errors[RemoteField.KEY] = R.string.remote_error_key_required
    return errors
}

/** Mirrors `RemoteNames.requireChild`, which SMB profiles apply to the share name. */
private fun isValidShare(share: String): Boolean =
    share.isNotBlank() &&
        share != "." &&
        share != ".." &&
        share.length <= 240 &&
        share.none { it.code < 32 || it.code == 127 || it in "/\\:*?\"<>|" } &&
        !share.endsWith('.') &&
        !share.endsWith(' ')
