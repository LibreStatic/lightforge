package com.ugallery.feature.remotebackup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import org.json.JSONObject

/** Records prior permission ownership before taking a grant. Retained with task history. */
internal class RemoteBackupGrants(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val file =
        AtomicFile(File(context.applicationContext.filesDir, "remote-backup-grants.json"))

    fun retain(
        task: String,
        uri: Uri,
        flags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION,
        required: Boolean = false,
    ) =
        synchronized(lock) {
            val root =
                if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists())
                    file.openRead().use {
                        val bytes = RemoteBackupIO.readBounded(it, 16 * 1024 * 1024)
                        require(bytes.size <= 16 * 1024 * 1024)
                        JSONObject(bytes.toString(Charsets.UTF_8))
                    }
                else JSONObject()
            val held = resolver.persistedUriPermissions.singleOrNull { it.uri == uri }
            // Do not let Android evict unrelated persistable grants when the grant budget is full.
            if (held == null && resolver.persistedUriPermissions.size >= 480)
                throw SecurityException("Permission budget requires review")
            val key = uri.toString()
            val row =
                root.optJSONObject(key)
                    ?: JSONObject()
                        .put(
                            "priorRead",
                            resolver.persistedUriPermissions.any {
                                it.uri == uri && it.isReadPermission
                            },
                        )
                        .put("priorWrite", held?.isWritePermission == true)
                        .put("tasks", JSONObject())
            row.getJSONObject("tasks").put(task, flags)
            root.put(key, row)
            val bytes = root.toString().toByteArray()
            require(bytes.size <= 16 * 1024 * 1024)
            val out = file.startWrite()
            try {
                out.write(bytes)
                file.finishWrite(out)
            } catch (error: Throwable) {
                file.failWrite(out)
                throw error
            }
            // MediaStore sources use app media permission rather than a persistable SAF grant.
            try {
                resolver.takePersistableUriPermission(uri, flags)
            } catch (error: SecurityException) {
                if (required) throw error
                resolver.openInputStream(uri)?.use {}
                    ?: throw SecurityException("Source unavailable")
            }
        }

    companion object {
        private val lock = Any()
    }
}
