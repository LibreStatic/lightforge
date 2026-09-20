package com.ugallery.feature.settings

import java.io.IOException
import java.util.Base64
import java.util.UUID

/**
 * Portable file identities are distinct source IDs plus digests, never recyclable MediaStore IDs.
 */
data class BackupManifest(
    val entries: List<Entry>,
    val organization: Organization? = null,
    val version: Int = if (organization == null) 1 else 2,
) {
    data class Entry(
        val path: String,
        val name: String,
        val mime: String,
        val bytes: Long,
        val sha256: String,
        val sourceId: String = path,
    )

    data class Organization(
        val schemaVersion: Int,
        val bytes: Long,
        val sha256: String,
        val path: String = ORGANIZATION_PATH,
    )

    val totalBytes: Long
        get() = entries.sumOf { it.bytes }

    fun encode(): ByteArray =
        buildString {
                append("UGALLERY-ORIGINALS\t$version\n")
                entries.forEach {
                    val fields =
                        listOf(
                            it.path,
                            it.bytes.toString(),
                            it.sha256,
                            encodeText(it.name),
                            encodeText(it.mime),
                        )
                    append(
                        (if (version == 1) fields else listOf("F") + fields + it.sourceId)
                            .joinToString("\t")
                    )
                    append('\n')
                }
                organization?.let {
                    append(
                        listOf(
                                "O",
                                it.schemaVersion.toString(),
                                it.bytes.toString(),
                                it.sha256,
                                it.path,
                            )
                            .joinToString("\t")
                    )
                    append('\n')
                }
            }
            .toByteArray(Charsets.UTF_8)

    companion object {
        const val PATH = "manifest.tsv"
        const val ORGANIZATION_PATH = "organization.ugallery"
        const val MAX_ENTRIES = 10_000
        const val MAX_MANIFEST_BYTES = 8 * 1024 * 1024
        const val MAX_ORGANIZATION_BYTES = 32 * 1024 * 1024
        const val MAX_FILE_BYTES = 50L * 1024 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 100L * 1024 * 1024 * 1024

        fun decode(bytes: ByteArray): BackupManifest {
            checkFormat(bytes.size <= MAX_MANIFEST_BYTES)
            val lines = bytes.toString(Charsets.UTF_8).split('\n', limit = MAX_ENTRIES + 4)
            checkFormat(lines.lastOrNull() == "")
            val version =
                when (lines.firstOrNull()) {
                    "UGALLERY-ORIGINALS\t1" -> 1
                    "UGALLERY-ORIGINALS\t2" -> 2
                    else -> throw IOException("Unsupported backup version")
                }
            val rows = lines.drop(1).dropLast(1)
            val metadata =
                if (version == 2) {
                    val fields =
                        rows.lastOrNull()?.split('\t', limit = 6)
                            ?: throw IOException("Missing organization descriptor")
                    checkFormat(
                        fields.size == 5 &&
                            fields[0] == "O" &&
                            fields[1] in setOf("1", "2", "3") &&
                            fields[4] == ORGANIZATION_PATH
                    )
                    val size =
                        fields[2].toLongOrNull() ?: throw IOException("Invalid organization size")
                    checkFormat(
                        size in 1..MAX_ORGANIZATION_BYTES.toLong() && validDigest(fields[3])
                    )
                    Organization(fields[1].toInt(), size, fields[3])
                } else null
            val originals = if (version == 1) rows else rows.dropLast(1)
            checkFormat(originals.size in 1..MAX_ENTRIES)
            val entries =
                originals.mapIndexed { index, row ->
                    val raw = row.split('\t', limit = 8)
                    checkFormat(if (version == 1) raw.size == 5 else raw.size == 7 && raw[0] == "F")
                    val fields = if (version == 1) raw else raw.drop(1)
                    val size =
                        fields[1].toLongOrNull() ?: throw IOException("Invalid manifest size")
                    checkFormat(
                        fields[0] == path(index) &&
                            size in 0..MAX_FILE_BYTES &&
                            validDigest(fields[2])
                    )
                    val name = decodeText(fields[3])
                    val mime = decodeText(fields[4])
                    checkFormat(
                        validName(name) &&
                            mime.length in 1..128 &&
                            mime.matches(Regex("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+"))
                    )
                    val sourceId =
                        if (version == 1) fields[0]
                        else fields[5].also { checkFormat(validUuid(it)) }
                    Entry(fields[0], name, mime, size, fields[2], sourceId)
                }
            checkFormat(
                entries.sumOf { it.bytes } <= MAX_TOTAL_BYTES &&
                    entries.map { it.sourceId }.distinct().size == entries.size
            )
            return BackupManifest(entries, metadata, version)
        }

        fun path(index: Int): String = "originals/" + index.toString().padStart(8, '0')

        fun validName(name: String): Boolean =
            name.length in 1..255 &&
                name !in setOf(".", "..") &&
                name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }

        private fun validUuid(value: String): Boolean =
            runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

        private fun validDigest(value: String): Boolean = value.matches(Regex("[a-f0-9]{64}"))

        private fun encodeText(text: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))

        private fun decodeText(text: String): String =
            try {
                val value = String(Base64.getUrlDecoder().decode(text), Charsets.UTF_8)
                checkFormat(encodeText(value) == text)
                value
            } catch (e: IllegalArgumentException) {
                throw IOException("Invalid manifest encoding", e)
            }

        internal fun checkFormat(valid: Boolean) {
            if (!valid) throw IOException("Invalid or unsupported UGallery backup")
        }
    }
}
