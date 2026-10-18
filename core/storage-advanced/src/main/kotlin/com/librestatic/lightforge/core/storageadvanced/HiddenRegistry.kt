package com.librestatic.lightforge.core.storageadvanced

import com.librestatic.lightforge.core.mediastore.HiddenEntry
import com.librestatic.lightforge.core.mediastore.HiddenKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * App-private record of the hides this app performed. Not thread-safe: callers serialize access.
 * A missing file is an empty registry; a corrupt file is kept as `<name>.corrupt` and treated as empty.
 */
internal class HiddenRegistry(private val file: File) {
    fun read(): List<HiddenEntry> {
        if (!file.isFile) return emptyList()
        return try {
            val array = JSONObject(file.readText()).getJSONArray(KEY_ENTRIES)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                HiddenEntry(
                    absolutePath = item.getString(KEY_PATH),
                    kind = HiddenKind.valueOf(item.getString(KEY_KIND)),
                    createdAtMillis = item.getLong(KEY_CREATED),
                )
            }
        } catch (_: Exception) {
            runCatching { file.copyTo(File(file.parentFile, file.name + ".corrupt"), overwrite = true) }
            emptyList()
        }
    }

    fun write(entries: List<HiddenEntry>) {
        val parent = requireNotNull(file.absoluteFile.parentFile)
        parent.mkdirs()
        val array = JSONArray()
        entries.forEach {
            array.put(
                JSONObject()
                    .put(KEY_PATH, it.absolutePath)
                    .put(KEY_KIND, it.kind.name)
                    .put(KEY_CREATED, it.createdAtMillis),
            )
        }
        val temp = File(parent, file.name + ".tmp")
        temp.writeText(JSONObject().put(KEY_VERSION, 1).put(KEY_ENTRIES, array).toString())
        if (!temp.renameTo(file)) {
            temp.delete()
            throw java.io.IOException("Could not replace ${file.name}")
        }
    }

    private companion object {
        const val KEY_VERSION = "version"
        const val KEY_ENTRIES = "entries"
        const val KEY_PATH = "path"
        const val KEY_KIND = "kind"
        const val KEY_CREATED = "createdAtMillis"
    }
}
