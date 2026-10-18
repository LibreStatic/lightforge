package com.librestatic.lightforge.core.storageadvanced

import com.librestatic.lightforge.core.mediastore.AdvancedResult
import com.librestatic.lightforge.core.mediastore.DirectoryEntry
import com.librestatic.lightforge.core.mediastore.HiddenEntry
import com.librestatic.lightforge.core.mediastore.HiddenKind
import java.io.File
import java.io.IOException

/** A mounted shared-storage volume: its MediaStore name (for example `external_primary`) and its root. */
internal data class VolumeRoot(val name: String, val root: File)

/**
 * Blocking file logic behind [FileAdvancedStorage]; Android services are injected so it is unit-testable.
 * Callers must serialize access (the facade holds a mutex) and run it off the main thread.
 *
 * [scan] receives every path of one operation in a single call and returns once the media scanner is
 * done with all of them (or gave up). [deleteOwnedMarker] removes a `.nomedia` this app owns through
 * the MediaStore row, which works without All files access.
 */
internal class StorageEngine(
    private val registry: HiddenRegistry,
    private val volumes: () -> List<VolumeRoot>,
    private val scan: (List<String>) -> Unit,
    private val isGranted: () -> Boolean = { true },
    private val deleteOwnedMarker: (File) -> Boolean = { false },
    private val renameFile: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private fun volumeRoots(): List<File> = volumes().map { it.root }

    fun hiddenByApp(): List<HiddenEntry> = registry.read()

    /** Absolute path for a MediaStore volume name plus relative path and display name, or null. */
    fun absolutePathOf(volumeName: String, relativePath: String?, displayName: String?): String? {
        val root = volumes().firstOrNull { it.name == volumeName }?.root ?: return null
        val parts = listOfNotNull(relativePath, displayName)
            .flatMap { it.split('/') }
            .filter { it.isNotEmpty() }
        if (parts.any { it == "." || it == ".." || it.contains('\u0000') }) return null
        return parts.fold(root) { acc, part -> File(acc, part) }.path
    }

    /**
     * Creates an empty `.nomedia`. A `.nomedia` that already exists and is not ours is never recorded
     * or touched: the call returns [AdvancedResult.Failed] with [ALREADY_HIDDEN_BY_OTHERS]. Hiding a
     * folder this app already hid is idempotent and returns Done.
     */
    fun hide(path: String): AdvancedResult {
        val dir = validated(path) ?: return INVALID
        if (!dir.isDirectory) return AdvancedResult.Failed("Not a directory")
        val entries = registry.read()
        val marker = File(dir, NOMEDIA)
        val canonical = dir.path
        if (marker.exists()) {
            return if (entries.any { it.absolutePath == canonical }) {
                AdvancedResult.Done
            } else {
                AdvancedResult.Failed(ALREADY_HIDDEN_BY_OTHERS)
            }
        }
        // Taken before the marker exists so the marker's mtime is never older than the record.
        val createdAt = clock()
        if (!runCatching { marker.createNewFile() }.getOrDefault(false)) {
            return AdvancedResult.Failed("Could not create .nomedia")
        }
        return try {
            // A recorded marker can vanish behind our back; re-hiding replaces the stale entry.
            registry.write(
                entries.filterNot { it.absolutePath == canonical } +
                    HiddenEntry(canonical, HiddenKind.NoMediaFile, createdAt),
            )
            scan(listOf(canonical, marker.path))
            AdvancedResult.Done
        } catch (e: Exception) {
            marker.delete()
            AdvancedResult.Failed(e.message ?: "Could not record hide")
        }
    }

    /**
     * Removes the `.nomedia` only when this app recorded it and it still looks like ours (empty, not
     * older than the record). Any other file is left alone and the entry is dropped as resolved.
     * Without All files access the marker is deleted through its MediaStore row; when that fails the
     * result is [AdvancedResult.NeedsTreeGrant].
     */
    fun unhide(path: String): AdvancedResult = unhideWith(path, treeRelativePath = null, deleteViaTree = null)

    /** [unhide] through a SAF tree grant whose document id path is [treeRelativePath]. */
    fun unhideWithTree(path: String, treeRelativePath: String, deleteViaTree: (File) -> Boolean): AdvancedResult =
        unhideWith(path, treeRelativePath, deleteViaTree)

    private fun unhideWith(
        path: String,
        treeRelativePath: String?,
        deleteViaTree: ((File) -> Boolean)?,
    ): AdvancedResult {
        val dir = validated(path) ?: return INVALID
        val entries = registry.read()
        val entry = entries.firstOrNull { it.absolutePath == dir.path }
            ?: return AdvancedResult.Failed("Not hidden by this app")
        val marker = File(dir, NOMEDIA)
        if (marker.exists() && looksLikeOurs(marker, entry)) {
            if (deleteViaTree != null) {
                val root = rootOf(dir) ?: return INVALID
                if (treeRelativePath?.trim('/') != dir.relativeTo(root).path) {
                    return AdvancedResult.Failed("Granted folder does not match")
                }
            }
            val removed = try {
                when {
                    deleteViaTree != null -> deleteViaTree(marker)
                    isGranted() -> marker.delete()
                    else -> deleteOwnedMarker(marker)
                }
            } catch (_: SecurityException) {
                false
            }
            if (!removed) {
                return if (deleteViaTree == null && !isGranted()) {
                    AdvancedResult.NeedsTreeGrant(dir.path)
                } else {
                    AdvancedResult.Failed("Could not delete .nomedia")
                }
            }
        }
        // A missing or foreign marker means the hide is already resolved: drop the entry, touch nothing.
        return try {
            registry.write(entries - entry)
            scan(listOf(dir.path))
            AdvancedResult.Done
        } catch (e: Exception) {
            AdvancedResult.Failed(e.message ?: "Could not update registry")
        }
    }

    /** Entries still hidden after trying to undo everything; stale entries count as resolved. */
    fun unhideAll(): List<HiddenEntry> =
        registry.read().filter { unhide(it.absolutePath) != AdvancedResult.Done }

    private fun looksLikeOurs(marker: File, entry: HiddenEntry): Boolean =
        marker.isFile && marker.length() == 0L && marker.lastModified() + MTIME_TOLERANCE_MILLIS >= entry.createdAtMillis

    fun renameInPlace(source: String, target: String): AdvancedResult {
        val outcome = renameOne(source, target)
        if (outcome !is Moved) return outcome.asFailure()
        scan(listOf(outcome.from.path, outcome.to.path))
        return AdvancedResult.Done
    }

    /** One scan for the whole batch. Stops at the first lost permission and reports the rest as NotGranted. */
    fun moveAll(moves: List<Pair<String, String>>): List<AdvancedResult> {
        val results = ArrayList<AdvancedResult>(moves.size)
        val scanPaths = LinkedHashSet<String>()
        for ((source, target) in moves) {
            val outcome = renameOne(source, target)
            if (outcome is Moved) {
                scanPaths += outcome.from.path
                scanPaths += outcome.to.path
                results += AdvancedResult.Done
            } else {
                results += outcome.asFailure()
            }
        }
        if (scanPaths.isNotEmpty()) scan(scanPaths.toList())
        return results
    }

    private sealed interface Outcome
    private class Moved(val from: File, val to: File) : Outcome
    private class Refused(val result: AdvancedResult) : Outcome

    private fun Outcome.asFailure(): AdvancedResult = (this as Refused).result

    /** Renames one file without scanning. */
    private fun renameOne(source: String, target: String): Outcome {
        if (!isGranted()) return Refused(AdvancedResult.NotGranted)
        val from = validated(source) ?: return Refused(INVALID)
        val to = validated(target) ?: return Refused(INVALID)
        if (from.name.startsWith(PENDING_PREFIX) || from.name.startsWith(TRASHED_PREFIX)) {
            return Refused(AdvancedResult.Failed("Source is a pending or trashed item"))
        }
        if (!from.isFile) return Refused(AdvancedResult.Failed("Source is not a file"))
        if (to.exists()) return Refused(AdvancedResult.Failed("Target exists"))
        val fromRoot = rootOf(from)
        if (fromRoot == null || fromRoot != rootOf(to)) return Refused(AdvancedResult.Failed("Cross-volume move"))
        val parent = to.parentFile ?: return Refused(INVALID)
        return try {
            if (!parent.isDirectory && !parent.mkdirs()) {
                return Refused(lostOr("Could not create target folder"))
            }
            if (!renameFile(from, to)) Refused(lostOr("Rename failed")) else Moved(from, to)
        } catch (e: SecurityException) {
            Refused(AdvancedResult.NotGranted)
        } catch (e: IOException) {
            Refused(if (e.message?.contains("EACCES") == true) AdvancedResult.NotGranted else AdvancedResult.Failed("Rename failed"))
        }
    }

    /** A failure caused by a revoked permission is NotGranted, not Failed. */
    private fun lostOr(reason: String): AdvancedResult =
        if (isGranted()) AdvancedResult.Failed(reason) else AdvancedResult.NotGranted

    fun createDirectory(parent: String, name: String): AdvancedResult {
        val parentDir = validated(parent, allowRoot = true) ?: return INVALID
        if (!parentDir.isDirectory) return AdvancedResult.Failed("Not a directory")
        if (!validName(name)) return AdvancedResult.Failed("Invalid folder name")
        val dir = validated(File(parentDir, name).path) ?: return INVALID
        if (dir.parentFile != parentDir) return INVALID
        if (dir.exists()) return AdvancedResult.Failed("Target exists")
        return try {
            if (dir.mkdir()) AdvancedResult.Done else lostOr("Could not create folder")
        } catch (_: SecurityException) {
            AdvancedResult.NotGranted
        }
    }

    fun renameDirectory(path: String, newName: String): AdvancedResult {
        val from = validated(path) ?: return INVALID
        if (!validName(newName)) return AdvancedResult.Failed("Invalid folder name")
        val parent = from.parentFile ?: return INVALID
        return moveDir(from, File(parent, newName))
    }

    fun moveDirectory(path: String, newParent: String): AdvancedResult {
        val from = validated(path) ?: return INVALID
        val parentDir = validated(newParent, allowRoot = true) ?: return INVALID
        if (!parentDir.isDirectory) return AdvancedResult.Failed("Not a directory")
        if (under(parentDir, from)) return AdvancedResult.Failed("Cannot move a folder into itself")
        return moveDir(from, File(parentDir, from.name))
    }

    private fun moveDir(from: File, rawTo: File): AdvancedResult {
        if (!isGranted()) return AdvancedResult.NotGranted
        val to = validated(rawTo.path) ?: return INVALID
        if (!from.isDirectory) return AdvancedResult.Failed("Not a directory")
        if (to.exists()) return AdvancedResult.Failed("Target exists")
        val fromRoot = rootOf(from)
        if (fromRoot == null || fromRoot != rootOf(to)) return AdvancedResult.Failed("Cross-volume move")
        if (under(to, from)) return AdvancedResult.Failed("Cannot move a folder into itself")
        val entries = registry.read()
        val rewritten = entries.map { entry ->
            if (under(File(entry.absolutePath), from)) {
                entry.copy(absolutePath = to.path + entry.absolutePath.removePrefix(from.path))
            } else {
                entry
            }
        }
        val changed = rewritten != entries
        // Registry first: a crash after the rename must not leave entries pointing at the old path.
        if (changed) {
            try {
                registry.write(rewritten)
            } catch (e: Exception) {
                return AdvancedResult.Failed(e.message ?: "Could not update registry")
            }
        }
        val renamed = try {
            renameFile(from, to)
        } catch (_: SecurityException) {
            false
        }
        if (!renamed) {
            if (changed) runCatching { registry.write(entries) }
            return lostOr("Rename failed")
        }
        scan(listOf(from.path, to.path))
        return AdvancedResult.Done
    }

    fun deleteEmptyDirectory(path: String): AdvancedResult {
        val dir = validated(path) ?: return INVALID
        if (!dir.isDirectory) return AdvancedResult.Failed("Not a directory")
        if (dir.list()?.isEmpty() != true) return AdvancedResult.Failed("Directory not empty")
        if (!dir.delete()) return AdvancedResult.Failed("Delete failed")
        scan(listOf(dir.path))
        return AdvancedResult.Done
    }

    /** Immediate sub-folders, sorted by name; Android/data and Android/obb are never listed. */
    fun listDirectories(path: String): List<DirectoryEntry> {
        val dir = validated(path, allowRoot = true) ?: return emptyList()
        val children = dir.listFiles { child -> child.isDirectory } ?: return emptyList()
        return children
            .filter { validated(it.path) != null }
            .sortedBy { it.name.lowercase() }
            .map { child ->
                DirectoryEntry(
                    name = child.name,
                    absolutePath = child.path,
                    isEmpty = child.list()?.isEmpty() == true,
                    hasNoMedia = File(child, NOMEDIA).exists(),
                    isDotPrefixed = child.name.startsWith("."),
                )
            }
    }

    private fun validName(name: String): Boolean =
        name.isNotBlank() && name == name.trim() && name != "." && name != ".." &&
            name.none { it == '/' || it == '\u0000' } && name.length <= MAX_NAME_LENGTH

    private fun rootOf(file: File): File? = volumeRoots().map { canonical(it) }.firstOrNull { under(file, it) }

    /**
     * Absolute, canonical (symlinks resolved), inside a mounted volume, outside Android/data and
     * Android/obb. The volume root itself is rejected unless [allowRoot].
     */
    private fun validated(path: String, allowRoot: Boolean = false): File? {
        val raw = File(path)
        if (!raw.isAbsolute) return null
        val file = canonical(raw)
        val root = rootOf(file) ?: return null
        if (file == root) return if (allowRoot) file else null
        val relative = file.relativeTo(root).path
        val top = relative.split(File.separatorChar)
        if (top.size >= 2 && top[0] == "Android" && (top[1] == "data" || top[1] == "obb")) return null
        return file
    }

    private fun canonical(file: File): File = runCatching { file.canonicalFile }.getOrDefault(file.absoluteFile)

    private fun under(file: File, root: File): Boolean =
        file.path == root.path || file.path.startsWith(root.path + File.separator)

    companion object {
        const val NOMEDIA = ".nomedia"
        const val ALREADY_HIDDEN_BY_OTHERS = com.librestatic.lightforge.core.mediastore.ALREADY_HIDDEN_BY_OTHERS
        const val PENDING_PREFIX = ".pending-"
        const val TRASHED_PREFIX = ".trashed-"

        /** Filesystems store mtime with up to 2 s granularity (FAT); the marker may read slightly older. */
        const val MTIME_TOLERANCE_MILLIS = 2_000L
        private const val MAX_NAME_LENGTH = 255
        private val INVALID = AdvancedResult.Failed("Path is not allowed")
    }
}
