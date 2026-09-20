package com.ugallery.feature.settings

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Bounded streaming archive. No entry path is ever interpreted as a destination path. */
object LocalBackupArchive {
    data class Source(
        val name: String,
        val mime: String,
        val sourceUri: String? = null,
        val open: () -> InputStream,
    )

    data class Progress(val completed: Int, val total: Int, val bytes: Long)

    interface RestoreTarget {
        fun open(name: String, mime: String): OutputStream

        fun commit()

        /** Removes only objects created by this transaction, not existing destination content. */
        fun abort()
    }

    fun create(
        sources: List<Source>,
        archive: File,
        checkCancelled: () -> Unit = {},
        progress: (Progress) -> Unit = {},
    ): BackupManifest {
        try {
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                val entries = writeOriginals(sources, zip, false, checkCancelled, progress)
                writeManifest(zip, BackupManifest(entries))
            }
            return inspect(archive, checkCancelled)
        } catch (error: Throwable) {
            archive.delete()
            throw error
        }
    }

    suspend fun createOrganized(
        sources: List<Source>,
        archive: File,
        port: LocalBackupOrganizationPort,
        progress: (Progress) -> Unit = {},
    ): BackupManifest =
        withContext(Dispatchers.IO) {
            val coroutine = currentCoroutineContext()
            val check = { coroutine.ensureActive() }
            try {
                ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                    val entries = writeOriginals(sources, zip, true, check, progress)
                    val payload =
                        port.export(
                            entries.zip(sources).map { (entry, source) ->
                                LocalBackupSourceRef(entry, source.sourceUri)
                            }
                        )
                    check()
                    BackupManifest.checkFormat(
                        payload.schemaVersion in 1..3 &&
                            payload.bytes.size in 1..BackupManifest.MAX_ORGANIZATION_BYTES
                    )
                    val digest =
                        MessageDigest.getInstance("SHA-256").digest(payload.bytes).joinToString(
                            ""
                        ) {
                            "%02x".format(it)
                        }
                    zip.putNextEntry(ZipEntry(BackupManifest.ORGANIZATION_PATH))
                    zip.write(payload.bytes)
                    zip.closeEntry()
                    writeManifest(
                        zip,
                        BackupManifest(
                            entries,
                            BackupManifest.Organization(
                                payload.schemaVersion,
                                payload.bytes.size.toLong(),
                                digest,
                            ),
                        ),
                    )
                }
                inspect(archive, check)
            } catch (error: Throwable) {
                archive.delete()
                throw error
            }
        }

    private fun writeOriginals(
        sources: List<Source>,
        zip: ZipOutputStream,
        distinctIds: Boolean,
        check: () -> Unit,
        progress: (Progress) -> Unit,
    ): List<BackupManifest.Entry> {
        BackupManifest.checkFormat(sources.size in 1..BackupManifest.MAX_ENTRIES)
        sources.forEach { BackupManifest.checkFormat(BackupManifest.validName(it.name)) }
        val entries = mutableListOf<BackupManifest.Entry>()
        var total = 0L
        zip.setLevel(0)
        sources.forEachIndexed { index, source ->
            check()
            val path = BackupManifest.path(index)
            zip.putNextEntry(ZipEntry(path))
            val result =
                source.open().use { copyChecked(it, zip, BackupManifest.MAX_FILE_BYTES, check) }
            zip.closeEntry()
            total += result.first
            BackupManifest.checkFormat(total <= BackupManifest.MAX_TOTAL_BYTES)
            entries +=
                BackupManifest.Entry(
                    path,
                    source.name,
                    source.mime,
                    result.first,
                    result.second,
                    if (distinctIds) UUID.randomUUID().toString() else path,
                )
            progress(Progress(index + 1, sources.size, total))
        }
        return entries
    }

    private fun writeManifest(zip: ZipOutputStream, manifest: BackupManifest) {
        val bytes = manifest.encode()
        BackupManifest.decode(bytes)
        zip.putNextEntry(ZipEntry(BackupManifest.PATH))
        zip.write(bytes)
        zip.closeEntry()
    }

    /**
     * Complete integrity check, including every byte, before any restore destination is touched.
     */
    fun inspect(archive: File, checkCancelled: () -> Unit = {}): BackupManifest {
        val observed = linkedMapOf<String, Pair<Long, String>>()
        var manifest: BackupManifest? = null
        var organization: Pair<Long, String>? = null
        var total = 0L
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                checkCancelled()
                val entry = zip.nextEntry ?: break
                BackupManifest.checkFormat(!entry.isDirectory && manifest == null)
                if (entry.name == BackupManifest.PATH) {
                    val bytes = java.io.ByteArrayOutputStream()
                    copyChecked(
                        zip,
                        bytes,
                        BackupManifest.MAX_MANIFEST_BYTES.toLong(),
                        checkCancelled,
                    )
                    manifest = BackupManifest.decode(bytes.toByteArray())
                } else if (entry.name == BackupManifest.ORGANIZATION_PATH) {
                    BackupManifest.checkFormat(organization == null)
                    organization =
                        copyChecked(
                            zip,
                            DISCARD,
                            BackupManifest.MAX_ORGANIZATION_BYTES.toLong(),
                            checkCancelled,
                        )
                } else {
                    BackupManifest.checkFormat(organization == null)
                    BackupManifest.checkFormat(
                        observed.size < BackupManifest.MAX_ENTRIES &&
                            entry.name == BackupManifest.path(observed.size)
                    )
                    val result =
                        copyChecked(zip, DISCARD, BackupManifest.MAX_FILE_BYTES, checkCancelled)
                    total += result.first
                    BackupManifest.checkFormat(total <= BackupManifest.MAX_TOTAL_BYTES)
                    observed[entry.name] = result
                }
                zip.closeEntry()
            }
        }
        val parsed = manifest ?: throw IOException("Missing backup manifest")
        BackupManifest.checkFormat(
            if (parsed.organization == null) organization == null
            else organization == (parsed.organization.bytes to parsed.organization.sha256)
        )
        BackupManifest.checkFormat(parsed.entries.size == observed.size)
        parsed.entries.forEach {
            BackupManifest.checkFormat(observed[it.path] == (it.bytes to it.sha256))
        }
        return parsed
    }

    /**
     * Restores into an isolated transaction. Any error/cancellation rolls back new destination
     * files.
     */
    fun restore(
        archive: File,
        expected: BackupManifest,
        target: RestoreTarget,
        checkCancelled: () -> Unit = {},
        progress: (Progress) -> Unit = {},
    ) {
        val verified = inspect(archive, checkCancelled)
        BackupManifest.checkFormat(verified == expected)
        var total = 0L
        try {
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                verified.entries.forEachIndexed { index, item ->
                    checkCancelled()
                    val entry = zip.nextEntry ?: throw IOException("Missing original")
                    BackupManifest.checkFormat(entry.name == item.path && !entry.isDirectory)
                    val copied =
                        target.open(item.name, item.mime).use {
                            copyChecked(zip, it, item.bytes, checkCancelled)
                        }
                    BackupManifest.checkFormat(copied == (item.bytes to item.sha256))
                    total += copied.first
                    progress(Progress(index + 1, verified.entries.size, total))
                    zip.closeEntry()
                }
                checkCancelled()
            }
            target.commit()
        } catch (error: Throwable) {
            try {
                target.abort()
            } catch (cleanup: Throwable) {
                error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    /** The archive was already fully inspected; read only the bounded sidecar via ZIP directory. */
    fun readOrganization(
        archive: File,
        expected: BackupManifest,
        checkCancelled: () -> Unit = {},
    ): ByteArray {
        val descriptor = expected.organization ?: throw IOException("No organization in backup")
        checkCancelled()
        ZipFile(archive).use { zip ->
            val entry =
                zip.getEntry(descriptor.path) ?: throw IOException("Missing organization payload")
            BackupManifest.checkFormat(!entry.isDirectory)
            val output = java.io.ByteArrayOutputStream()
            val observed =
                zip.getInputStream(entry).use {
                    copyChecked(
                        it,
                        output,
                        BackupManifest.MAX_ORGANIZATION_BYTES.toLong(),
                        checkCancelled,
                    )
                }
            BackupManifest.checkFormat(observed == (descriptor.bytes to descriptor.sha256))
            return output.toByteArray()
        }
    }

    suspend fun restoreGallery(
        archive: File,
        expected: BackupManifest,
        port: LocalBackupOrganizationPort,
        options: LocalRestoreOrganizationOptions = LocalRestoreOrganizationOptions(),
        progress: (Progress) -> Unit = {},
    ): LocalRestoreGalleryResult {
        var session: LocalRestoreGallerySession? = null
        try {
            return withContext(Dispatchers.IO) {
                val coroutine = currentCoroutineContext()
                val check = { coroutine.ensureActive() }
                BackupManifest.checkFormat(
                    inspect(archive, check) == expected && expected.organization != null
                )
                val payload = readOrganization(archive, expected, check)
                val review = port.review(payload, expected)
                BackupManifest.checkFormat(review.canRestore)
                val destination = port.beginRestore(payload, expected, options)
                session = destination
                var total = 0L
                ZipInputStream(archive.inputStream().buffered()).use { zip ->
                    expected.entries.forEachIndexed { index, entry ->
                        check()
                        BackupManifest.checkFormat(zip.nextEntry?.name == entry.path)
                        val input = GalleryEntryInput(zip, entry.bytes, check)
                        try {
                            destination.stage(entry, input)
                            BackupManifest.checkFormat(
                                input.read() == -1 &&
                                    input.bytes == entry.bytes &&
                                    input.sha256() == entry.sha256
                            )
                        } finally {
                            input.invalidate()
                        }
                        total += entry.bytes
                        progress(Progress(index + 1, expected.entries.size, total))
                        zip.closeEntry()
                    }
                }
                check()
                destination.commit()
            }
        } catch (error: Throwable) {
            try {
                withContext(NonCancellable + Dispatchers.IO) { session?.abort() }
            } catch (cleanup: Throwable) {
                error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    private class GalleryEntryInput(
        private val source: InputStream,
        private val limit: Long,
        private val check: () -> Unit,
    ) : InputStream() {
        var bytes = 0L
            private set

        private val digest = MessageDigest.getInstance("SHA-256")
        private var active = true

        fun invalidate() {
            active = false
        }

        private fun checkLifetime() {
            if (!active) throw IOException("Original stream lifetime ended")
        }

        override fun read(): Int {
            checkLifetime()
            check()
            val value = source.read()
            if (value >= 0) {
                if (++bytes > limit) throw IOException("Original size changed")
                digest.update(value.toByte())
            }
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            checkLifetime()
            check()
            val count = source.read(buffer, offset, length)
            if (count > 0) {
                bytes += count
                if (bytes > limit) throw IOException("Original size changed")
                digest.update(buffer, offset, count)
            }
            return count
        }

        override fun close() {} // Lifetime is this stage call; the archive owns the underlying

        // stream.

        fun sha256(): String = digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun copyChecked(
        input: InputStream,
        output: OutputStream,
        limit: Long,
        checkCancelled: () -> Unit,
    ): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > limit) throw IOException("Backup size limit exceeded")
            output.write(buffer, 0, count)
            digest.update(buffer, 0, count)
        }
        return total to digest.digest().joinToString("") { "%02x".format(it) }
    }

    private val DISCARD =
        object : OutputStream() {
            override fun write(value: Int) {}

            override fun write(bytes: ByteArray, offset: Int, length: Int) {}
        }
}
