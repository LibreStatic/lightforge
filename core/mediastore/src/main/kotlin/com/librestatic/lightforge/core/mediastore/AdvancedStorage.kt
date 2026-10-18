package com.librestatic.lightforge.core.mediastore

import android.content.Intent
import android.net.Uri

/**
 * Optional "advanced storage" capabilities backed by All files access (MANAGE_EXTERNAL_STORAGE).
 * The rest of the app only sees this interface; the implementation lives in the isolated
 * `:core:storage-advanced` module and is bound through `BuildConfig.ALL_FILES_ACCESS` (ADR-046).
 * Builds without the module, or with the flag off, use [NoOpAdvancedStorage].
 */
interface AdvancedStorage {
    /** Compiled into this build (BuildConfig flag on and module present). */
    val isSupported: Boolean

    /** Supported AND the user granted All files access. Re-read on every call; the user can revoke anytime. */
    fun isGranted(): Boolean

    /** Intent to the system "All files access" screen for this package, or null when unsupported. */
    fun permissionIntent(): Intent?

    suspend fun hideFolder(absolutePath: String): AdvancedResult

    suspend fun unhideFolder(absolutePath: String): AdvancedResult

    /** Every hide this app performed and has not undone; readable even after the permission is revoked. */
    suspend fun hiddenByApp(): List<HiddenEntry>

    /** Tries to undo every recorded hide; returns the entries that could not be undone. */
    suspend fun unhideAllRecorded(): List<HiddenEntry>

    /**
     * Same-volume rename of a media file followed by a MediaStore rescan. Refuses cross-volume moves
     * and never overwrites an existing target; callers fall back to the verified copy path on any
     * result other than [AdvancedResult.Done].
     */
    suspend fun renameInPlace(sourceAbsolutePath: String, targetAbsolutePath: String): AdvancedResult

    /** Deletes the directory only if it exists and is empty. */
    suspend fun deleteEmptyDirectory(absolutePath: String): AdvancedResult

    /**
     * Maps a MediaStore volume name plus a relative path and display name to an absolute path of a
     * mounted volume. The only place that maps volumes to paths; null when unsupported, unmounted or
     * when the parts would escape the volume.
     */
    fun absolutePathOf(volumeName: String, relativePath: String? = null, displayName: String? = null): String? = null

    /** Creates a new folder named [name] inside [parentAbsolutePath]; never overwrites. */
    suspend fun createDirectory(parentAbsolutePath: String, name: String): AdvancedResult = AdvancedResult.Unsupported

    /** Renames a folder in place (same parent) and rescans it. */
    suspend fun renameDirectory(absolutePath: String, newName: String): AdvancedResult = AdvancedResult.Unsupported

    /** Moves a folder inside [newParentAbsolutePath] on the same volume and rescans it. */
    suspend fun moveDirectory(absolutePath: String, newParentAbsolutePath: String): AdvancedResult =
        AdvancedResult.Unsupported

    /**
     * Batch of same-volume file moves (source to target absolute paths) under one lock and one scan.
     * The result list has one entry per input pair, in order.
     */
    suspend fun moveAll(moves: List<Pair<String, String>>): List<AdvancedResult> =
        moves.map { AdvancedResult.Unsupported }

    /**
     * Batch of verified file copies (source to target absolute paths), the fallback for a move that
     * [moveAll] refused and for Copy to folder. Each target is written under a temporary name, compared
     * with the source by SHA-256, renamed into place without overwriting and scanned once at the end.
     * The result list has one entry per input pair, in order; a failed copy leaves no partial file.
     */
    suspend fun copyAll(copies: List<Pair<String, String>>): List<AdvancedResult> =
        copies.map { AdvancedResult.Unsupported }

    /** Immediate sub-folders of [absolutePath] (a volume root is allowed); empty when unsupported or not granted. */
    suspend fun listDirectories(absolutePath: String): List<DirectoryEntry> = emptyList()

    /**
     * Image and video rows MediaStore keeps as plain files (`media_type=0`) because a `.nomedia` or a
     * dot-prefixed folder excludes them, plus media rows under dot-prefixed folders. Empty unless granted.
     * The mode and the show-hidden setting are the caller's gate.
     */
    suspend fun queryHiddenMedia(): List<HiddenMediaRow> = emptyList()

    /**
     * Removes the `.nomedia` this app recorded in the folder through a SAF tree grant on that folder
     * (the fallback after [AdvancedResult.NeedsTreeGrant]) and rescans it.
     */
    suspend fun unhideWithTree(absolutePath: String, treeUri: Uri): AdvancedResult = AdvancedResult.Unsupported
}

data class DirectoryEntry(
    val name: String,
    val absolutePath: String,
    val isEmpty: Boolean,
    val hasNoMedia: Boolean,
    val isDotPrefixed: Boolean,
)

enum class HiddenMediaKind { Image, Video }

data class HiddenMediaRow(
    val volumeName: String,
    val mediaStoreId: Long,
    val relativePath: String,
    val displayName: String,
    val mimeType: String,
    val kind: HiddenMediaKind,
    val sizeBytes: Long,
    val dateTakenOrModifiedMillis: Long,
    val contentUri: Uri,
)

data class HiddenEntry(val absolutePath: String, val kind: HiddenKind, val createdAtMillis: Long)

/** Only [NoMediaFile] is implemented; [DotPrefix] is reserved. */
enum class HiddenKind { NoMediaFile, DotPrefix }

/** The [AdvancedResult.Failed] reason of a hide refused because another app already placed a `.nomedia`. */
const val ALREADY_HIDDEN_BY_OTHERS: String = "already hidden by someone else"

sealed interface AdvancedResult {
    data object Done : AdvancedResult
    data object Unsupported : AdvancedResult
    data object NotGranted : AdvancedResult
    data class Failed(val reason: String) : AdvancedResult

    /** The `.nomedia` could not be removed without the permission; ask for a SAF tree grant on [absolutePath]. */
    data class NeedsTreeGrant(val absolutePath: String) : AdvancedResult
}

/** Default binding: no advanced storage in this build. */
object NoOpAdvancedStorage : AdvancedStorage {
    override val isSupported: Boolean = false
    override fun isGranted(): Boolean = false
    override fun permissionIntent(): Intent? = null
    override suspend fun hideFolder(absolutePath: String): AdvancedResult = AdvancedResult.Unsupported
    override suspend fun unhideFolder(absolutePath: String): AdvancedResult = AdvancedResult.Unsupported
    override suspend fun hiddenByApp(): List<HiddenEntry> = emptyList()
    override suspend fun unhideAllRecorded(): List<HiddenEntry> = emptyList()
    override suspend fun renameInPlace(sourceAbsolutePath: String, targetAbsolutePath: String): AdvancedResult =
        AdvancedResult.Unsupported
    override suspend fun deleteEmptyDirectory(absolutePath: String): AdvancedResult = AdvancedResult.Unsupported
}
