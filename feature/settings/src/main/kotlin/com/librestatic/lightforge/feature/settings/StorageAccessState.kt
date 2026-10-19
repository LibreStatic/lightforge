package com.librestatic.lightforge.feature.settings

import android.content.Intent
import android.net.Uri

/**
 * An item Lightforge hid, as shown in Settings (ADR-046): a folder with a `.nomedia` file, or, when [isFile] is true,
 * a single file renamed with a leading dot. [absolutePath] is the hidden path.
 */
data class HiddenFolderUi(val absolutePath: String, val createdAtMillis: Long, val isFile: Boolean = false)

enum class StorageAccessMessageKind { PermissionNeeded, NotAllUnhidden, IncludeFailed }

/** One-shot explanation shown on the page until the next action or resume. [count] is the folders left hidden. */
data class StorageAccessMessage(val kind: StorageAccessMessageKind, val count: Int = 0)

/**
 * State of the "Storage access" settings page. [advancedSupported] is false in builds without the
 * advanced storage module or with the All files access flag off; the advanced section is then absent.
 */
data class StorageAccessUiState(
    val manageMediaSupported: Boolean = false,
    val manageMediaGranted: Boolean = false,
    val advancedSupported: Boolean = false,
    val advancedEnabled: Boolean = false,
    val allFilesGranted: Boolean = false,
    val hidden: List<HiddenFolderUi> = emptyList(),
    /** Subset of [hidden] the app can only unhide once the user picks the folder (owner delete failed). */
    val treeGrantPaths: Set<String> = emptySet(),
    val showHidden: Boolean = false,
    val deleteEmptyFolders: Boolean = false,
    val protectHiddenItems: Boolean = false,
    val appLockEnabled: Boolean = false,
    /** Absolute paths of the folders the user added to the library; the list exists only while the mode is active. */
    val scannedFolders: List<String> = emptyList(),
    /** Folders to offer to unhide after an import or reset turned the mode off; 0 means no prompt. */
    val unhidePromptCount: Int = 0,
    val busy: Boolean = false,
    val message: StorageAccessMessage? = null,
) {
    val advancedStatus: AdvancedStatus get() = advancedStatusOf(advancedEnabled, allFilesGranted)

    /** The user opted in, the permission is gone, and there is something Lightforge should undo. */
    val permissionRevoked: Boolean
        get() = advancedSupported && advancedEnabled && !allFilesGranted && hidden.isNotEmpty()

    /** Folders still hidden while the permission is gone; 0 when nothing needs the user's action. */
    val revokedWithHidden: Int get() = if (permissionRevoked) hidden.size else 0

    /** Show-hidden and delete-empty exist only while the mode is active; nothing of it shows otherwise. */
    val advancedActive: Boolean get() = advancedSupported && advancedStatus == AdvancedStatus.Active

    /** The lock row exists only where hidden items can exist and the app lock can protect them. */
    val protectRowVisible: Boolean get() = advancedSupported && appLockEnabled

    /** Hidden items and the hidden-folders list need the app lock before they are shown. */
    val hiddenItemsProtected: Boolean get() = protectHiddenItems && protectRowVisible

    /** Whether the page has anything to show; the settings list hides the entry otherwise. */
    val hasContent: Boolean get() = manageMediaSupported || advancedSupported || hidden.isNotEmpty()
}

