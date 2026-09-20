package com.ugallery.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.AtomicFile
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.PortableMediaKind
import com.ugallery.core.model.PortableSourceFacts
import com.ugallery.feature.settings.BackupManifest
import com.ugallery.feature.settings.LocalBackupTaskRetryEntry
import com.ugallery.feature.settings.LocalRestoreGalleryResult
import com.ugallery.feature.settings.LocalRestoreGallerySession
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Values observed from the newly inserted destination, never IDs from a backup or digest search.
 */
data class GalleryRestoredMedia(
    val entry: BackupManifest.Entry,
    val uri: Uri,
    val key: MediaKey?,
    val generationAdded: Long,
    val generationModified: Long,
) {
    val generation: Long
        get() = generationModified
}

data class GalleryRestoreRecoveryResult(val removed: Int, val retained: Int, val committed: Int)

/**
 * One operation owns only its new MediaStore copies. Room receipt is the authoritative commit
 * marker. Journal is durable before insert, before publication and after each observed destination
 * change. Recovery retains ambiguous published rows, changed generations, changed bytes and unknown
 * owners.
 */
class GalleryRestoreMediaSession(
    context: Context,
    private val operationId: String,
    private val facts: Map<String, PortableSourceFacts>,
    private val commitMetadata:
        suspend (String, List<GalleryRestoredMedia>) -> LocalRestoreGalleryResult,
    private val isCommitted: suspend (String) -> Boolean,
    private val volumeName: String = MediaStore.VOLUME_EXTERNAL_PRIMARY,
    private val resume: Boolean = false,
) : LocalRestoreGallerySession {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val journal =
        File(this.context.filesDir, "$JournalDirectory/$operationId.json").let { file ->
            if (resume && (file.exists() || File(file.path + ".bak").exists())) Journal.load(file)
            else Journal(file.parentFile!!, operationId, this.context.packageName, volumeName)
        }
    private var finished = false

    init {
        require(validUuid(operationId) && facts.size <= BackupManifest.MAX_ENTRIES)
        require(volumeName.isNotBlank() && '/' !in volumeName)
        synchronized(active) {
            check(active.add(journal.file.absolutePath)) { "Restore already active" }
        }
        try {
            check(resume || !journal.file.exists()) { "Restore operation requires recovery" }
            require(journal.owner == this.context.packageName && journal.volume == volumeName)
            journal.save()
        } catch (error: Throwable) {
            synchronized(active) { active.remove(journal.file.absolutePath) }
            throw error
        }
    }

    override suspend fun stage(entry: BackupManifest.Entry, input: InputStream) =
        withContext(Dispatchers.IO) {
            check(!finished)
            require(validUuid(entry.sourceId) && BackupManifest.validName(entry.name))
            val previous = journal.rows.singleOrNull { it.entry.sourceId == entry.sourceId }
            if (previous != null) {
                require(resume && previous.entry == entry)
                resumeEntry(previous, input)
                return@withContext
            }
            require(journal.rows.size < BackupManifest.MAX_ENTRIES)
            val sourceFacts = facts[entry.sourceId]
            if (sourceFacts != null)
                require(sourceFacts.sha256 == entry.sha256 && sourceFacts.sizeBytes == entry.bytes)
            val kind =
                sourceFacts?.kind
                    ?: when {
                        entry.mime.startsWith("image/") -> PortableMediaKind.Image
                        entry.mime.startsWith("video/") -> PortableMediaKind.Video
                        else -> PortableMediaKind.Document
                    }
            val path =
                "${when(kind) { PortableMediaKind.Image -> "Pictures"
PortableMediaKind.Video -> "Movies"
PortableMediaKind.Document -> "Download" }}/UGallery Restore/$operationId/"
            val row = OwnedRow(entry, kind, path)
            journal.rows += row
            journal
                .save() // Planned record exists even if death occurs immediately after MediaStore
            // insert.
            val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, entry.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, entry.mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                    sourceFacts?.let {
                        put(MediaStore.MediaColumns.IS_FAVORITE, if (it.isFavorite) 1 else 0)
                        it.dateTakenMillis?.let { value ->
                            put(MediaStore.MediaColumns.DATE_TAKEN, value)
                        }
                    }
                }
            val uri =
                resolver.insert(collection(volumeName, kind), values)
                    ?: throw IOException("Gallery rejected restore copy")
            row.uri = uri.toString()
            val inserted = observe(uri) ?: throw IOException("Inserted restore copy unavailable")
            requireOwned(row, inserted)
            row.observe(inserted)
            row.phase = "writing"
            journal.save()
            val coroutine = currentCoroutineContext()
            val check = { coroutine.ensureActive() }
            val observed =
                (resolver.openOutputStream(uri, "w")
                        ?: throw IOException("Restore output unavailable"))
                    .use { output ->
                        val digest = MessageDigest.getInstance("SHA-256")
                        var size = 0L
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            check()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            size += count
                            if (size > entry.bytes)
                                throw IOException("Source exceeds reviewed size")
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                        }
                        size to hex(digest.digest())
                    }
            if (observed != (entry.bytes to entry.sha256))
                throw IOException("Source changed during restore")
            verifyBytes(uri, entry, check)
            val current = observe(uri) ?: throw IOException("Restore copy disappeared")
            requireOwned(row, current)
            row.observe(current)
            row.phase = "verified"
            journal.save()
        }

    override suspend fun commit(): LocalRestoreGalleryResult =
        withContext(Dispatchers.IO) {
            check(
                !finished &&
                    journal.rows.isNotEmpty() &&
                    journal.rows.all {
                        it.phase == "verified" || (resume && it.phase == "published")
                    }
            )
            val coroutine = currentCoroutineContext()
            val check = { coroutine.ensureActive() }
            journal.rows.forEach { row ->
                check()
                val uri = Uri.parse(requireNotNull(row.uri))
                val before = observe(uri) ?: throw IOException("Restore copy missing")
                requireOwned(row, before)
                if (
                    before.generationAdded != row.generationAdded ||
                        before.generationModified != row.generationModified
                )
                    throw IOException("Restore copy changed before publish")
                if (resume && row.phase == "published") {
                    check(!before.pending)
                    verifyBytes(uri, row.entry, check)
                    return@forEach
                }
                row.phase = "publishing"
                journal.save()
                val values =
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                        facts[row.entry.sourceId]?.let {
                            put(MediaStore.MediaColumns.IS_FAVORITE, if (it.isFavorite) 1 else 0)
                        }
                    }
                if (resolver.update(uri, values, null, null) != 1)
                    throw IOException("Restore publication failed")
                val published =
                    observe(uri) ?: throw IOException("Published restore copy unavailable")
                requireOwned(row, published)
                if (published.pending) throw IOException("Restore copy remains pending")
                row.observe(published)
                row.phase = "published"
                journal.save()
                verifyBytes(uri, row.entry, check)
                facts[row.entry.sourceId]?.let {
                    if (published.favorite != it.isFavorite)
                        throw IOException("Platform favorite was not preserved")
                }
            }
            val mapping =
                journal.rows.map { row ->
                    check()
                    val uri = Uri.parse(requireNotNull(row.uri))
                    val current = observe(uri) ?: throw IOException("Published copy missing")
                    requireOwned(row, current)
                    if (
                        current.generationAdded != row.generationAdded ||
                            current.generationModified != row.generationModified
                    )
                        throw IOException("Published copy changed before metadata commit")
                    GalleryRestoredMedia(
                        row.entry,
                        uri,
                        if (row.kind == PortableMediaKind.Document) null
                        else MediaKey(volumeName, current.id),
                        current.generationAdded,
                        current.generationModified,
                    )
                }
            check()
            val result = commitMetadata(operationId, mapping)
            // An exception/uncertainty here retains the journal; abort will consult the receipt
            // again.
            if (!isCommitted(operationId)) throw IOException("Metadata commit receipt missing")
            finished = true
            journal.delete()
            release()
            result
        }

    override suspend fun pause() =
        withContext(NonCancellable + Dispatchers.IO) {
            // Durable task state owns retry. Do not turn scheduler interruption into user
            // cancellation.
            if (!finished) journal.save()
            release()
        }

    private suspend fun resumeEntry(row: OwnedRow, input: InputStream) {
        val coroutine = currentCoroutineContext()
        val check = { coroutine.ensureActive() }
        val uri = row.uri?.let(Uri::parse)
        if (uri == null) {
            if (operationRows(context, journal, row).isNotEmpty())
                throw IOException("Unresolved interrupted insertion requires review")
            journal.rows.remove(row)
            journal.save()
            throw LocalBackupTaskRetryEntry()
        }
        val current = observe(uri) ?: throw IOException("Interrupted restore copy missing")
        requireOwned(row, current)
        require(current.generationAdded == row.generationAdded)
        val complete = row.phase in setOf("verified", "published", "publishing")
        if (complete) {
            require(current.generationModified == row.generationModified)
            require(current.pending == (row.phase != "published"))
            verifyBytes(uri, row.entry, check)
        } else require(current.pending)
        // Compare every existing destination byte to the immutable archive prefix. Never discard
        // an externally changed partial merely because it still has our name or pending flag.
        val hash = MessageDigest.getInstance("SHA-256")
        var total = 0L
        resolver.openInputStream(uri)!!.use { existing ->
            val buffer = ByteArray(65536)
            val actual = ByteArray(65536)
            var destinationEnded = false
            while (true) {
                check()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                require(total <= row.entry.bytes)
                hash.update(buffer, 0, count)
                var compared = 0
                while (!destinationEnded && compared < count) {
                    val read = existing.read(actual, 0, count - compared)
                    if (read < 0) {
                        destinationEnded = true
                        break
                    }
                    if (read == 0) continue
                    repeat(read) {
                        require(actual[it] == buffer[compared + it]) { "Interrupted copy changed" }
                    }
                    compared += read
                }
            }
            require(existing.read() == -1)
        }
        require(total == row.entry.bytes && hex(hash.digest()) == row.entry.sha256)
        if (complete) {
            if (row.phase == "publishing") {
                row.phase = "verified"
                journal.save()
            }
        } else {
            check()
            if (
                deleteUnchanged(
                    context,
                    uri,
                    journal.owner,
                    row.path,
                    current.generationAdded,
                    current.generationModified,
                ) != 1
            )
                throw IOException("Interrupted copy changed during cleanup")
            journal.rows.remove(row)
            journal.save()
            throw LocalBackupTaskRetryEntry()
        }
    }

    override suspend fun verifyBeforeAbort(entry: BackupManifest.Entry, input: InputStream) =
        withContext(Dispatchers.IO) {
            val row =
                journal.rows.singleOrNull { it.entry.sourceId == entry.sourceId }
                    ?: return@withContext
            require(row.entry == entry)
            try {
                resumeEntry(row, input)
            } catch (_: LocalBackupTaskRetryEntry) {
                /* Verified own partial removed. */
            }
        }

    override suspend fun abort() =
        withContext(NonCancellable + Dispatchers.IO) {
            if (finished) return@withContext
            try {
                if (isCommitted(operationId)) {
                    finished = true
                    journal.delete()
                    return@withContext
                }
                val outcome = cleanup(journal)
                if (outcome.second > 0)
                    throw IOException(
                        "Restore cleanup retained ${outcome.second} changed or ambiguous copies"
                    )
                finished = true
                journal.delete()
            } finally {
                release()
            }
        }

    private fun release() {
        synchronized(active) { active.remove(journal.file.absolutePath) }
    }

    private fun observe(uri: Uri): Observed? = observe(context, uri)

    private fun verifyBytes(uri: Uri, entry: BackupManifest.Entry, check: () -> Unit) =
        verifyBytes(context, uri, entry, check)

    private fun requireOwned(row: OwnedRow, current: Observed) {
        if (
            current.owner != context.packageName ||
                current.path != row.path ||
                (row.name != null && row.name != current.name)
        )
            throw IOException("Restore destination ownership changed")
    }

    private fun cleanup(value: Journal): Pair<Int, Int> = cleanup(context, value)

    companion object {
        private const val JournalDirectory = "gallery-restore-journal"
        private const val MaxJournalBytes = 16 * 1024 * 1024
        private val active = mutableSetOf<String>()

        private fun validUuid(value: String) =
            runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        private fun collection(volume: String, kind: PortableMediaKind): Uri =
            when (kind) {
                PortableMediaKind.Image -> MediaStore.Images.Media.getContentUri(volume)
                PortableMediaKind.Video -> MediaStore.Video.Media.getContentUri(volume)
                PortableMediaKind.Document -> MediaStore.Downloads.getContentUri(volume)
            }

        /**
         * Call on startup with Room available. Never interpret a failed receipt lookup as false.
         */
        suspend fun recover(
            context: Context,
            retainedOperationIds: Set<String> = emptySet(),
            isCommitted: suspend (String) -> Boolean,
        ): GalleryRestoreRecoveryResult =
            withContext(Dispatchers.IO) {
                val root = File(context.filesDir, JournalDirectory)
                var removed = 0
                var retained = 0
                var committed = 0
                root
                    .listFiles()
                    ?.mapNotNull { candidate ->
                        val baseName =
                            if (candidate.name.endsWith(".json.bak"))
                                candidate.name.removeSuffix(".bak")
                            else candidate.name
                        if (baseName.endsWith(".json") && validUuid(baseName.removeSuffix(".json")))
                            File(root, baseName)
                        else null
                    }
                    ?.distinctBy { it.absolutePath }
                    ?.forEach { file ->
                        if (
                            file.nameWithoutExtension in retainedOperationIds ||
                                synchronized(active) { file.absolutePath in active }
                        )
                            return@forEach
                        val journal =
                            try {
                                Journal.load(file)
                            } catch (_: Exception) {
                                retained++
                                return@forEach
                            }
                        if (journal.owner != context.packageName) {
                            retained++
                            return@forEach
                        }
                        if (isCommitted(journal.id)) {
                            committed++
                            journal.delete()
                        } else {
                            val result = cleanup(context, journal)
                            removed += result.first
                            retained += result.second
                            if (result.second == 0) journal.delete()
                        }
                    }
                GalleryRestoreRecoveryResult(removed, retained, committed)
            }

        private fun observe(context: Context, uri: Uri): Observed? {
            val columns =
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.GENERATION_ADDED,
                    MediaStore.MediaColumns.GENERATION_MODIFIED,
                    MediaStore.MediaColumns.IS_PENDING,
                    MediaStore.MediaColumns.IS_FAVORITE,
                )
            return context.contentResolver
                .query(
                    uri,
                    columns,
                    Bundle().apply {
                        putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                    },
                    null,
                )
                ?.use { c ->
                    if (!c.moveToFirst()) null
                    else
                        Observed(
                            c.getLong(0),
                            c.getString(1),
                            c.getString(2),
                            c.getString(3),
                            c.getLong(4),
                            c.getLong(5),
                            c.getInt(6) != 0,
                            c.getInt(7) != 0,
                        )
                }
        }

        private fun verifyBytes(
            context: Context,
            uri: Uri,
            entry: BackupManifest.Entry,
            check: () -> Unit,
        ) {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            val buffer = ByteArray(64 * 1024)
            (context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Restore readback unavailable"))
                .use { input ->
                    while (true) {
                        check()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        size += count
                        if (size > entry.bytes) throw IOException("Restored size changed")
                        digest.update(buffer, 0, count)
                    }
                }
            if (size != entry.bytes || hex(digest.digest()) != entry.sha256)
                throw IOException("Restored bytes differ")
        }

        private fun cleanup(context: Context, journal: Journal): Pair<Int, Int> {
            var removed = 0
            val retained = mutableSetOf<Uri>()
            var unresolvedQueries = 0
            journal.rows.forEach { row ->
                val candidates =
                    if (row.uri != null) listOf(Uri.parse(row.uri))
                    else plannedCandidates(context, journal, row)
                candidates.forEach { uri ->
                    try {
                        val current = observe(context, uri) ?: return@forEach
                        if (
                            current.owner != journal.owner ||
                                current.path != row.path ||
                                (row.name != null && current.name != row.name) ||
                                (row.generationAdded >= 0 &&
                                    current.generationAdded != row.generationAdded)
                        ) {
                            retained += uri
                            return@forEach
                        }
                        if (!current.pending) {
                            // Publication changed generation: without an after-publish fingerprint,
                            // user metadata edits cannot be distinguished; retain for explicit
                            // review.
                            if (
                                row.phase != "published" ||
                                    current.generationModified != row.generationModified
                            ) {
                                retained += uri
                                return@forEach
                            }
                            verifyBytes(context, uri, row.entry, {})
                        } else if (row.phase == "published") {
                            retained += uri
                            return@forEach
                        } else if (row.phase == "writing") {
                            // An interrupted writer and an external edit are indistinguishable
                            // without an immutable source prefix. Durable tasks verify it first.
                            retained += uri
                            return@forEach
                        } else if (row.phase == "verified" || row.phase == "publishing") {
                            if (current.generationModified != row.generationModified) {
                                retained += uri
                                return@forEach
                            }
                            verifyBytes(context, uri, row.entry, {})
                        }
                        if (
                            deleteUnchanged(
                                context,
                                uri,
                                journal.owner,
                                row.path,
                                current.generationAdded,
                                current.generationModified,
                            ) == 1
                        )
                            removed++
                        else retained += uri
                    } catch (_: Exception) {
                        retained += uri
                    }
                }
            }
            // An insert can succeed before its returned URI reaches the journal. A provider may
            // rename duplicate basenames, so name-based candidates alone cannot prove emptiness.
            // Observe only this operation's owner/path/collection; never delete unresolved rows.
            journal.rows
                .distinctBy { it.kind to it.path }
                .forEach { row ->
                    try {
                        retained += operationRows(context, journal, row)
                    } catch (_: Exception) {
                        unresolvedQueries++
                    }
                }
            return removed to (retained.size + unresolvedQueries)
        }

        private fun plannedCandidates(
            context: Context,
            journal: Journal,
            row: OwnedRow,
        ): List<Uri> {
            val collection = collection(journal.volume, row.kind)
            return context.contentResolver
                .query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    Bundle().apply {
                        putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
                        putString(
                            android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                        )
                        putStringArray(
                            android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                            arrayOf(journal.owner, row.path, row.entry.name),
                        )
                    },
                    null,
                )
                ?.use { c ->
                    buildList {
                        while (c.moveToNext()) add(
                            ContentUris.withAppendedId(collection, c.getLong(0))
                        )
                    }
                } ?: emptyList()
        }

        /** Compare-and-delete closes the gap between readback verification and row removal. */
        internal fun deleteUnchanged(
            context: Context,
            uri: Uri,
            owner: String,
            path: String,
            generationAdded: Long,
            generationModified: Long,
        ): Int =
            context.contentResolver.delete(
                uri,
                "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.GENERATION_ADDED}=? AND ${MediaStore.MediaColumns.GENERATION_MODIFIED}=?",
                arrayOf(owner, path, generationAdded.toString(), generationModified.toString()),
            )

        private fun operationRows(context: Context, journal: Journal, row: OwnedRow): List<Uri> {
            val collection = collection(journal.volume, row.kind)
            return (context.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    Bundle().apply {
                        putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                        putString(
                            android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                        )
                        putStringArray(
                            android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                            arrayOf(journal.owner, row.path),
                        )
                    },
                    null,
                ) ?: throw IOException("Restore operation inventory unavailable"))
                .use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(
                            ContentUris.withAppendedId(collection, cursor.getLong(0))
                        )
                    }
                }
        }

        private data class Observed(
            val id: Long,
            val owner: String?,
            val path: String?,
            val name: String?,
            val generationAdded: Long,
            val generationModified: Long,
            val pending: Boolean,
            val favorite: Boolean,
        )

        private data class OwnedRow(
            val entry: BackupManifest.Entry,
            val kind: PortableMediaKind,
            val path: String,
            var uri: String? = null,
            var name: String? = null,
            var generationAdded: Long = -1,
            var generationModified: Long = -1,
            var phase: String = "planned",
        ) {
            fun observe(value: Observed) {
                name = value.name
                generationAdded = value.generationAdded
                generationModified = value.generationModified
            }

            fun json() =
                JSONObject()
                    .put(
                        "entry",
                        JSONObject()
                            .put("path", entry.path)
                            .put("name", entry.name)
                            .put("mime", entry.mime)
                            .put("bytes", entry.bytes)
                            .put("sha256", entry.sha256)
                            .put("sourceId", entry.sourceId),
                    )
                    .put("kind", kind.name)
                    .put("relativePath", path)
                    .put("uri", uri ?: JSONObject.NULL)
                    .put("actualName", name ?: JSONObject.NULL)
                    .put("added", generationAdded)
                    .put("modified", generationModified)
                    .put("phase", phase)
        }

        private class Journal(root: File, val id: String, val owner: String, val volume: String) {
            val file = File(root, "$id.json")
            val rows = mutableListOf<OwnedRow>()

            fun save() {
                file.parentFile!!.mkdirs()
                val bytes =
                    JSONObject()
                        .put("version", 1)
                        .put("operationId", id)
                        .put("owner", owner)
                        .put("volume", volume)
                        .put("rows", JSONArray(rows.map { it.json() }))
                        .toString()
                        .toByteArray()
                if (bytes.size > MaxJournalBytes)
                    throw IOException("Restore journal limit exceeded")
                val atomic = AtomicFile(file)
                val output = atomic.startWrite()
                try {
                    output.write(bytes)
                    atomic.finishWrite(output)
                } catch (error: Throwable) {
                    atomic.failWrite(output)
                    throw error
                }
            }

            fun delete() {
                AtomicFile(file).delete()
            }

            companion object {
                fun load(file: File): Journal {
                    val atomic = AtomicFile(file)
                    val bytes =
                        atomic.openRead().use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(64 * 1024)
                            var total = 0
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= MaxJournalBytes)
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                    require(bytes.isNotEmpty())
                    val json = JSONObject(bytes.toString(Charsets.UTF_8))
                    require(
                        json.getInt("version") == 1 &&
                            json.getString("operationId") == file.nameWithoutExtension
                    )
                    val result =
                        Journal(
                            file.parentFile!!,
                            json.getString("operationId"),
                            json.getString("owner"),
                            json.getString("volume"),
                        )
                    val rows = json.getJSONArray("rows")
                    require(rows.length() <= BackupManifest.MAX_ENTRIES)
                    repeat(rows.length()) { index ->
                        val value = rows.getJSONObject(index)
                        val entry = value.getJSONObject("entry")
                        val parsed =
                            BackupManifest.Entry(
                                entry.getString("path"),
                                entry.getString("name"),
                                entry.getString("mime"),
                                entry.getLong("bytes"),
                                entry.getString("sha256"),
                                entry.getString("sourceId"),
                            )
                        require(
                            validUuid(parsed.sourceId) &&
                                BackupManifest.validName(parsed.name) &&
                                parsed.bytes in 0..BackupManifest.MAX_FILE_BYTES &&
                                parsed.sha256.matches(Regex("[a-f0-9]{64}"))
                        )
                        val row =
                            OwnedRow(
                                parsed,
                                PortableMediaKind.valueOf(value.getString("kind")),
                                value.getString("relativePath"),
                                if (value.isNull("uri")) null else value.getString("uri"),
                                if (value.isNull("actualName")) null
                                else value.getString("actualName"),
                                value.getLong("added"),
                                value.getLong("modified"),
                                value.getString("phase"),
                            )
                        require(
                            row.path.endsWith("/UGallery Restore/${result.id}/") &&
                                row.phase in
                                    setOf(
                                        "planned",
                                        "writing",
                                        "verified",
                                        "publishing",
                                        "published",
                                    )
                        )
                        row.uri?.let { require(Uri.parse(it).authority == MediaStore.AUTHORITY) }
                        result.rows += row
                    }
                    require(
                        result.rows.map { it.entry.sourceId }.distinct().size == result.rows.size
                    )
                    return result
                }
            }
        }
    }
}
