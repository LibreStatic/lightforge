package com.librestatic.lightforge.feature.settings

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Only this feature's UUID-named session staging can be handed off without copying. */
internal object LocalBackupTaskSnapshots {
    fun manifestSha(manifest: BackupManifest): String =
        MessageDigest.getInstance("SHA-256").digest(manifest.encode()).joinToString("") {
            "%02x".format(it)
        }

    fun privateSource(context: Context, source: File): File {
        val canonical = source.canonicalFile
        // Android can expose the same private root through /data/data and /data/user/0.
        // Canonical containment is authoritative; reject a caller-supplied file symlink.
        require(canonical.isFile && !Files.isSymbolicLink(source.toPath()))
        require(
            listOf(context.filesDir.canonicalFile, context.cacheDir.canonicalFile).any {
                canonical.path.startsWith(it.path + File.separator)
            }
        ) {
            "Task snapshot must already be private"
        }
        return canonical
    }

    fun attachOwned(context: Context, source: File, destination: File): Boolean {
        val session = source.parentFile ?: return false
        val expectedRoot = File(context.cacheDir, "local-backup").canonicalFile
        if (
            session.parentFile?.canonicalFile != expectedRoot ||
                !LocalBackupTaskStore.validId(session.name) ||
                !source.name.endsWith(".lightforge.zip") ||
                !LocalBackupTaskStore.validId(source.name.removeSuffix(".lightforge.zip"))
        )
            return false
        // Atomic second directory entry: session cleanup can unlink its own name without deleting
        // the durable inode. Never link/move arbitrary caller paths or external originals.
        try {
            Files.createLink(destination.toPath(), source.toPath())
        } catch (error: java.io.IOException) {
            // Same private filesystem, only an explicitly owned staging name may be moved.
            if (!source.renameTo(destination)) throw error
        }
        return true
    }
}
