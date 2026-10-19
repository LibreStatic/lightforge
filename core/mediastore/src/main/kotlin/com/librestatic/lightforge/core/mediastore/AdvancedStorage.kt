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

    /** Whether [absolutePath] exists inside a mounted volume; false when unsupported, not granted or refused. */
    suspend fun pathExists(absolutePath: String): Boolean = false

    /**
     * Asks the media scanner to index the photos and videos under [absolutePath], recursively; this is how
     * a folder MediaStore does not list is added to the library. Returns the number of files handed to the
     * scanner, or null when unsupported, not granted or refused.
     */
    suspend fun scanFolderTree(absolutePath: String): Int? = null

    /**
     * Hides one media file by renaming it with a leading dot (`IMG.jpg` becomes `.IMG.jpg`) and records it
     * ([HiddenKind.DotPrefix]) so [unhideFolder] can rename it back. The recorded path is the hidden one.
     * Folders are refused: a `.nomedia` hides those.
     */
    suspend fun hideWithDotPrefix(absolutePath: String): AdvancedResult = AdvancedResult.Unsupported

    /** Deletes files (one result each, one scan). A file that is already gone counts as [AdvancedResult.Done]. */
    suspend fun deleteFiles(absolutePaths: List<String>): List<AdvancedResult> =
        absolutePaths.map { AdvancedResult.Unsupported }

    /**
     * Copies the folder into [newParentAbsolutePath] as [newName] (default: its own name) with every file
     * byte-checked. A failure leaves nothing behind; an existing target is refused.
     */
    suspend fun copyDirectory(absolutePath: String, newParentAbsolutePath: String, newName: String? = null): AdvancedResult =
        AdvancedResult.Unsupported

    /**
     * Deletes the folder and everything in it; standard folders, volume roots and the Android tree are
     * refused. A folder that holds items in the Trash is refused too ([FOLDER_HAS_TRASH]), so a trashed
     * photo is never lost behind a folder delete.
     */
    suspend fun deleteDirectory(absolutePath: String): AdvancedResult = AdvancedResult.Unsupported

    /**
     * What a recursive delete of the folder would remove: every file and sub-folder on disk, not only the
     * indexed media. Null when unsupported, not granted or the path is refused.
     */
    suspend fun inspectDirectory(absolutePath: String): DirectoryContents? = null

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

/**
 * The real contents of a folder tree on disk. [files] counts every regular file ([mediaFiles] photos and
 * videos by file name, [otherFiles] the rest, [trashedFiles] the `.trashed-` and `.pending-` items of the
 * system Trash); [hiddenMedia] are photos and videos inside a hidden or dot-prefixed folder.
 */
data class DirectoryContents(
    val files: Long,
    val folders: Long,
    val bytes: Long,
    val mediaFiles: Long,
    val otherFiles: Long,
    val hiddenMedia: Long,
    val trashedFiles: Long,
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

/** [NoMediaFile]: a `.nomedia` inside the folder. [DotPrefix]: the folder or file was renamed with a leading dot. */
enum class HiddenKind { NoMediaFile, DotPrefix }

/** The [AdvancedResult.Failed] reason of a hide refused because another app already placed a `.nomedia`. */
const val ALREADY_HIDDEN_BY_OTHERS: String = "already hidden by someone else"

/** The [AdvancedResult.Failed] reason of a folder delete refused because the folder holds items in the Trash. */
const val FOLDER_HAS_TRASH: String = "folder holds items in Trash"

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
