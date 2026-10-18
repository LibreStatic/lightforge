package com.librestatic.lightforge.core.storageadvanced

import com.librestatic.lightforge.core.mediastore.AdvancedResult
import com.librestatic.lightforge.core.mediastore.HiddenEntry
import com.librestatic.lightforge.core.mediastore.HiddenKind
import java.io.File

/**
 * Blocking file logic behind [FileAdvancedStorage]; Android services are injected so it is unit-testable.
 * Callers must serialize access (the facade holds a mutex) and run it off the main thread.
 */
internal class StorageEngine(
    private val registry: HiddenRegistry,
    private val volumeRoots: () -> List<File>,
    private val scan: (List<String>) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun hiddenByApp(): List<HiddenEntry> = registry.read()

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
        if (!runCatching { marker.createNewFile() }.getOrDefault(false)) {
            return AdvancedResult.Failed("Could not create .nomedia")
        }
        return try {
            // A recorded marker can vanish behind our back; re-hiding replaces the stale entry.
            registry.write(
                entries.filterNot { it.absolutePath == canonical } +
                    HiddenEntry(canonical, HiddenKind.NoMediaFile, clock()),
            )
            scan(listOf(canonical, marker.path))
            AdvancedResult.Done
        } catch (e: Exception) {
            marker.delete()
            AdvancedResult.Failed(e.message ?: "Could not record hide")
        }
    }

    /** Removes the `.nomedia` only when this app recorded it. */
    fun unhide(path: String): AdvancedResult {
        val dir = validated(path) ?: return INVALID
        val entries = registry.read()
        val entry = entries.firstOrNull { it.absolutePath == dir.path }
            ?: return AdvancedResult.Failed("Not hidden by this app")
        val marker = File(dir, NOMEDIA)
        if (marker.exists() && !marker.delete()) return AdvancedResult.Failed("Could not delete .nomedia")
        return try {
            registry.write(entries - entry)
            scan(listOf(dir.path))
            AdvancedResult.Done
        } catch (e: Exception) {
            AdvancedResult.Failed(e.message ?: "Could not update registry")
        }
    }

    fun unhideAll(): List<HiddenEntry> =
        registry.read().filter { unhide(it.absolutePath) != AdvancedResult.Done }

    fun renameInPlace(source: String, target: String): AdvancedResult {
        val from = validated(source) ?: return INVALID
        val to = validated(target) ?: return INVALID
        if (!from.isFile) return AdvancedResult.Failed("Source is not a file")
        if (to.exists()) return AdvancedResult.Failed("Target exists")
        val fromRoot = rootOf(from)
        if (fromRoot == null || fromRoot != rootOf(to)) return AdvancedResult.Failed("Cross-volume move")
        val parent = to.parentFile ?: return INVALID
        if (!parent.isDirectory && !parent.mkdirs()) return AdvancedResult.Failed("Could not create target folder")
        if (!from.renameTo(to)) return AdvancedResult.Failed("Rename failed")
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

    private fun rootOf(file: File): File? = volumeRoots().map { canonical(it) }.firstOrNull { under(file, it) }

    /** Absolute, canonical, inside a mounted volume (not the root itself), outside Android/data and Android/obb. */
    private fun validated(path: String): File? {
        val raw = File(path)
        if (!raw.isAbsolute) return null
        val file = canonical(raw)
        val root = rootOf(file) ?: return null
        if (file == root) return null
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
        const val ALREADY_HIDDEN_BY_OTHERS = "already hidden by someone else"
        private val INVALID = AdvancedResult.Failed("Path is not allowed")
    }
}
