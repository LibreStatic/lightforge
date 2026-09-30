package com.librestatic.lightforge.feature.settings

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

/**
 * Only the UUID belongs in SavedState; selected SAF URIs and staged bytes remain in private cache.
 */
class LocalBackupSession(private val root: File, val id: String) {
    val directory: File

    init {
        require(UUID.fromString(id).toString() == id)
        root.mkdirs()
        synchronized(openedRoots) {
            // Once at process startup: no operation from this process can already own this root.
            if (openedRoots.add(root.canonicalPath))
                pruneStale(root, id, System.currentTimeMillis())
        }
        directory = File(root, id).apply { mkdirs() }
        directory.setLastModified(System.currentTimeMillis())
    }

    fun saveSelection(values: List<String>) {
        require(
            values.size <= BackupManifest.MAX_ENTRIES &&
                values.sumOf { it.length.toLong() } <= 4 * 1024 * 1024 &&
                values.all { it.length <= 8192 && it.startsWith("content://") }
        )
        val temporary = File(directory, "selection.tmp")
        try {
            DataOutputStream(temporary.outputStream().buffered()).use { output ->
                output.writeInt(1)
                output.writeInt(values.size)
                values.forEach(output::writeUTF)
            }
            if (!temporary.renameTo(File(directory, "selection.bin")))
                throw IOException("Selection persistence failed")
        } finally {
            temporary.delete()
        }
    }

    fun readSelection(): List<String> =
        try {
            DataInputStream(File(directory, "selection.bin").inputStream().buffered()).use { input
                ->
                if (input.readInt() != 1) throw IOException("Unknown selection version")
                val count = input.readInt()
                if (count !in 0..BackupManifest.MAX_ENTRIES)
                    throw IOException("Invalid selection count")
                var chars = 0L
                List(count) {
                        input.readUTF().also {
                            chars += it.length
                            if (
                                chars > 4 * 1024 * 1024 ||
                                    it.length > 8192 ||
                                    !it.startsWith("content://")
                            )
                                throw IOException("Invalid selection")
                        }
                    }
                    .also { if (input.read() != -1) throw IOException("Trailing selection data") }
            }
        } catch (_: IOException) {
            emptyList()
        }

    fun close() {
        directory.deleteRecursively()
    }

    companion object {
        private val openedRoots = mutableSetOf<String>()
        private const val STALE_MILLIS = 24L * 60 * 60 * 1000

        internal fun pruneStale(root: File, retained: String, now: Long) {
            root.listFiles()?.forEach { file ->
                val validSession =
                    runCatching { UUID.fromString(file.name).toString() == file.name }
                        .getOrDefault(false)
                if (
                    validSession &&
                        file.name != retained &&
                        now - file.lastModified() > STALE_MILLIS &&
                        !Files.isSymbolicLink(file.toPath())
                )
                    file.deleteRecursively()
            }
        }
    }
}
