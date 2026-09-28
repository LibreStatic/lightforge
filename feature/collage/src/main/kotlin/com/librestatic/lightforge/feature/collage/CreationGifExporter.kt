package com.librestatic.lightforge.feature.collage

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*

data class CreationGifSource(
    val uri: Uri,
    val expectedGeneration: Long? = null,
    val expectedGenerationAdded: Long? = null,
) {
    val identity: String get() = creationGifSourceIdentity(uri.toString(), expectedGeneration, expectedGenerationAdded)
}

internal fun creationGifSourceIdentity(uri: String, modified: Long?, added: Long?): String = "$uri@$modified/$added"

/** One ordered subset of the same exact source snapshot; invalid saved state cannot start work. */
internal fun validCreationGifDraft(
    savedSessionId: String, sessionId: String, savedIdentities: List<String>, identities: List<String>,
    order: List<Int>, seconds: Int, current: Int,
): Boolean = sessionId.isNotBlank() && sessionId.length <= 128 && savedSessionId == sessionId &&
    identities.size in 2..60 && savedIdentities == identities &&
    order.size in 2..identities.size && order.distinct().size == order.size &&
    order.all { it in identities.indices } && GifFrameTiming.isValid(seconds) && current in order.indices

internal fun requireCreationGifGeneration(
    expectedModified: Long, expectedAdded: Long?, actualModified: Long, actualAdded: Long,
    trashed: Boolean, pending: Boolean,
) {
    check(expectedModified >= 0 && (expectedAdded == null || expectedAdded >= 0)) { "Invalid source generation" }
    check(actualModified == expectedModified && !trashed && !pending) { "Source unavailable or changed" }
    check(expectedAdded == null || actualAdded == expectedAdded) { "Source replaced" }
}
data class CreationGifRequest(val sources: List<CreationGifSource>, val frameTiming: Int = GifFrameTiming.Default) {
    init {
        require(sources.size in 2..60 && GifFrameTiming.isValid(frameTiming))
        require(sources.all { it.uri.scheme in setOf("file", "content") })
    }
}

