package com.librestatic.lightforge.feature.settings

import android.content.Intent

/** A folder Lightforge hid with a `.nomedia` file, as shown in Settings (ADR-046). */
data class HiddenFolderUi(val absolutePath: String, val createdAtMillis: Long)

enum class StorageAccessMessageKind { PermissionNeeded, NotAllUnhidden }

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
    val busy: Boolean = false,
    val message: StorageAccessMessage? = null,
) {
    val advancedStatus: AdvancedStatus get() = advancedStatusOf(advancedEnabled, allFilesGranted)

    /** The user opted in, the permission is gone, and there is something Lightforge should undo. */
    val permissionRevoked: Boolean
        get() = advancedSupported && advancedEnabled && !allFilesGranted && hidden.isNotEmpty()

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
