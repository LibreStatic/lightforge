package com.librestatic.lightforge

import com.librestatic.lightforge.core.mediastore.AdvancedResult
import com.librestatic.lightforge.core.mediastore.AdvancedStorage
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind

/** The facts about one library item that a file operation needs; read from the index before it runs. */
internal data class TransferSource(
    val key: MediaKey,
    val kind: MediaKind,
    val volumeName: String,
    val relativePath: String,
    val displayName: String,
)

internal enum class TransferPurpose { Move, Copy }

/** A destination folder: a volume plus a relative path, null for the volume root. */
internal data class FolderTarget(val volumeName: String, val relativePath: String?)

/**
 * What a batch did. [moved] items were renamed in place (their index row, and everything attached
 * to it, was kept); [copied] are verified copies; [awaitingDelete] are originals of a verified copy
 * whose deletion the user still approves; [skipped] were already in the destination.
 */
internal data class TransferResult(
    val purpose: TransferPurpose,
    val total: Int,
    val moved: Int = 0,
    val copied: Int = 0,
    val awaitingDelete: List<TransferSource> = emptyList(),
    val skipped: Int = 0,
    val failed: Int = 0,
    val notGranted: Boolean = false,
    /** The mode was not active, so nothing was touched. */
    val inactive: Boolean = false,
) {
    val succeeded: Int get() = moved + copied + skipped
}

internal data class FolderOpResult(val result: AdvancedResult, val reconciled: List<String> = emptyList())

/** Hands the originals of verified cross-volume copies to the existing verified delete (system confirmation). */
internal fun interface OriginalRetirement {
    suspend fun retire(originals: List<TransferSource>)
}

/**
 * Moves, copies and renames media by path while advanced storage is active (ADR-046). One strategy
 * per item: [AdvancedStorage.moveAll] when source and target share a volume (the index row keeps its
 * id and every saved decision attached to it), otherwise a verified copy followed by the existing
 * verified delete of the original. An item whose rename already succeeded never falls back, so no
 * duplicate is made. Every item is first checked against its current MediaStore row, and an original
 * is retired only after its copy is byte-compared with the item's own content URI; a crash before
 * that leaves a copy beside the original, never a lost file. With the mode inactive every entry point does nothing; callers use the SAF flow.
 */
