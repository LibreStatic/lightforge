package com.librestatic.lightforge.core.storageadvanced

import com.librestatic.lightforge.core.mediastore.AdvancedResult
import com.librestatic.lightforge.core.mediastore.DirectoryEntry
import com.librestatic.lightforge.core.mediastore.HiddenEntry
import com.librestatic.lightforge.core.mediastore.HiddenKind
import java.io.File
import java.io.IOException
import java.security.MessageDigest

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
        val dir = validatedFolder(path) ?: return INVALID
        if (!dir.isDirectory) return AdvancedResult.Failed("Not a directory")
        // MediaProvider deletes a .nomedia in a standard top-level folder or a volume root, so hiding there does nothing.
        if (rootOf(dir) == dir || isStandardFolder(dir)) return PROTECTED
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
            if (!marker.exists()) {
                // The platform removed the marker: nothing is hidden, so nothing may be recorded.
                registry.write(entries.filterNot { it.absolutePath == canonical })
                return AdvancedResult.Failed("The system did not keep the .nomedia")
            }
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
    fun unhide(path: String): AdvancedResult = unhideWith(path, treeDocumentId = null, deleteViaTree = null)

    /**
     * [unhide] through a SAF tree grant whose full document id (`primary:Pics/Sub` or
     * `1234-5678:Pics/Sub`) is [treeDocumentId]; both the volume and the path must match [path].
     */
    fun unhideWithTree(path: String, treeDocumentId: String, deleteViaTree: (File) -> Boolean): AdvancedResult =
        unhideWith(path, treeDocumentId, deleteViaTree)

    private fun unhideWith(
        path: String,
        treeDocumentId: String?,
        deleteViaTree: ((File) -> Boolean)?,
    ): AdvancedResult {
        val dir = validated(path) ?: return INVALID
        val entries = registry.read()
        val entry = entries.firstOrNull { it.absolutePath == dir.path }
            ?: return AdvancedResult.Failed("Not hidden by this app")
        if (entry.kind == HiddenKind.DotPrefix) {
            // A dot prefix is undone by renaming; a tree grant cannot do that.
            return if (deleteViaTree != null) AdvancedResult.Failed("Needs All files access") else undoDotPrefix(dir, entry, entries)
        }
        val marker = File(dir, NOMEDIA)
        if (marker.exists() && looksLikeOurs(marker, entry)) {
            if (deleteViaTree != null) {
                val root = rootOf(dir) ?: return INVALID
                val volumeName = volumes().firstOrNull { canonical(it.root) == root }?.name
                val treeVolume = treeDocumentId?.substringBefore(':', "")
                val treePath = treeDocumentId?.substringAfter(':', "")?.trim('/')
                if (volumeName == null || treeVolume.isNullOrEmpty() || !sameVolume(treeVolume, volumeName) ||
                    treePath != dir.relativeTo(root).path
                ) {
                    return AdvancedResult.Failed("Granted folder does not match")
                }
            }
            val removed = try {
                when {
                    deleteViaTree != null -> deleteViaTree(marker) && !marker.exists()
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

    /** One scan for the whole batch; each copy is verified byte for byte (SHA-256) before it is renamed into place. */
    fun copyAll(copies: List<Pair<String, String>>): List<AdvancedResult> {
        val results = ArrayList<AdvancedResult>(copies.size)
        val scanPaths = LinkedHashSet<String>()
        for ((source, target) in copies) {
            val outcome = copyOne(source, target)
            if (outcome is Moved) scanPaths += outcome.to.path
            results += if (outcome is Moved) AdvancedResult.Done else outcome.asFailure()
        }
        if (scanPaths.isNotEmpty()) scan(scanPaths.toList())
        return results
    }

    private fun copyOne(source: String, target: String): Outcome {
        if (!isGranted()) return Refused(AdvancedResult.NotGranted)
        val from = validated(source) ?: return Refused(INVALID)
        // Android/media/<package> is deleted with its app, so files never land anywhere in the Android tree.
        val to = validatedFolder(target) ?: return Refused(INVALID)
        if (from.name.startsWith(PENDING_PREFIX) || from.name.startsWith(TRASHED_PREFIX)) {
            return Refused(AdvancedResult.Failed("Source is a pending or trashed item"))
        }
        if (!from.isFile) return Refused(AdvancedResult.Failed("Source is not a file"))
        if (to.exists()) return Refused(AdvancedResult.Failed("Target exists"))
        val parent = to.parentFile ?: return Refused(INVALID)
        val partial = File(parent, to.name + PARTIAL_SUFFIX)
        return try {
            if (!parent.isDirectory && !parent.mkdirs()) return Refused(lostOr("Could not create target folder"))
            val expected = copyWithDigest(from, partial)
            when {
                !digestOf(partial).contentEquals(expected) -> Refused(AdvancedResult.Failed("Copy does not match the original"))
                to.exists() -> Refused(AdvancedResult.Failed("Target exists"))
                else -> {
                    partial.setLastModified(from.lastModified())
                    if (renameFile(partial, to)) Moved(from, to) else Refused(lostOr("Rename failed"))
                }
            }
        } catch (e: SecurityException) {
            Refused(AdvancedResult.NotGranted)
        } catch (e: IOException) {
            Refused(if (e.message?.contains("EACCES") == true) AdvancedResult.NotGranted else AdvancedResult.Failed("Copy failed"))
        } finally {
            if (partial.exists()) partial.delete()
        }
    }

    private fun copyWithDigest(from: File, into: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        from.inputStream().use { input ->
            into.outputStream().use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
        }
        return digest.digest()
    }

    private fun digestOf(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    private sealed interface Outcome
    private class Moved(val from: File, val to: File) : Outcome
    private class Refused(val result: AdvancedResult) : Outcome

    private fun Outcome.asFailure(): AdvancedResult = (this as Refused).result

    /** Renames one file without scanning. */
    private fun renameOne(source: String, target: String): Outcome {
        if (!isGranted()) return Refused(AdvancedResult.NotGranted)
        val from = validated(source) ?: return Refused(INVALID)
        // Android/media/<package> is deleted with its app, so files never land anywhere in the Android tree.
        val to = validatedFolder(target) ?: return Refused(INVALID)
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
        val parentDir = validatedFolder(parent, allowRoot = true) ?: return INVALID
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
        val from = validatedFolder(path) ?: return INVALID
        if (isStandardFolder(from)) return PROTECTED
        if (!validName(newName)) return AdvancedResult.Failed("Invalid folder name")
        val parent = from.parentFile ?: return INVALID
        return moveDir(from, File(parent, newName))
    }

    fun moveDirectory(path: String, newParent: String): AdvancedResult {
        val from = validatedFolder(path) ?: return INVALID
        if (isStandardFolder(from)) return PROTECTED
        val parentDir = validatedFolder(newParent, allowRoot = true) ?: return INVALID
        if (!parentDir.isDirectory) return AdvancedResult.Failed("Not a directory")
        if (under(parentDir, from)) return AdvancedResult.Failed("Cannot move a folder into itself")
        return moveDir(from, File(parentDir, from.name))
    }

    private fun moveDir(from: File, rawTo: File): AdvancedResult {
        if (!isGranted()) return AdvancedResult.NotGranted
        val to = validatedFolder(rawTo.path) ?: return INVALID
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

    /** Whether [path] exists inside a mounted volume; false for anything the engine would refuse. */
    fun exists(path: String): Boolean = validated(path)?.exists() == true

    /**
     * Hides a folder or a media file by giving it a dot-prefixed name (`Trip` becomes `.Trip`), which
     * MediaStore treats like a `.nomedia`. The new path is recorded so [unhide] can undo it; a name that
     * already starts with a dot, an existing target, the Android tree and standard folders are refused.
     */
    fun hideWithDotPrefix(path: String): AdvancedResult {
        val source = validatedFolder(path) ?: return INVALID
        if (!source.exists()) return AdvancedResult.Failed("Not found")
        if (source.isDirectory && isStandardFolder(source)) return PROTECTED
        if (source.name.startsWith(".")) return AdvancedResult.Failed("Already hidden")
        val parent = source.parentFile ?: return INVALID
        val target = File(parent, "." + source.name)
        if (!validName(target.name)) return AdvancedResult.Failed("Invalid name")
        if (target.exists()) return AdvancedResult.Failed("Target exists")
        val createdAt = clock()
        val result = if (source.isDirectory) {
            moveDir(source, target)
        } else {
            when (val outcome = renameOne(source.path, target.path)) {
                is Moved -> { scan(listOf(outcome.from.path, outcome.to.path)); AdvancedResult.Done }
                else -> outcome.asFailure()
            }
        }
        if (result != AdvancedResult.Done) return result
        return try {
            val entries = registry.read().filterNot { it.absolutePath == target.path }
            registry.write(entries + HiddenEntry(target.path, HiddenKind.DotPrefix, createdAt))
            AdvancedResult.Done
        } catch (e: Exception) {
            // Without the record the hide could not be undone from the app: put the name back.
            runCatching { renameFile(target, source) }
            scan(listOf(source.path, target.path))
            AdvancedResult.Failed(e.message ?: "Could not record hide")
        }
    }

    private fun undoDotPrefix(hidden: File, entry: HiddenEntry, entries: List<HiddenEntry>): AdvancedResult {
        val forget = { registry.write(entries - entry) }
        // Renamed or deleted behind our back: the hide is already resolved.
        if (!hidden.exists() || !hidden.name.startsWith(".") || hidden.name.length < 2) {
            return try { forget(); AdvancedResult.Done } catch (e: Exception) { AdvancedResult.Failed(e.message ?: "Could not update registry") }
        }
        if (!isGranted()) return AdvancedResult.NotGranted
        val parent = hidden.parentFile ?: return INVALID
        val visible = File(parent, hidden.name.substring(1))
        if (visible.exists()) return AdvancedResult.Failed("Target exists")
        val result = if (hidden.isDirectory) {
            moveDir(hidden, visible)
        } else {
            when (val outcome = renameOne(hidden.path, visible.path)) {
                is Moved -> { scan(listOf(outcome.from.path, outcome.to.path)); AdvancedResult.Done }
                else -> outcome.asFailure()
            }
        }
        if (result != AdvancedResult.Done) return result
        return try {
            // moveDir rewrote the record of a folder to its new path; drop whichever form is there.
            registry.write(registry.read().filterNot { it.absolutePath == hidden.path || it.absolutePath == visible.path })
            AdvancedResult.Done
        } catch (e: Exception) {
            AdvancedResult.Failed(e.message ?: "Could not update registry")
        }
    }

    /** Deletes files, one result each and one scan for the batch. A file that is already gone counts as deleted. */
    fun deleteFiles(paths: List<String>): List<AdvancedResult> {
        val scanPaths = LinkedHashSet<String>()
        val results = paths.map { path ->
            if (!isGranted()) return@map AdvancedResult.NotGranted
            val file = validated(path) ?: return@map INVALID
            if (!file.exists()) { scanPaths += file.path; return@map AdvancedResult.Done }
            if (!file.isFile) return@map AdvancedResult.Failed("Not a file")
            try {
                if (file.delete()) { scanPaths += file.path; AdvancedResult.Done } else lostOr("Delete failed")
            } catch (_: SecurityException) {
                AdvancedResult.NotGranted
            }
        }
        if (scanPaths.isNotEmpty()) scan(scanPaths.toList())
        return results
    }

    /**
     * Copies the folder into [newParent] as [newName] (default: its own name), file by file with the
     * same byte check as [copyAll]. The copy is made under a name that did not exist, so a failure removes
     * everything it created. Symbolic links, pending or trashed files and the markers this app made are
     * not copied: the copy of a folder this app hid is visible.
     */
    fun copyDirectory(path: String, newParent: String, newName: String? = null): AdvancedResult {
        if (!isGranted()) return AdvancedResult.NotGranted
        val from = validated(path) ?: return INVALID
        if (!from.isDirectory) return AdvancedResult.Failed("Not a directory")
        val parent = validatedFolder(newParent, allowRoot = true) ?: return INVALID
        if (!parent.isDirectory) return AdvancedResult.Failed("Not a directory")
        val name = newName ?: from.name
        if (!validName(name)) return AdvancedResult.Failed("Invalid folder name")
        val to = validatedFolder(File(parent, name).path) ?: return INVALID
        if (to.exists()) return AdvancedResult.Failed("Target exists")
        if (under(to, from)) return AdvancedResult.Failed("Cannot copy a folder into itself")
        val ownMarkers = registry.read().filter { it.kind == HiddenKind.NoMediaFile }
            .mapTo(HashSet()) { File(it.absolutePath, NOMEDIA).path }
        val scanPaths = ArrayList<String>()
        return try {
            copyTree(from, to, ownMarkers, scanPaths)
            scan(scanPaths + to.path)
            AdvancedResult.Done
        } catch (_: SecurityException) {
            to.deleteTree(ArrayList())
            AdvancedResult.NotGranted
        } catch (e: IOException) {
            to.deleteTree(ArrayList())
            if (e.message?.contains("EACCES") == true) AdvancedResult.NotGranted else AdvancedResult.Failed("Copy failed")
        }
    }

    private fun copyTree(from: File, to: File, skip: Set<String>, scanPaths: MutableList<String>) {
        if (!to.isDirectory && !to.mkdirs()) throw IOException("Could not create ${to.name}")
        for (child in from.listFiles().orEmpty()) {
            if (java.nio.file.Files.isSymbolicLink(child.toPath())) continue
            val target = File(to, child.name)
            when {
                child.isDirectory -> copyTree(child, target, skip, scanPaths)
                !child.isFile || child.path in skip -> Unit
                child.name.startsWith(PENDING_PREFIX) || child.name.startsWith(TRASHED_PREFIX) -> Unit
                else -> when (val outcome = copyOne(child.path, target.path)) {
                    is Moved -> scanPaths += outcome.to.path
                    is Refused -> throw IOException(if (outcome.result == AdvancedResult.NotGranted) "EACCES" else "Copy failed")
                }
            }
        }
    }

    /**
     * Deletes the folder and everything in it, never following a symbolic link. Standard folders, the
     * volume root and the Android tree are refused. Hide records inside it are dropped.
     */
    fun deleteDirectory(path: String): AdvancedResult {
        val dir = validatedFolder(path) ?: return INVALID
        if (isStandardFolder(dir)) return PROTECTED
        if (!dir.isDirectory) return AdvancedResult.Failed("Not a directory")
        if (!isGranted()) return AdvancedResult.NotGranted
        val removed = ArrayList<String>()
        val ok = try {
            dir.deleteTree(removed)
        } catch (_: SecurityException) {
            false
        }
        // What was deleted is gone even when something else refused, so the index hears about it.
        scan(removed + dir.path)
        if (!ok) return lostOr("Delete failed")
        runCatching { registry.write(registry.read().filterNot { under(File(it.absolutePath), dir) }) }
        return AdvancedResult.Done
    }

    private fun File.deleteTree(deleted: MutableList<String>): Boolean {
        val isLink = java.nio.file.Files.isSymbolicLink(toPath())
        var clean = true
        if (!isLink && isDirectory) {
            for (child in listFiles().orEmpty()) clean = child.deleteTree(deleted) && clean
        }
        if (!clean) return false
        val gone = delete()
        if (gone) deleted += path
        return gone
    }

    fun deleteEmptyDirectory(path: String): AdvancedResult {
        val dir = validatedFolder(path) ?: return INVALID
        if (isStandardFolder(dir)) return PROTECTED
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
                    // A lone .nomedia marker (a folder hidden by this app) still counts as an empty folder.
                    isEmpty = child.list()?.all { it == NOMEDIA } == true,
                    hasNoMedia = File(child, NOMEDIA).exists(),
                    isDotPrefixed = child.name.startsWith("."),
                )
            }
    }

    private fun validName(name: String): Boolean =
        name.isNotBlank() && name == name.trim() && name != "." && name != ".." &&
            name.none { it == '/' || it == '\u0000' } && name.length <= MAX_NAME_LENGTH

    // SAF names the emulated volume "primary"; MediaStore calls it external_primary. Cards use the UUID.
    private fun sameVolume(documentVolume: String, mediaStoreName: String): Boolean =
        if (documentVolume.equals("primary", ignoreCase = true)) mediaStoreName == "external_primary"
        else documentVolume.equals(mediaStoreName, ignoreCase = true)

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
        if (top.size >= 2 && top[0].equals("Android", ignoreCase = true) &&
            (top[1].equals("data", ignoreCase = true) || top[1].equals("obb", ignoreCase = true))
        ) {
            return null
        }
        return file
    }

    /**
     * [validated] for operations that change a folder (hide, create, rename, move, delete): the whole
     * Android tree is refused as well, because Android/media/<package> belongs to another app that
     * would recreate an empty folder and orphan the files left under the new name.
     */
    private fun validatedFolder(path: String, allowRoot: Boolean = false): File? {
        val file = validated(path, allowRoot) ?: return null
        val root = rootOf(file) ?: return null
        if (file == root) return file
        val first = file.relativeTo(root).path.split(File.separatorChar).first()
        return if (first.equals("Android", ignoreCase = true)) null else file
    }

    /** A standard top-level folder (DCIM, Pictures, Download...) that other apps and MediaStore rely on. */
    private fun isStandardFolder(dir: File): Boolean {
        val root = rootOf(dir) ?: return false
        val relative = dir.relativeTo(root).path
        return !relative.contains(File.separatorChar) && STANDARD_FOLDERS.any { it.equals(relative, ignoreCase = true) }
    }

    private fun canonical(file: File): File = runCatching { file.canonicalFile }.getOrDefault(file.absoluteFile)

    private fun under(file: File, root: File): Boolean =
        file.path == root.path || file.path.startsWith(root.path + File.separator)

    companion object {
        const val NOMEDIA = ".nomedia"
        const val ALREADY_HIDDEN_BY_OTHERS = com.librestatic.lightforge.core.mediastore.ALREADY_HIDDEN_BY_OTHERS
        const val PENDING_PREFIX = ".pending-"
        const val TRASHED_PREFIX = ".trashed-"
        private const val PARTIAL_SUFFIX = ".lightforge-copy.part"
        private const val COPY_BUFFER_BYTES = 128 * 1024

        /** Filesystems store mtime with up to 2 s granularity (FAT); the marker may read slightly older. */
        const val MTIME_TOLERANCE_MILLIS = 2_000L
        private const val MAX_NAME_LENGTH = 255
        private val INVALID = AdvancedResult.Failed("Path is not allowed")
        private val PROTECTED = AdvancedResult.Failed("Standard folders cannot be hidden, renamed, moved or deleted")
        private val STANDARD_FOLDERS = listOf(
            "DCIM", "Pictures", "Download", "Movies", "Music", "Podcasts", "Ringtones", "Alarms",
            "Notifications", "Documents", "Audiobooks", "Recordings", "Screenshots",
        )
    }
}
