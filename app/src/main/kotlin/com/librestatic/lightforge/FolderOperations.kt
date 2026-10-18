package com.librestatic.lightforge

import androidx.paging.PagingData
import com.librestatic.lightforge.core.mediastore.ALREADY_HIDDEN_BY_OTHERS
import com.librestatic.lightforge.core.mediastore.AdvancedResult
import com.librestatic.lightforge.core.mediastore.HiddenMediaRow
import com.librestatic.lightforge.core.model.TimelineMedia
import com.librestatic.lightforge.core.model.folders.HiddenFolderGroup
import com.librestatic.lightforge.feature.folders.normalizedFolderPath
import kotlinx.coroutines.flow.Flow

/** What the folder explorer needs from the library; the gallery view model owns the real thing. */
interface FolderLibrary {
    fun media(volumeName: String, relativePath: String, includeSubfolders: Boolean): Flow<PagingData<TimelineMedia>>

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

enum class FolderOp { Hide, Unhide, Rename, Delete, Create, Move }

/** One-shot outcome of a folder operation, shown in the app snackbar. */
sealed interface FolderMessage {
    data class Done(val op: FolderOp) : FolderMessage

    /** The permission is missing or the mode is off; the action opens the permission screen. */
    data object NotGranted : FolderMessage

    /** Another app already hid the folder with its own .nomedia, so this app cannot unhide it. */
    data object AlreadyHiddenByOthers : FolderMessage

    data class Failed(val op: FolderOp) : FolderMessage
}

internal fun AdvancedResult.toFolderMessage(op: FolderOp): FolderMessage = when (this) {
    AdvancedResult.Done -> FolderMessage.Done(op)
    AdvancedResult.NotGranted -> FolderMessage.NotGranted
    is AdvancedResult.Failed ->
        if (reason == ALREADY_HIDDEN_BY_OTHERS) FolderMessage.AlreadyHiddenByOthers else FolderMessage.Failed(op)
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
)

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
)

/** Groups hidden rows by folder and kind, the shape the folder tree overlay takes. */
internal fun groupHiddenRows(rows: List<HiddenMediaRow>): List<HiddenFolderGroup> = groupHiddenFacts(rows.map { it.facts() })

internal fun groupHiddenFacts(rows: List<HiddenRowFacts>): List<HiddenFolderGroup> = rows
    .filter { normalizedFolderPath(it.relativePath).isNotEmpty() }
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
        )
    }
