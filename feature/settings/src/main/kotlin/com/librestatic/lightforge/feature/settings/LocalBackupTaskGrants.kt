package com.librestatic.lightforge.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Write-ahead grant ownership: forgetting a task never releases pre-existing or shared grants. */
internal class LocalBackupTaskGrants(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val file =
        AtomicFile(File(context.applicationContext.filesDir, "local-backup-task-grants.json"))

    /**
     * [adopted] are flags another ledger handed over for this task: they count as owned here even
     * though the app already held them, so this ledger releases them exactly once.
     */
    fun retain(task: String, uri: Uri, flags: Int, required: Boolean = false, adopted: Int = 0) =
        synchronized(lock) {
            val root = load()
            val key = uri.toString()
            val rows = root.getJSONObject("grants")
            val existing = rows.optJSONObject(key)
            val prior = resolver.persistedUriPermissions.singleOrNull { it.uri == uri }
            val held =
                (if (prior?.isReadPermission == true) Intent.FLAG_GRANT_READ_URI_PERMISSION
                else 0) or
                    (if (prior?.isWritePermission == true) Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    else 0)
            val entry =
                existing
                    ?: JSONObject().put("protected", held).put("owned", 0).put("tasks", JSONArray())
            val ids = entry.getJSONArray("tasks")
            if ((0 until ids.length()).none { ids.getString(it) == task }) ids.put(task)
            adopt(entry, adopted)
            entry.put("owned", entry.getInt("owned") or (flags and entry.getInt("protected").inv()))
            rows.put(key, entry)
            save(root) // Precedes acquisition, including the process-death window before return.
            try {
                resolver.takePersistableUriPermission(uri, flags)
            } catch (error: SecurityException) {
                if (required) throw error
            }
        }

    fun release(task: String) =
        synchronized(lock) {
            val root = load()
            val rows = root.getJSONObject("grants")
            rows.keys().asSequence().toList().forEach { key ->
                val entry = rows.getJSONObject(key)
                val ids = entry.getJSONArray("tasks")
                val remaining =
                    (0 until ids.length()).map { ids.getString(it) }.filter { it != task }
                if (remaining.isEmpty()) {
                    val uri = Uri.parse(key)
                    val permission = resolver.persistedUriPermissions.singleOrNull { it.uri == uri }
                    val held =
                        (if (permission?.isReadPermission == true) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                            (if (permission?.isWritePermission == true) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                    val owned = entry.getInt("owned") and held
                    if (owned != 0) resolver.releasePersistableUriPermission(uri, owned)
                    rows.remove(key)
                } else entry.put("tasks", JSONArray(remaining))
            }
            save(root)
        }

    private fun load(): JSONObject {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists())
            return JSONObject().put("version", 1).put("grants", JSONObject())
        return file.openRead().use { input ->
            val buffer = ByteArray(65536)
            val output = java.io.ByteArrayOutputStream()
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 16 * 1024 * 1024)
                output.write(buffer, 0, count)
            }
            val bytes = output.toByteArray()
            JSONObject(bytes.toString(Charsets.UTF_8)).also { require(it.getInt("version") == 1) }
        }
    }

    private fun save(root: JSONObject) {
        val bytes = root.toString().toByteArray()
        require(bytes.size <= 16 * 1024 * 1024)
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    companion object {
        private val lock = Any()

        fun adopt(entry: JSONObject, adopted: Int) {
            if (adopted == 0) return
            entry.put("protected", entry.getInt("protected") and adopted.inv())
            entry.put("owned", entry.getInt("owned") or adopted)
        }
    }
}