/** System intents and user actions of the page. Defaults keep previews and tests trivial. */
data class StorageAccessActions(
    val onRefresh: () -> Unit = {},
    /** Persists the opt-in only; the page opens [permissionIntent] itself after the explanation. */
    val onSetEnabled: (Boolean) -> Unit = {},
    /** Unhides every recorded folder, then turns the mode off unless the permission is missing. */
    val onUnhideAndTurnOff: () -> Unit = {},
    val onUnhide: (String) -> Unit = {},
    val onUnhideAll: () -> Unit = {},
    /** Finishes an unhide with a folder the user picked in the system picker (ADR-046). */
    val onUnhideWithTree: (String, Uri) -> Unit = { _, _ -> },
    val onSetShowHidden: (Boolean) -> Unit = {},
    val onSetDeleteEmptyFolders: (Boolean) -> Unit = {},
    /** Adds the folder picked in the system picker to the library and scans it. */
    val onIncludeFolder: (Uri) -> Unit = {},
    val onRemoveScannedFolder: (String) -> Unit = {},
    val onRescanScannedFolders: () -> Unit = {},
    val onDismissUnhidePrompt: () -> Unit = {},
    /** "Keep hidden" on the unhide prompt: also remembered so the launch prompt does not return for these folders. */
    val onKeepHidden: () -> Unit = {},
    /** Runs [onSuccess] after the app lock check when [protectHiddenItems] is on; the host owns the biometric UI. */
    val requireUnlock: (onSuccess: () -> Unit) -> Unit = { it() },
    val manageMediaIntent: () -> Intent? = { null },
    val permissionIntent: () -> Intent? = { null },
)

/** Bundle handed to the settings screen; null there means the page does not exist. */
data class StorageAccessSettings(
    val state: StorageAccessUiState,
    val actions: StorageAccessActions,
)

enum class AdvancedStatus { Off, Active, WaitingForPermission }

/** The mode is effectively active only when the user opted in AND the system grants the permission. */
fun advancedStatusOf(enabled: Boolean, granted: Boolean): AdvancedStatus = when {
    !enabled -> AdvancedStatus.Off
    granted -> AdvancedStatus.Active
    else -> AdvancedStatus.WaitingForPermission
}

enum class TurnOffAction { TurnOffDirectly, AskAboutHiddenFolders }

/** Turning the mode off asks what to do with hidden folders only when there are some. */
fun turnOffAction(hiddenCount: Int): TurnOffAction =
    if (hiddenCount > 0) TurnOffAction.AskAboutHiddenFolders else TurnOffAction.TurnOffDirectly

private val PrimaryVolumePrefixes = listOf(
    Regex("^/storage/emulated/\\d+/"),
    Regex("^/sdcard/"),
    Regex("^/storage/self/primary/"),
)
private val OtherVolumePrefix = Regex("^/storage/([^/]+)/")

/** The name to show for a hidden item: its last path segment, without the leading dot that hides a file. */
fun hiddenItemName(entry: HiddenFolderUi): String {
    val name = entry.absolutePath.trimEnd('/').substringAfterLast('/')
    return (if (entry.isFile) name.removePrefix(".") else name).ifEmpty { readableHiddenPath(entry.absolutePath) }
}

/** The readable path of the folder that holds a hidden item. */
fun hiddenItemParent(entry: HiddenFolderUi): String =
    readableHiddenPath(entry.absolutePath.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = "/").ifEmpty { "/" })

/** `/storage/emulated/0/Pictures/Trip` becomes `Pictures/Trip`; other volumes keep their label as a prefix. */
fun readableHiddenPath(absolutePath: String): String {
    val path = absolutePath.trimEnd('/')
    PrimaryVolumePrefixes.firstNotNullOfOrNull { prefix -> prefix.find(path) }
        ?.let { return path.removeRange(it.range).ifEmpty { "/" } }
    OtherVolumePrefix.find(path)?.let { match ->
        val rest = path.removeRange(match.range)
        return if (rest.isEmpty()) match.groupValues[1] else "${match.groupValues[1]}/$rest"
    }
    return path.ifEmpty { "/" }
}

/**
 * Document id of a folder for the picker's initial location, or null for a path outside a mounted volume.
 * `/storage/emulated/0/Pictures/Trip` becomes `primary:Pictures/Trip`; other volumes use their UUID.
 */
fun treeDocumentIdOf(absolutePath: String): String? {
    val path = absolutePath.trimEnd('/')
    PrimaryVolumePrefixes.firstNotNullOfOrNull { prefix -> prefix.find("$path/") }
        ?.let { return "primary:" + "$path/".removeRange(it.range).trimEnd('/') }
    OtherVolumePrefix.find("$path/")?.let { match ->
        return match.groupValues[1] + ":" + "$path/".removeRange(match.range).trimEnd('/')
    }
    return null
}