internal class AdvancedFileOperations(
    private val advanced: AdvancedStorage,
    private val isActive: () -> Boolean,
    private val deleteEmptyFolders: () -> Boolean,
    private val sources: suspend (List<MediaKey>) -> List<TransferSource>,
    private val applyHint: suspend (MediaKey) -> Unit,
    private val reconcile: suspend (volumeName: String, relativePathPrefix: String) -> Unit,
    private val refresh: suspend () -> Unit,
    private val retirement: OriginalRetirement,
    private val folderBuckets: suspend (volumeName: String, prefix: String) -> Map<String, Long>,
    private val folderRelocated: suspend (
        oldVolume: String,
        oldPrefix: String,
        newVolume: String,
        newPrefix: String,
        previousBuckets: Map<String, Long>,
    ) -> Unit,
    /** The keys whose MediaStore row is still where the index says it is; others are never touched. */
    private val verifyIdentity: suspend (List<TransferSource>) -> Set<MediaKey>,
    /** True when the file at the target path has the bytes of the item's own content URI. */
    private val verifyCopy: suspend (TransferSource, targetPath: String) -> Boolean,
) {
    suspend fun transfer(
        purpose: TransferPurpose,
        keys: List<MediaKey>,
        destination: FolderTarget,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): TransferResult {
        val total = keys.size
        if (!isActive()) return TransferResult(purpose, total, inactive = true)
        onProgress(0, total)
        val indexed = sources(keys)
        val stillThere = verifyIdentity(indexed)
        // A row whose file moved or was renamed since the last scan could point at another file.
        val byKey = indexed.filter { it.key in stillThere }.associateBy { it.key }
        var failed = keys.count { it !in byKey }
        var skipped = 0
        val toMove = ArrayList<Planned>()
        val toCopy = ArrayList<Planned>()
        for (key in keys) {
            val source = byKey[key] ?: continue
            val from = advanced.absolutePathOf(source.volumeName, source.relativePath, source.displayName)
            val to = advanced.absolutePathOf(destination.volumeName, destination.relativePath, source.displayName)
            when {
                from == null || to == null -> failed++
                from == to -> skipped++
                purpose == TransferPurpose.Move && source.volumeName == destination.volumeName ->
                    toMove += Planned(source, from, to)
                else -> toCopy += Planned(source, from, to)
            }
        }

        var notGranted = false
        val moved = ArrayList<Planned>()
        if (toMove.isNotEmpty()) {
            val results = advanced.moveAll(toMove.map { it.from to it.to })
            toMove.forEachIndexed { index, planned ->
                when (results.getOrNull(index) ?: AdvancedResult.Unsupported) {
                    AdvancedResult.Done -> moved += planned
                    AdvancedResult.NotGranted -> { notGranted = true; failed++ }
                    // Only the item the rename refused falls back to the verified copy.
                    else -> toCopy += planned
                }
            }
            onProgress(moved.size + skipped, total)
        }

        val copied = ArrayList<Planned>()
        if (toCopy.isNotEmpty()) {
            val results = advanced.copyAll(toCopy.map { it.from to it.to })
            toCopy.forEachIndexed { index, planned ->
                when (results.getOrNull(index) ?: AdvancedResult.Unsupported) {
                    AdvancedResult.Done -> copied += planned
                    AdvancedResult.NotGranted -> { notGranted = true; failed++ }
                    else -> failed++
                }
            }
        }

        // The rows of renamed files are re-read at once, so Room keeps their identity.
        moved.forEach { applyHint(it.source.key) }
        if (moved.isNotEmpty() || copied.isNotEmpty()) runCatching { refresh() }
        // An original is only retired once its copy is proven to hold the item's own bytes.
        val originals = if (purpose == TransferPurpose.Move) {
            copied.filter { verifyCopy(it.source, it.to) }.map { it.source }.also { failed += copied.size - it.size }
        } else {
            emptyList()
        }
        if (originals.isNotEmpty()) retirement.retire(originals)
        if (moved.isNotEmpty()) deleteEmptyParents(moved.map { it.source.volumeName to it.source.relativePath })
        onProgress(total, total)
        return TransferResult(
            purpose = purpose,
            total = total,
            moved = moved.size,
            copied = if (purpose == TransferPurpose.Copy) copied.size else 0,
            awaitingDelete = originals,
            skipped = skipped,
            failed = failed,
            notGranted = notGranted,
        )
    }

    /**
     * Removes the folders in [folders] (volume to relative path) that are now empty, once each, when
     * the "remove empty folders" option is on. A folder that still has content is left alone.
     */
    suspend fun deleteEmptyParents(folders: Collection<Pair<String, String>>) {
        if (!deleteEmptyFolders() || !isActive()) return
        folders.filter { (_, path) -> path.isNotBlank() }.distinct().forEach { (volume, path) ->
            val absolute = advanced.absolutePathOf(volume, path) ?: return@forEach
            // "Directory not empty" and every other refusal mean the folder stays.
            advanced.deleteEmptyDirectory(absolute)
        }
    }

    /** The parents of [keys] as they are now; read before an action removes the rows. */
    suspend fun parentsOf(keys: List<MediaKey>): List<Pair<String, String>> =
        sources(keys).map { it.volumeName to it.relativePath }

    /**
     * Renames a file next to where it is. True when it was renamed and the row re-read; false means
     * the caller continues with the permission-based write request.
     */
    suspend fun renameSibling(key: MediaKey, newDisplayName: String): Boolean {
        if (!isActive()) return false
        val source = sources(listOf(key)).singleOrNull()?.takeIf { it.key in verifyIdentity(listOf(it)) } ?: return false
        // A bare rename would let the name drop or change its extension, and MediaStore would then
        // stop indexing the file (and the row, with its favourites and albums, would be removed).
        val safeName = renameKeepingKind(source.displayName, newDisplayName) ?: return false
        val from = advanced.absolutePathOf(source.volumeName, source.relativePath, source.displayName) ?: return false
        val to = advanced.absolutePathOf(source.volumeName, source.relativePath, safeName) ?: return false
        if (advanced.renameInPlace(from, to) != AdvancedResult.Done) return false
        applyHint(key)
        return true
    }

    /** Moves the folder into [destination]; both prefixes are re-read afterwards. */
    suspend fun moveFolder(source: FolderTarget, destination: FolderTarget): FolderOpResult {
        val sourcePath = source.relativePath?.takeIf { it.isNotBlank() } ?: return FolderOpResult(AdvancedResult.Failed("volume root"))
        if (!isActive()) return FolderOpResult(AdvancedResult.NotGranted)
        val from = advanced.absolutePathOf(source.volumeName, sourcePath) ?: return FolderOpResult(AdvancedResult.Failed("unknown path"))
        val parent = advanced.absolutePathOf(destination.volumeName, destination.relativePath)
            ?: return FolderOpResult(AdvancedResult.Failed("unknown path"))
        val leaf = sourcePath.trim('/').substringAfterLast('/')
        val newPath = (destination.relativePath.orEmpty().trim('/') + "/" + leaf).trim('/') + "/"
        val buckets = runCatching { folderBuckets(source.volumeName, sourcePath) }.getOrDefault(emptyMap())
        val result = advanced.moveDirectory(from, parent)
        if (result != AdvancedResult.Done) return FolderOpResult(result)
        return FolderOpResult(result, reconcileAfterMove(source.volumeName, destination.volumeName, sourcePath, newPath, buckets))
    }

    /** Renames the folder in place; both prefixes are re-read afterwards. */
    suspend fun renameFolder(source: FolderTarget, newName: String): FolderOpResult {
        val sourcePath = source.relativePath?.takeIf { it.isNotBlank() } ?: return FolderOpResult(AdvancedResult.Failed("volume root"))
        if (!isActive()) return FolderOpResult(AdvancedResult.NotGranted)
        val from = advanced.absolutePathOf(source.volumeName, sourcePath) ?: return FolderOpResult(AdvancedResult.Failed("unknown path"))
        val newPath = renamedRelativePath(sourcePath, newName)
        val buckets = runCatching { folderBuckets(source.volumeName, sourcePath) }.getOrDefault(emptyMap())
        val result = advanced.renameDirectory(from, newName)
        if (result != AdvancedResult.Done) return FolderOpResult(result)
        return FolderOpResult(result, reconcileAfterMove(source.volumeName, source.volumeName, sourcePath, newPath, buckets))
    }

    // The new prefix first, so rows that kept their id are updated before the old prefix is swept.
    // The folder rules move once the new rows are indexed, because their bucket ids are read from them.
    private suspend fun reconcileAfterMove(
        oldVolume: String,
        newVolume: String,
        oldPath: String,
        newPath: String,
        buckets: Map<String, Long>,
    ): List<String> {
        runCatching { reconcile(newVolume, newPath) }
        runCatching { folderRelocated(oldVolume, oldPath, newVolume, newPath, buckets) }
        runCatching { reconcile(oldVolume, oldPath) }
        runCatching { refresh() }
        return listOf(newPath, oldPath)
    }

    private class Planned(val source: TransferSource, val from: String, val to: String)
}

/**
 * The name to rename a media file to with a plain file rename, or null when the caller must use the
 * permission-based write request instead: a name starting with a dot (it would hide the file) or an
 * extension that differs from the current one. A name without any extension keeps the current one.
 */
internal fun renameKeepingKind(currentName: String?, requested: String): String? {
    val name = requested.trim()
    if (name.isEmpty() || name.startsWith(".")) return null
    val currentExt = currentName?.substringAfterLast('.', "").orEmpty()
    if (currentExt.isEmpty()) return name
    val requestedExt = name.substringAfterLast('.', "")
    return when {
        requestedExt.equals(currentExt, ignoreCase = true) && name.length > currentExt.length + 1 -> name
        '.' !in name -> "$name.$currentExt"
        else -> null
    }
}
