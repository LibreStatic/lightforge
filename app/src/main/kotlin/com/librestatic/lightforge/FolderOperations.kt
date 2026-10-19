package com.librestatic.lightforge

import androidx.paging.PagingData
import com.librestatic.lightforge.core.mediastore.ALREADY_HIDDEN_BY_OTHERS
import com.librestatic.lightforge.core.mediastore.AdvancedResult
import com.librestatic.lightforge.core.mediastore.AdvancedStorage
import com.librestatic.lightforge.core.mediastore.FOLDER_HAS_TRASH
import com.librestatic.lightforge.core.database.AlbumMediaFilter
import com.librestatic.lightforge.core.database.AlbumSort
import com.librestatic.lightforge.feature.folders.FolderDeleteSummary
import com.librestatic.lightforge.feature.folders.FolderFileFilter
import com.librestatic.lightforge.feature.folders.FolderFileSort
import com.librestatic.lightforge.core.mediastore.HiddenMediaRow
import com.librestatic.lightforge.core.model.TimelineMedia
import com.librestatic.lightforge.core.model.folders.HiddenFolderGroup
import com.librestatic.lightforge.feature.folders.normalizedFolderPath
import kotlinx.coroutines.flow.Flow

/** What the folder explorer needs from the library; the gallery view model owns the real thing. */
interface FolderLibrary {
    fun media(
        volumeName: String,
        relativePath: String,
        includeSubfolders: Boolean,
        filter: FolderFileFilter = FolderFileFilter.All,
        sort: FolderFileSort = FolderFileSort.NewestFirst,
    ): Flow<PagingData<TimelineMedia>>

    /** Re-reads one folder subtree from MediaStore, then refreshes the library views. */
    suspend fun reconcile(volumeName: String, relativePathPrefix: String)

    /** A full catch-up with MediaStore. */
    suspend fun refresh()

    /** Relative path to bucket id of the indexed folders at or under [relativePathPrefix]; read before a rename or move. */
    suspend fun folderBuckets(volumeName: String, relativePathPrefix: String): Map<String, Long> = emptyMap()

    /**
     * A folder was renamed or moved: its folder rules (excluded, included) follow it to the new path
     * and bucket. [previousBuckets] is what [folderBuckets] returned before the operation.
     */
    suspend fun folderRelocated(
        oldVolume: String,
        oldPrefix: String,
        newVolume: String,
        newPrefix: String,
        previousBuckets: Map<String, Long>,
    ) = Unit
}

enum class FolderOp { Hide, Unhide, Rename, Delete, Create, Move, Copy }

/** One-shot outcome of a folder operation, shown in the app snackbar. */
sealed interface FolderMessage {
    data class Done(val op: FolderOp) : FolderMessage

    /** The permission is missing or the mode is off; the action opens the permission screen. */
    data object NotGranted : FolderMessage

    /** Another app already hid the folder with its own .nomedia, so this app cannot unhide it. */
    data object AlreadyHiddenByOthers : FolderMessage

    /** The folder holds items of the Trash, which a delete would orphan, so nothing was deleted. */
    data object FolderHasTrash : FolderMessage

    data class Failed(val op: FolderOp) : FolderMessage
}

internal fun AdvancedResult.toFolderMessage(op: FolderOp): FolderMessage = when (this) {
    AdvancedResult.Done -> FolderMessage.Done(op)
    AdvancedResult.NotGranted -> FolderMessage.NotGranted
    is AdvancedResult.Failed ->
        when (reason) {
            ALREADY_HIDDEN_BY_OTHERS -> FolderMessage.AlreadyHiddenByOthers
            FOLDER_HAS_TRASH -> FolderMessage.FolderHasTrash
            else -> FolderMessage.Failed(op)
        }
    AdvancedResult.Unsupported, is AdvancedResult.NeedsTreeGrant -> FolderMessage.Failed(op)
}

/** The relative path ("A/B/") a folder has after renaming its last segment to [newName]. */
internal fun renamedRelativePath(relativePath: String, newName: String): String {
    val parent = normalizedFolderPath(relativePath).trimEnd('/').substringBeforeLast('/', "")
    return normalizedFolderPath("$parent/$newName")
}

/** The parts of a hidden row the folder tree needs (the content URI stays out, so this is plain JVM data). */
internal data class HiddenRowFacts(
    val volumeName: String,
    val mediaStoreId: Long,
    val relativePath: String,
    val kind: com.librestatic.lightforge.core.model.folders.HiddenMediaKind,
    val sizeBytes: Long,
    val dateMillis: Long,
    val displayName: String = "",
)

/**
 * Whether this row proves its folder is hidden. A file that only carries a dot name inside a folder whose own path
 * has no dot segment is hidden by its name alone; every other hidden row sits in a `.nomedia` folder or below a
 * dot-named one. (A dot-named file in a `.nomedia` folder is told apart by the disk overlay, which reads the marker.)
 */
internal fun HiddenRowFacts.hidesFolder(): Boolean =
    !displayName.startsWith(".") || relativePath.split('/').any { it.startsWith(".") }

internal fun HiddenMediaRow.facts() = HiddenRowFacts(
    volumeName = volumeName,
    mediaStoreId = mediaStoreId,
    relativePath = relativePath,
    kind = when (kind) {
        com.librestatic.lightforge.core.mediastore.HiddenMediaKind.Image ->
            com.librestatic.lightforge.core.model.folders.HiddenMediaKind.Image
        com.librestatic.lightforge.core.mediastore.HiddenMediaKind.Video ->
            com.librestatic.lightforge.core.model.folders.HiddenMediaKind.Video
    },
    sizeBytes = sizeBytes,
    dateMillis = dateTakenOrModifiedMillis,
    displayName = displayName,
)