/** Sources are immutable inputs. Publication happens only after decoding and source revalidation. */
class CreationGifExporter(
    private val context: Context,
    private val publicationJournal: CreationGifPublicationJournal = CreationGifPublicationJournal(
        File(context.noBackupFilesDir.canonicalFile, "gif-publications")),
) {
    private val resolver = context.contentResolver

    suspend fun export(request: CreationGifRequest, onProgress: (Int) -> Unit = {},
        onPublished: (Uri) -> Unit = {}): Uri = withTimeout(180_000) {
        withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()
            val dir = File(context.cacheDir, "creation-gif-${UUID.randomUUID()}")
            check(dir.mkdir())
            var pending: Uri? = null
            var committed = false
            var bitmap: Bitmap? = null
            try {
                val hashes = mutableListOf<String>()
                var total = 0L
                val gif = File(dir, "animation.gif")
                gif.outputStream().buffered().use { out ->
                    GifEncoder.encode(512, 512, request.sources.size, out, frameAt = { index ->
                        job.ensureActive()
                        bitmap?.recycle()
                        bitmap = null
                        val source = request.sources[index]
                        verify(source)
                        val snapshot = File(dir, "source")
                        resolver.openInputStream(source.uri)!!.use { input ->
                            snapshot.outputStream().use { output ->
                                val bytes = ByteArray(65536)
                                var count = 0L
                                while (true) {
                                    job.ensureActive()
                                    val n = input.read(bytes)
                                    if (n < 0) break
                                    count += n; total += n
                                    check(count <= 128L * 1024 * 1024 && total <= 512L * 1024 * 1024)
                                    output.write(bytes, 0, n)
                                }
                            }
                        }
                        verify(source)
                        hashes += snapshot.inputStream().use { sha256(it) { job.ensureActive() } }
                        bitmap = decodeFrame(snapshot)
                        snapshot.delete()
                        onProgress(index * 75 / request.sources.size)
                        GifEncoder.GifFrame(bitmap!!, GifFrameTiming.delaysMillis(request.frameTiming, index + 1)[index])
                    }, checkpoint = { job.ensureActive() })
                }
                bitmap?.recycle(); bitmap = null
                validate(gif, GifFrameTiming.totalMillis(request.frameTiming, request.sources.size))
                val outputHash = gif.inputStream().use { sha256(it) { job.ensureActive() } }
                request.sources.forEachIndexed { index, source ->
                    job.ensureActive()
                    verify(source)
                    val hash = resolver.openInputStream(source.uri)!!.use { sha256(it) { job.ensureActive() } }
                    check(hash == hashes[index]) { "Source changed" }
                    verify(source)
                }
                onProgress(85)
                pending = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "Lightforge-GIF-${UUID.randomUUID()}.gif")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/gif")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Lightforge/GIF/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }) ?: error("No output destination")
                onProgress(90)
                resolver.openOutputStream(pending!!, "w")!!.use { output ->
                    gif.inputStream().use { input ->
                        val bytes = ByteArray(65536)
                        while (true) {
                            job.ensureActive()
                            val n = input.read(bytes)
                            if (n < 0) break
                            output.write(bytes, 0, n)
                        }
                    }
                }
                val writtenHash = resolver.openInputStream(pending!!)!!.use { sha256(it) { job.ensureActive() } }
                check(outputHash == writtenHash)
                request.sources.forEach(::verify)
                job.ensureActive()
                check(resolver.update(pending!!, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null) == 1)
                committed = true
                // Retained UI state receives the URI even if cancellation races with dispatcher return.
                onPublished(pending!!)
                onProgress(100)
                pending!!
            } finally {
                bitmap?.recycle()
                if (!committed) pending?.let { runCatching { resolver.delete(it, null, null) } }
                dir.deleteRecursively()
            }
        }
    }

    /** The receipt retains all draft identities, but hashes only the frames indexed by order. */
    suspend fun export(sessionId: String, request: CreationGifRequest, order: List<Int> = request.sources.indices.toList(),
        onProgress: (Int) -> Unit = {}, onPublished: (Uri) -> Unit = {}): Uri =
        publicationJournal.withPublicationLock(sessionId) { exportDurableUnlocked(sessionId, request, order, onProgress, onPublished) }

    private suspend fun exportDurableUnlocked(
        sessionId: String,
        request: CreationGifRequest,
        order: List<Int> = request.sources.indices.toList(),
        onProgress: (Int) -> Unit = {},
        onPublished: (Uri) -> Unit = {},
    ): Uri = withTimeout(180_000) {
        withContext(Dispatchers.IO) {
            val sources = request.sources.toList()
            val checkedOrder = order.toList()
            val identities = sources.map { it.identity }
            require(identities.distinct().size == sources.size)
            require(checkedOrder.size in 2..sources.size && checkedOrder.distinct().size == checkedOrder.size)
            require(checkedOrder.all { it in sources.indices })
            val orderedSources = checkedOrder.map { sources[it] }
            val existing = publicationJournal.read(sessionId)
            if (existing != null) {
                check(existing.sourceIdentities == identities && existing.order == checkedOrder &&
                    existing.frameTiming == request.frameTiming) { "This session belongs to another publication" }
                val recovered = reconcileReceipt(existing)
                check(recovered.status == CreationGifPublicationStatus.Published) { "Resolve the existing publication before exporting" }
                val result = Uri.parse(requireNotNull(recovered.resultUri))
                onPublished(result); onProgress(100)
                return@withContext result
            }
            val job = currentCoroutineContext()
            val dir = File(context.cacheDir, "creation-gif-${UUID.randomUUID()}")
            check(dir.mkdir())
            var bitmap: Bitmap? = null
            try {
                val hashes = mutableListOf<String>()
                var total = 0L
                val gif = File(dir, "animation.gif")
                gif.outputStream().use { fileOutput ->
                    val out = fileOutput.buffered()
                    GifEncoder.encode(512, 512, orderedSources.size, out, frameAt = { index ->
                        job.ensureActive()
                        bitmap?.recycle()
                        bitmap = null
                        val source = orderedSources[index]
                        verify(source)
                        val snapshot = File(dir, "source")
                        resolver.openInputStream(source.uri)!!.use { input ->
                            snapshot.outputStream().use { output ->
                                val bytes = ByteArray(65536)
                                var count = 0L
                                while (true) {
                                    job.ensureActive()
                                    val n = input.read(bytes)
                                    if (n < 0) break
                                    count += n; total += n
                                    check(count <= 128L * 1024 * 1024 && total <= 512L * 1024 * 1024)
                                    output.write(bytes, 0, n)
                                }
                            }
                        }
                        verify(source)
                        hashes += snapshot.inputStream().use { sha256(it) { job.ensureActive() } }
                        bitmap = decodeFrame(snapshot)
                        snapshot.delete()
                        onProgress(index * 75 / orderedSources.size)
                        GifEncoder.GifFrame(bitmap!!, GifFrameTiming.delaysMillis(request.frameTiming, index + 1)[index])
                    }, checkpoint = { job.ensureActive() })
                    out.flush(); fileOutput.fd.sync()
                }
                bitmap?.recycle(); bitmap = null
                validate(gif, GifFrameTiming.totalMillis(request.frameTiming, orderedSources.size))
                val outputHash = gif.inputStream().use { sha256(it) { job.ensureActive() } }
                orderedSources.forEachIndexed { index, source ->
                    job.ensureActive()
                    verify(source)
                    val hash = resolver.openInputStream(source.uri)!!.use { sha256(it) { job.ensureActive() } }
                    check(hash == hashes[index]) { "Source changed" }
                    verify(source)
                }
                val initial = CreationGifPublicationReceipt.validatedCopy(CreationGifPublicationReceipt(
                    sessionId, UUID.randomUUID().toString(), identities, hashes, checkedOrder,
                    request.frameTiming, outputHash, gif.length()))
                onProgress(85); job.ensureActive()
                publicationJournal.begin(initial) // Every fsync completes BEFORE any MediaStore insert.
                job.ensureActive()
                val destination = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "Lightforge-GIF-${initial.token}.gif")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/gif")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Lightforge/GIF/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }) ?: error("No output destination")
                // Insert-to-receipt failure retains Intent; never search by name or delete a guessed URI.
                val insertedDestination = checkNotNull(publicationMetadata(destination))
                val inserted = CreationGifPublicationReceipt.validatedCopy(initial.copy(
                    destination = insertedDestination, phase = CreationGifPublicationPhase.Inserted))
                check(insertedDestination.ownerPackage == context.packageName)
                publicationJournal.advance(initial, inserted)
                onProgress(90)
                resolver.openFileDescriptor(destination, "w")!!.use { descriptor ->
                    android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                        gif.inputStream().use { input ->
                            val bytes = ByteArray(65536)
                            while (true) {
                                job.ensureActive(); val count = input.read(bytes)
                                if (count < 0) break
                                output.write(bytes, 0, count)
                            }
                        }
                        output.fd.sync()
                    }
                }
                val writtenDestination = checkNotNull(publicationMetadata(destination))
                check(sameDestination(insertedDestination, writtenDestination) && writtenDestination.pending && !writtenDestination.trashed)
                check(writtenDestination.generationModified >= insertedDestination.generationModified)
                val ready = CreationGifPublicationReceipt.validatedCopy(inserted.copy(
                    destination = writtenDestination, phase = CreationGifPublicationPhase.Ready))
                verifyPublicationBytes(ready, writtenDestination)
                orderedSources.forEachIndexed { index, source ->
                    job.ensureActive(); verify(source)
                    check(resolver.openInputStream(source.uri)!!.use { sha256(it) { job.ensureActive() } } == hashes[index]) { "Source changed" }
                    verify(source)
                }
                job.ensureActive(); publicationJournal.advance(inserted, ready); job.ensureActive()
                check(resolver.update(destination, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                        "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.GENERATION_ADDED}=? AND " +
                        "${MediaStore.MediaColumns.GENERATION_MODIFIED}=? AND ${MediaStore.MediaColumns.IS_PENDING}=1 AND ${MediaStore.MediaColumns.IS_TRASHED}=0",
                    arrayOf(writtenDestination.ownerPackage, writtenDestination.displayName, writtenDestination.relativePath,
                        writtenDestination.generationAdded.toString(), writtenDestination.generationModified.toString())) == 1)
                // Cancellation, journal fsync and callback errors after this point NEVER delete the GIF.
                val recovered = reconcileReceipt(ready)
                check(recovered.status == CreationGifPublicationStatus.Published)
                val published = requireNotNull(recovered.receipt)
                publicationJournal.advance(ready, published)
                CreationGifCommitProbe.afterCommit(context, published)
                onPublished(destination); onProgress(100)
                destination
            } finally {
                bitmap?.recycle()
                dir.deleteRecursively() // Only this invocation's private rendering scratch directory.
            }
        }
    }

    /** Includes corrupt/nonregular entries as known intent; an I/O error is never interpreted as Missing. */
    suspend fun hasPublication(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        publicationJournal.hasEntry(sessionId)
    }

    /** Does not reopen originals, write the marker, start a render, or mutate MediaStore. */
    suspend fun reconcile(sessionId: String): CreationGifPublicationRecovery = withTimeout(20_000) {
        withContext(Dispatchers.IO) {
            val receipt = publicationJournal.read(sessionId)
                ?: return@withContext CreationGifPublicationRecovery(CreationGifPublicationStatus.Missing)
            reconcileReceipt(receipt)
        }
    }

    /** A verified static first frame; the caller owns the returned bitmap. Never opens originals or starts playback. */
    suspend fun loadVerifiedResultPreview(expected: CreationGifPublicationReceipt): Bitmap? {
        var owned: Bitmap? = null
        var delivered = false
        try {
            return withTimeout(20_000) {
                withContext(Dispatchers.IO) {
                    val checked = CreationGifPublicationReceipt.validatedCopy(expected)
                    require(checked.phase == CreationGifPublicationPhase.Published)
                    val destination = requireNotNull(checked.destination)
                    val stored = publicationJournal.read(checked.sessionId) ?: return@withContext null
                    // Ready may be the last durable phase after commit; compare its full request and original destination anchor.
                    if (stored.phase !in listOf(CreationGifPublicationPhase.Ready, CreationGifPublicationPhase.Published) ||
                        !stored.sameRequest(checked)) return@withContext null
                    val prior = requireNotNull(stored.destination)
                    if (!sameDestination(prior, destination) || destination.generationModified < prior.generationModified ||
                        (stored.phase == CreationGifPublicationPhase.Published && stored != checked)) return@withContext null
                    val uri = Uri.parse(destination.uri)
                    if (publicationMetadata(uri) != destination) return@withContext null
                    val bytes = readVerifiedPublicationBytes(checked, destination)
                    currentCoroutineContext().ensureActive()
                    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                        check(info.size.width == 512 && info.size.height == 512)
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                    owned = bitmap
                    check(bitmap.width == 512 && bitmap.height == 512)
                    if (publicationJournal.read(checked.sessionId) != stored || publicationMetadata(uri) != destination)
                        return@withContext null
                    // Re-read exact bytes after decode, not only provider generations; metadata and marker must remain identical.
                    verifyPublicationBytes(checked, destination)
                    if (publicationJournal.read(checked.sessionId) != stored || publicationMetadata(uri) != destination)
                        return@withContext null
                    currentCoroutineContext().ensureActive()
                    bitmap
                }
            }.also { delivered = it != null }
        } finally {
            if (!delivered) owned?.recycle() // Includes cancellation during the dispatcher return.
        }
    }

    /** Explicit resolved-journal retirement only. It never deletes the public result or an incomplete receipt. */
    suspend fun retirePublication(expected: CreationGifPublicationReceipt): Boolean =
        publicationJournal.tryWithPublicationLock(expected.sessionId, { false }) { retirePublicationUnlocked(expected) }

    private suspend fun retirePublicationUnlocked(expected: CreationGifPublicationReceipt): Boolean = withTimeout(20_000) {
        withContext(Dispatchers.IO) {
            val stored = publicationJournal.read(expected.sessionId) ?: return@withContext false
            val recovered = reconcileReceipt(stored)
            if (recovered.status != CreationGifPublicationStatus.Published || recovered.receipt != expected) return@withContext false
            publicationJournal.retire(stored)
        }
    }

    suspend fun listPublications(): List<CreationGifPublicationEntry> = withContext(Dispatchers.IO) { publicationJournal.listEntries() }

    suspend fun inspectResolution(entry: CreationGifPublicationEntry): CreationGifPublicationResolution = withContext(Dispatchers.IO) {
        if (entry.unreadable) return@withContext CreationGifPublicationResolution(entry)
        publicationJournal.tryWithPublicationLock(entry.id, { CreationGifPublicationResolution(entry, busy = true) }) {
            inspectResolutionUnlocked(entry)
        }
    }

    private suspend fun inspectResolutionUnlocked(expected: CreationGifPublicationEntry): CreationGifPublicationResolution {
        val entry = publicationJournal.readEntry(expected.id)
        if (entry != expected || entry.unreadable || entry.receipt == null || entry.journalSha256 == null)
            return CreationGifPublicationResolution(entry)
        val receipt = entry.receipt
        try {
            val recovery = reconcileReceipt(receipt)
            if (recovery.status != CreationGifPublicationStatus.Incomplete || receipt.destination == null ||
                receipt.phase !in listOf(CreationGifPublicationPhase.Inserted, CreationGifPublicationPhase.Ready)) {
                if (publicationJournal.readEntry(entry.id) != entry) return CreationGifPublicationResolution(publicationJournal.readEntry(entry.id))
                return CreationGifPublicationResolution(entry, recovery, canForget = true)
            }
            val original = receipt.destination
            val current = publicationMetadata(Uri.parse(original.uri)) ?: return CreationGifPublicationResolution(entry, recovery)
            if (!sameDestination(original, current) || !current.pending || current.trashed || current.generationModified < original.generationModified)
                return CreationGifPublicationResolution(entry, recovery, canForget = true)
            val bytes = inspectPendingBytes(current, receipt.renderSizeBytes)
            var complete = receipt.phase == CreationGifPublicationPhase.Ready && current == original && !bytes.missing &&
                bytes.size == receipt.renderSizeBytes && bytes.sha256 == receipt.renderSha256
            if (complete) {
                try { verifyPublicationBytes(receipt, current) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: IllegalStateException) { complete = false }
                catch (_: IllegalArgumentException) { complete = false }
            }
            if (publicationMetadata(Uri.parse(current.uri)) != current || publicationJournal.readEntry(entry.id) != entry)
                return CreationGifPublicationResolution(publicationJournal.readEntry(entry.id))
            return CreationGifPublicationResolution(entry, recovery, pendingDestination = current, pendingBytesSha256 = bytes.sha256,
                pendingBytesSize = bytes.size, backingFileMissing = bytes.missing, canComplete = complete,
                canRemovePending = true, canForget = true)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { return CreationGifPublicationResolution(entry.copy(unreadable = true)) }
    }

    suspend fun completePublication(expected: CreationGifPublicationResolution): Boolean = withContext(Dispatchers.IO) {
        publicationJournal.tryWithPublicationLock(expected.entry.id, { false }) {
            val fresh = inspectResolutionUnlocked(expected.entry)
            if (fresh != expected || !fresh.canComplete) return@tryWithPublicationLock false
            val receipt = checkNotNull(fresh.entry.receipt)
            val current = checkNotNull(fresh.pendingDestination)
            val uri = Uri.parse(current.uri)
            if (resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    pendingSelection(), pendingArguments(current)) != 1) return@tryWithPublicationLock false
            val recovered = reconcileReceipt(receipt)
            if (recovered.status != CreationGifPublicationStatus.Published) return@tryWithPublicationLock false
            val published = checkNotNull(recovered.receipt)
            publicationJournal.advance(receipt, published)
            CreationGifCommitProbe.afterCommit(context, published)
            true
        }
    }

    suspend fun removePendingPublication(expected: CreationGifPublicationResolution): Boolean = withContext(Dispatchers.IO) {
        publicationJournal.tryWithPublicationLock(expected.entry.id, { false }) {
            val fresh = inspectResolutionUnlocked(expected.entry)
            if (fresh != expected || !fresh.canRemovePending) return@tryWithPublicationLock false
            val current = checkNotNull(fresh.pendingDestination)
            val uri = Uri.parse(current.uri)
            if (resolver.delete(uri, pendingSelection(), pendingArguments(current)) != 1) return@tryWithPublicationLock false
            val absence = checkNotNull(resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)) {
                "Pending output absence unavailable"
            }.use { !it.moveToFirst() }
            if (!absence) return@tryWithPublicationLock false
            publicationJournal.forgetEntry(fresh.entry)
        }
    }

    suspend fun forgetPublication(expected: CreationGifPublicationResolution): Boolean = withContext(Dispatchers.IO) {
        publicationJournal.tryWithPublicationLock(expected.entry.id, { false }) {
            val fresh = inspectResolutionUnlocked(expected.entry)
            if (fresh != expected || !fresh.canForget) return@tryWithPublicationLock false
            publicationJournal.forgetEntry(fresh.entry)
        }
    }

    private fun pendingSelection(): String = "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
        "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.MIME_TYPE}=? AND ${MediaStore.MediaColumns.GENERATION_ADDED}=? AND " +
        "${MediaStore.MediaColumns.GENERATION_MODIFIED}=? AND COALESCE(${MediaStore.MediaColumns.SIZE},0)=CAST(? AS INTEGER) AND " +
        "${MediaStore.MediaColumns.IS_PENDING}=1 AND ${MediaStore.MediaColumns.IS_TRASHED}=0"

    private fun pendingArguments(value: CreationGifPublicationDestination): Array<String> = arrayOf(value.ownerPackage,
        value.displayName, value.relativePath, value.mimeType, value.generationAdded.toString(), value.generationModified.toString(), value.sizeBytes.toString())

    private data class PendingBytes(val sha256: String?, val size: Long?, val missing: Boolean)

    private suspend fun inspectPendingBytes(value: CreationGifPublicationDestination, limit: Long): PendingBytes {
        val uri = Uri.parse(value.uri)
        check(value.pending && !value.trashed && publicationMetadata(uri) == value)
        val result = try {
            val input = resolver.openInputStream(uri) ?: throw java.io.IOException("Pending stream unavailable")
            input.use {
                val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536); var size = 0L
                val job = currentCoroutineContext()
                while (true) {
                    job.ensureActive(); val count = it.read(buffer); if (count < 0) break
                    check(count <= limit - size) { "Pending bytes exceed reviewed render extent" }
                    size += count; hash.update(buffer, 0, count)
                }
                PendingBytes(hash.digest().joinToString("") { "%02x".format(it) }, size, false)
            }
        } catch (missing: java.io.FileNotFoundException) {
            if (!isGifPublicationBackingFileMissing(missing)) throw missing
            PendingBytes(null, null, true)
        }
        check(publicationMetadata(uri) == value)
        return result
    }

    private suspend fun reconcileReceipt(receipt: CreationGifPublicationReceipt): CreationGifPublicationRecovery {
        fun result(status: CreationGifPublicationStatus) = CreationGifPublicationRecovery(status, receipt)
        val expected = receipt.destination ?: return result(CreationGifPublicationStatus.Incomplete)
        val current = publicationMetadata(Uri.parse(expected.uri)) ?: return result(CreationGifPublicationStatus.Conflict)
        if (!sameDestination(expected, current) || current.trashed || current.generationModified < expected.generationModified)
            return result(CreationGifPublicationStatus.Conflict)
        if (receipt.phase == CreationGifPublicationPhase.Inserted)
            return result(if (current.pending) CreationGifPublicationStatus.Incomplete else CreationGifPublicationStatus.Conflict)
        if (current.pending) return result(if (receipt.phase == CreationGifPublicationPhase.Ready && current == expected)
            CreationGifPublicationStatus.Incomplete else CreationGifPublicationStatus.Conflict)
        if (current.sizeBytes != receipt.renderSizeBytes) return result(CreationGifPublicationStatus.Conflict)
        if (receipt.phase == CreationGifPublicationPhase.Published && current != expected)
            return result(CreationGifPublicationStatus.Conflict)
        try { verifyPublicationBytes(receipt, current) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IllegalStateException) { return result(CreationGifPublicationStatus.Conflict) }
        catch (_: IllegalArgumentException) { return result(CreationGifPublicationStatus.Conflict) }
        val verified = CreationGifPublicationReceipt.validatedCopy(receipt.copy(
            destination = current, phase = CreationGifPublicationPhase.Published))
        return CreationGifPublicationRecovery(CreationGifPublicationStatus.Published, verified, current.uri)
    }

    private fun publicationMetadata(uri: Uri): CreationGifPublicationDestination? {
        val cursor = resolver.query(uri, arrayOf(
        MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.GENERATION_ADDED, MediaStore.MediaColumns.GENERATION_MODIFIED,
        MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.IS_TRASHED,
    ), null, null, null) ?: throw java.io.IOException("Publication metadata query unavailable")
        return cursor.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val result = CreationGifPublicationDestination(uri.toString(), cursor.getString(0).orEmpty(),
            cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty(), cursor.getString(3).orEmpty(),
            cursor.getLong(4), cursor.getLong(5), cursor.getLong(6), cursor.getInt(7) != 0, cursor.getInt(8) != 0)
        check(!cursor.moveToNext())
        result
    }
    }

    private fun sameDestination(left: CreationGifPublicationDestination, right: CreationGifPublicationDestination): Boolean =
        left.uri == right.uri && left.ownerPackage == right.ownerPackage && left.ownerPackage == context.packageName &&
            left.displayName == right.displayName && left.relativePath == right.relativePath &&
            left.mimeType == right.mimeType && left.generationAdded == right.generationAdded

    private suspend fun verifyPublicationBytes(receipt: CreationGifPublicationReceipt, metadata: CreationGifPublicationDestination) {
        readVerifiedPublicationBytes(receipt, metadata)
    }

    private suspend fun readVerifiedPublicationBytes(
        receipt: CreationGifPublicationReceipt,
        metadata: CreationGifPublicationDestination,
    ): ByteArray {
        val job = currentCoroutineContext()
        val uri = Uri.parse(metadata.uri)
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(65536)
            while (true) {
                job.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                check(count <= receipt.renderSizeBytes - output.size())
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("Published bytes unavailable")
        check(bytes.size.toLong() == receipt.renderSizeBytes && sha256(bytes.inputStream()) == receipt.renderSha256)
        @Suppress("DEPRECATION")
        val movie = Movie.decodeByteArray(bytes, 0, bytes.size) ?: error("GIF decoder rejected publication")
        check(movie.width() == 512 && movie.height() == 512 &&
            movie.duration() == GifFrameTiming.totalMillis(receipt.frameTiming, receipt.order.size))
        val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes)))
        check(drawable is android.graphics.drawable.AnimatedImageDrawable)
        drawable.stop()
        check(publicationMetadata(uri) == metadata) { "Publication changed during readback" }
        return bytes
    }

    internal fun verify(source: CreationGifSource) {
        check(source.expectedGenerationAdded == null || source.expectedGeneration != null) { "Incomplete source generation" }
        source.expectedGeneration?.let { expected ->
            check(source.uri.scheme == "content" && source.uri.authority == "media")
            resolver.query(source.uri, arrayOf(MediaStore.MediaColumns.GENERATION_MODIFIED,
                MediaStore.MediaColumns.GENERATION_ADDED, MediaStore.MediaColumns.IS_TRASHED,
                MediaStore.MediaColumns.IS_PENDING), null, null, null)?.use {
                check(it.moveToFirst()) { "Source unavailable" }
                requireCreationGifGeneration(expected, source.expectedGenerationAdded,
                    it.getLong(0), it.getLong(1), it.getInt(2) != 0, it.getInt(3) != 0)
                check(!it.moveToNext()) { "Ambiguous source" }
            } ?: error("Source unavailable")
        }
    }

    companion object {
        internal fun decodeFrame(file: File): Bitmap {
            val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val scale = minOf(1f, 512f / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            }
            return try {
                Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).also { frame ->
                    val canvas = Canvas(frame)
                    canvas.drawColor(Color.BLACK)
                    val scale = minOf(512f / decoded.width, 512f / decoded.height)
                    val w = decoded.width * scale; val h = decoded.height * scale
                    canvas.drawBitmap(decoded, null, RectF((512-w)/2, (512-h)/2, (512+w)/2, (512+h)/2), Paint(Paint.FILTER_BITMAP_FLAG))
                }
            } finally { decoded.recycle() }
        }
        @Suppress("DEPRECATION")
        internal fun validate(file: File, duration: Int) {
            val movie = Movie.decodeFile(file.absolutePath) ?: error("GIF decoder rejected output")
            check(movie.width() == 512 && movie.height() == 512 && movie.duration() == duration)
            val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))
            check(drawable is android.graphics.drawable.AnimatedImageDrawable)
            drawable.stop()
        }
        internal fun sha256(input: InputStream, checkpoint: () -> Unit = {}): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = ByteArray(65536)
            while (true) { checkpoint(); val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** MediaStore may strip the typed cause across Binder. Only its exact observed ENOENT spelling is accepted. */
internal fun isGifPublicationBackingFileMissing(failure: java.io.FileNotFoundException): Boolean {
    if (failure.message == "open failed: ENOENT (No such file or directory)") return true
    var cause: Throwable? = failure.cause
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    while (cause != null && seen.add(cause)) {
        if (cause is android.system.ErrnoException && cause.errno == android.system.OsConstants.ENOENT) return true
        cause = cause.cause
    }
    return false
}
