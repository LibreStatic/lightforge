package com.ugallery.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import org.json.JSONObject

/**
 * Write-ahead grant ownership for one feature's tasks, stored in [fileName]. Each URI row records
 * whether the app held the grant before its first task (priorRead/priorWrite) and which tasks use
 * it; releasing a task gives back only what the feature itself took, once no task references it.
 * When [liveTasks] is set, a full grant budget first releases grants of tasks no longer live.
 */
class PersistableUriGrants(
    context: Context,
    fileName: String,
    private val liveTasks: (() -> Set<String>)? = null,
) {
    private val resolver = context.applicationContext.contentResolver
    private val file = AtomicFile(File(context.applicationContext.filesDir, fileName))

    fun retain(
        task: String,
        uri: Uri,
        flags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION,
        required: Boolean = false,
    ) =
        synchronized(lock) {
            val root = load()
            val held = resolver.persistedUriPermissions.singleOrNull { it.uri == uri }
            // Do not let Android evict unrelated persistable grants when the grant budget is full:
            // first give back grants whose tasks have finished, then stop only if still full.
            if (liveTasks != null && held == null && resolver.persistedUriPermissions.size >= 480) {
                val active = liveTasks.invoke()
                releaseAll(forget(root) { it == task || it in active })
                save(root)
                if (resolver.persistedUriPermissions.size >= 480)
                    throw SecurityException("Permission budget requires review")
            }
            val key = uri.toString()
            val row =
                root.optJSONObject(key)
                    ?: JSONObject()
                        .put("priorRead", held?.isReadPermission == true)
                        .put("priorWrite", held?.isWritePermission == true)
                        .put("tasks", JSONObject())
            row.getJSONObject("tasks").put(task, flags)
            root.put(key, row)
            save(root) // Precedes acquisition, including the process-death window before return.
            // MediaStore sources use app media permission rather than a persistable SAF grant.
            try {
                resolver.takePersistableUriPermission(uri, flags)
            } catch (error: SecurityException) {
                if (required) throw error
                resolver.openInputStream(uri)?.use {}
                    ?: throw SecurityException("Source unavailable")
            }
        }

    /** Gives back the grants [task] took. Pre-existing and still shared grants stay held. */
    fun release(task: String) =
        synchronized(lock) {
            val root = load()
            releaseAll(forget(root) { it != task })
            save(root)
        }

    private fun releaseAll(released: Map<String, Int>) =
        released.forEach { (key, owned) ->
            val uri = Uri.parse(key)
            val permission = resolver.persistedUriPermissions.singleOrNull { it.uri == uri }
            val held =
                (if (permission?.isReadPermission == true) Intent.FLAG_GRANT_READ_URI_PERMISSION
                else 0) or
                    (if (permission?.isWritePermission == true)
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    else 0)
            if (owned and held != 0)
                try {
                    resolver.releasePersistableUriPermission(uri, owned and held)
                } catch (_: SecurityException) {
                    // Already revoked by the user or the provider.
                }
        }

    private fun load(): JSONObject {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists())
            return JSONObject()
        return file.openRead().use { input ->
            val buffer = ByteArray(65536)
            val output = java.io.ByteArrayOutputStream()
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MaxBytes)
                output.write(buffer, 0, count)
            }
            JSONObject(output.toByteArray().toString(Charsets.UTF_8))
        }
    }

    private fun save(root: JSONObject) {
        val bytes = root.toString().toByteArray()
        require(bytes.size <= MaxBytes)
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
        private const val MaxBytes = 16 * 1024 * 1024

        /**
         * Drops every task that is not [keep] from [root]. Returns the URIs no remaining task
         * references, with the flags their tasks took beyond what the app held before.
         */
        fun forget(root: JSONObject, keep: (String) -> Boolean): Map<String, Int> {
            val released = mutableMapOf<String, Int>()
            root.keys().asSequence().toList().forEach { key ->
                val row = root.getJSONObject(key)
                val tasks = row.getJSONObject("tasks")
                var owned = row.optInt("owned", 0)
                tasks.keys().asSequence().toList().forEach { task ->
                    owned = owned or tasks.getInt(task)
                    if (!keep(task)) tasks.remove(task)
                }
                val prior =
                    (if (row.optBoolean("priorRead")) Intent.FLAG_GRANT_READ_URI_PERMISSION
                    else 0) or
                        (if (row.optBoolean("priorWrite")) Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        else 0)
                // Flags of tasks forgotten earlier still belong to this row until it empties.
                row.put("owned", owned)
                if (tasks.length() == 0) {
                    root.remove(key)
                    val own = owned and prior.inv()
                    if (own != 0) released[key] = own
                }
            }
            return released
        }
    }
}