/**
 * The directories on disk the explorer adds to the library tree for [location]: the children of every level from
 * the volume root down to the folder itself, so a folder only the disk knows (new, empty, or holding nothing the
 * library indexes) stays reachable together with the ones created inside it. [listChildren] lists the directories
 * of a relative path (null is the volume root). A folder carrying `.nomedia` or a dot name is left out unless
 * hidden items are shown; a folder that is only a lone `.nomedia` marker still counts as empty.
 */
internal suspend fun diskOverlayDirectories(
    volumeName: String,
    location: String?,
    showHidden: Boolean,
    listChildren: suspend (String?) -> List<com.librestatic.lightforge.core.mediastore.DirectoryEntry>?,
): List<com.librestatic.lightforge.core.model.folders.EmptyDirectory> {
    val segments = normalizedFolderPath(location.orEmpty()).trim('/').split('/').filter { it.isNotEmpty() }
    val result = LinkedHashSet<com.librestatic.lightforge.core.model.folders.EmptyDirectory>()
    var underHidden = false
    for (depth in 0..segments.size) {
        val parent = if (depth == 0) null else segments.take(depth).joinToString("/", postfix = "/")
        // A level that is gone ends the walk: nothing below it exists either.
        val children = listChildren(parent) ?: break
        children
            .filter { !com.librestatic.lightforge.core.model.folders.isSystemEntryName(it.name) }
            .filter { (showHidden || !it.isDotPrefixed) && (it.isEmpty || showHidden || !it.hasNoMedia) }
            .mapTo(result) {
                com.librestatic.lightforge.core.model.folders.EmptyDirectory(
                    volumeName,
                    "${parent.orEmpty()}${it.name}/",
                    it.isEmpty,
                    isHidden = underHidden || it.hasNoMedia || it.isDotPrefixed,
                )
            }
        // The next level is hidden when the folder walked into is, or when it sits below one that is.
        val next = segments.getOrNull(depth)
        val entry = next?.let { name -> children.firstOrNull { it.name == name } }
        underHidden = underHidden || entry?.let { it.hasNoMedia || it.isDotPrefixed } == true
    }
    return result.toList()
}

/**
 * Keeps the [items] whose file is still on disk. MediaStore can keep a row for a file that is gone (a stale entry),
 * and opening it would fail. [pathOf] gives the absolute path (null when it cannot be known, which drops the item).
 */
internal fun <T> filterExistingFiles(items: List<T>, pathOf: (T) -> String?, exists: (String) -> Boolean): List<T> =
    items.filter { item -> pathOf(item)?.let(exists) == true }

/** Groups hidden rows by folder and kind, the shape the folder tree overlay takes. */
internal fun groupHiddenRows(rows: List<HiddenMediaRow>): List<HiddenFolderGroup> = groupHiddenFacts(rows.map { it.facts() })

internal fun groupHiddenFacts(rows: List<HiddenRowFacts>): List<HiddenFolderGroup> = rows
    .filter { normalizedFolderPath(it.relativePath).isNotEmpty() }
    .filterNot { com.librestatic.lightforge.core.model.folders.hasSystemEntrySegment(it.relativePath) }
    .groupBy { Triple(it.volumeName, normalizedFolderPath(it.relativePath), it.kind) }
    .map { (key, group) ->
        HiddenFolderGroup(
            volumeName = key.first,
            relativePath = key.second,
            count = group.size.toLong(),
            bytes = group.sumOf { it.sizeBytes },
            minSortMillis = group.minOf { it.dateMillis },
            maxSortMillis = group.maxOf { it.dateMillis },
            coverMediaStoreId = group.maxByOrNull { it.dateMillis }?.mediaStoreId,
            kind = key.third,
            folderHidden = group.any { it.hidesFolder() },
        )
    }

/**
 * Adds up what a recursive delete of the folders at [absolutePaths] would remove, read from the disk.
 * Null when any of them cannot be read, so the dialog never confirms a delete it could not count.
 * Reads only: it never deletes.
 */
internal suspend fun summarizeFolderDelete(storage: AdvancedStorage, absolutePaths: List<String>): FolderDeleteSummary? {
    var total = FolderDeleteSummary()
    for (path in absolutePaths) {
        val contents = storage.inspectDirectory(path) ?: return null
        total = FolderDeleteSummary(
            files = total.files + contents.files,
            // The folder itself is deleted as well.
            folders = total.folders + contents.folders,
            bytes = total.bytes + contents.bytes,
            otherFiles = total.otherFiles + contents.otherFiles,
            hiddenMedia = total.hiddenMedia + contents.hiddenMedia,
            trashedFiles = total.trashedFiles + contents.trashedFiles,
        )
    }
    return total
}

internal fun FolderFileFilter.toAlbumFilter() = when (this) {
    FolderFileFilter.All -> AlbumMediaFilter.All
    FolderFileFilter.Images -> AlbumMediaFilter.Images
    FolderFileFilter.Videos -> AlbumMediaFilter.Videos
}

internal fun FolderFileSort.toAlbumSort() = when (this) {
    FolderFileSort.NewestFirst -> AlbumSort.NewestFirst
    FolderFileSort.OldestFirst -> AlbumSort.OldestFirst
    FolderFileSort.NameAscending -> AlbumSort.NameAscending
    FolderFileSort.NameDescending -> AlbumSort.NameDescending
    FolderFileSort.SizeAscending -> AlbumSort.SizeAscending
    FolderFileSort.SizeDescending -> AlbumSort.SizeDescending
}
