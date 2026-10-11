package com.librestatic.lightforge

import com.librestatic.lightforge.core.editing.image.grade.LabStats
import com.librestatic.lightforge.core.editing.image.grade.PhotoLut
import java.io.File
import java.util.UUID

/** A "My filters" entry: [id] is the `custom:<uuid>` look id. */
data class StoredPhotoLook(val id: String, val name: String, val createdAtMillis: Long)

/**
 * "My filters" on disk: `<uuid>.lut` (the baked [PhotoLut]) and `<uuid>.json` (name, creation time
 * and the reference photo's Lab statistics) inside [directory]. Blocking; call from an IO dispatcher.
 */
class PhotoCustomLookStore(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> UUID = UUID::randomUUID,
) {
    private val lutCache = HashMap<String, PhotoLut>()

    fun list(): List<StoredPhotoLook> {
        val files = directory.listFiles { file -> file.extension == "json" } ?: return emptyList()
        return files.mapNotNull { file ->
            val uuid = file.nameWithoutExtension
            if (!UuidPattern.matches(uuid) || !File(directory, "$uuid.lut").isFile) return@mapNotNull null
            val fields = runCatching { FlatJson.parse(file.readText()) }.getOrNull() ?: return@mapNotNull null
            val name = fields["name"] as? String ?: return@mapNotNull null
            StoredPhotoLook("custom:$uuid", name, (fields["createdAt"] as? Number)?.toLong() ?: 0L)
        }.sortedWith(compareBy({ it.createdAtMillis }, { it.id }))
    }

    /** Stores [lut] under a fresh id and returns the entry. [name] is trimmed and capped at [MaxNameLength]. */
    fun create(lut: PhotoLut, stats: LabStats, name: String): StoredPhotoLook {
        directory.mkdirs()
        val uuid = newId().toString().lowercase()
        val clean = cleanName(name) ?: error("Filter name is blank")
        val createdAt = clock()
        writeAtomically(File(directory, "$uuid.lut")) { it.writeBytes(lut.toByteArray()) }
        writeAtomically(File(directory, "$uuid.json")) { it.writeText(encode(clean, createdAt, stats)) }
        synchronized(lutCache) { lutCache["custom:$uuid"] = lut }
        return StoredPhotoLook("custom:$uuid", clean, createdAt)
    }

    /** Renames [id]; false when it does not exist or [name] is blank. */
    fun rename(id: String, name: String): Boolean {
        val uuid = uuidOf(id) ?: return false
        val clean = cleanName(name) ?: return false
        val json = File(directory, "$uuid.json")
        val fields = runCatching { FlatJson.parse(json.readText()) }.getOrNull() ?: return false
        writeAtomically(json) {
            it.writeText(encode(clean, (fields["createdAt"] as? Number)?.toLong() ?: 0L, fields))
        }
        return true
    }

    fun delete(id: String): Boolean {
        val uuid = uuidOf(id) ?: return false
        synchronized(lutCache) { lutCache.remove(id) }
        val lut = File(directory, "$uuid.lut").delete()
        val json = File(directory, "$uuid.json").delete()
        return lut || json
    }

    /** The cached LUT of [id], or null when it was deleted or is unreadable. */
    fun lut(id: String): PhotoLut? {
        val uuid = uuidOf(id) ?: return null
        synchronized(lutCache) { lutCache[id]?.let { return it } }
        val file = File(directory, "$uuid.lut")
        if (!file.isFile) return null
        val lut = runCatching { PhotoLut.fromByteArray(file.readBytes()) }.getOrNull() ?: return null
        synchronized(lutCache) { lutCache[id] = lut }
        return lut
    }

    /** The first unused "[format] N" name, where [format] renders the number, e.g. "Filter 3". */
    fun nextDefaultName(format: (Int) -> String): String {
        val taken = list().mapTo(HashSet()) { it.name }
        var number = taken.size + 1
        while (format(number) in taken) number++
        return format(number)
    }

    private fun uuidOf(id: String): String? =
        id.removePrefix("custom:").takeIf { id.startsWith("custom:") && UuidPattern.matches(it) }

    private fun writeAtomically(destination: File, write: (File) -> Unit) {
        val temp = File(directory, "${destination.name}.tmp")
        write(temp)
        if (!temp.renameTo(destination)) {
            destination.delete()
            check(temp.renameTo(destination)) { "Cannot replace ${destination.name}" }
        }
    }

    private fun encode(name: String, createdAt: Long, stats: LabStats): String = FlatJson.write(
        linkedMapOf(
            "name" to name, "createdAt" to createdAt,
            "meanL" to stats.meanL, "stdL" to stats.stdL, "meanA" to stats.meanA,
            "stdA" to stats.stdA, "meanB" to stats.meanB, "stdB" to stats.stdB,
        ),
    )

    private fun encode(name: String, createdAt: Long, previous: Map<String, Any>): String = FlatJson.write(
        linkedMapOf<String, Any>("name" to name, "createdAt" to createdAt).also { result ->
            previous.forEach { (key, value) -> if (key != "name" && key != "createdAt") result[key] = value }
        },
    )

    companion object {
        const val MaxNameLength = 40
        private val UuidPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        fun cleanName(name: String): String? = name.trim().take(MaxNameLength).trim().takeIf { it.isNotEmpty() }
    }
}

/** Reads and writes a single flat JSON object of strings and numbers, enough for the filter metadata. */
internal object FlatJson {
    fun write(fields: Map<String, Any>): String = buildString {
        append('{')
        fields.entries.forEachIndexed { index, (key, value) ->
            if (index > 0) append(',')
            quote(key)
            append(':')
            if (value is String) quote(value) else append(value.toString())
        }
        append('}')
    }

    private fun StringBuilder.quote(text: String) {
        append('"')
        for (c in text) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }

    fun parse(text: String): Map<String, Any> {
        var i = 0
        fun skip() { while (i < text.length && text[i].isWhitespace()) i++ }
        fun expect(c: Char) { skip(); require(i < text.length && text[i] == c) { "Expected $c at $i" }; i++ }
        fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                require(i < text.length) { "Unterminated string" }
                val c = text[i++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        require(i < text.length) { "Bad escape" }
                        when (val e = text[i++]) {
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            'r' -> out.append('\r')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'u' -> {
                                require(i + 4 <= text.length) { "Bad unicode escape" }
                                out.append(text.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> out.append(e)
                        }
                    }
                    else -> out.append(c)
                }
            }
        }
        val result = LinkedHashMap<String, Any>()
        expect('{')
        skip()
        if (i < text.length && text[i] == '}') return result
        while (true) {
            val key = string()
            expect(':')
            skip()
            result[key] = if (i < text.length && text[i] == '"') string() else {
                val start = i
                while (i < text.length && text[i] != ',' && text[i] != '}' && !text[i].isWhitespace()) i++
                val token = text.substring(start, i)
                token.toLongOrNull() ?: token.toDouble()
            }
            skip()
            require(i < text.length) { "Unterminated object" }
            if (text[i] == ',') { i++; continue }
            expect('}')
            return result
        }
    }
}
