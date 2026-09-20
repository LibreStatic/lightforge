package com.ugallery.feature.collage

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

class CreationCollagePrepared internal constructor(
    internal val directory: File,
    val sources: List<CreationCollageSource>,
    internal val hashes: List<String>,
) {
    internal fun snapshot(index: Int) = File(directory, "source-$index")
    /** This object owns only its newly created UUID directory, never caller source files. */
    fun close() { directory.deleteRecursively() }
}

class CreationCollageRender internal constructor(
    internal val prepared: CreationCollagePrepared,
    val layout: CreationCollageLayout,
    val file: File,
    val sha256: String,
)

/** The PNG shown by the preview is the exact file published, not a second lossy render. */
class CreationCollageExporter(
    private val context: Context,
    private val publicationJournal: CreationCollagePublicationJournal = CreationCollagePublicationJournal(
        File(context.noBackupFilesDir.canonicalFile, "collage-publications")),
) {
    private val resolver = context.contentResolver

    suspend fun prepare(sources: List<CreationCollageSource>): CreationCollagePrepared {
        var ownedDirectory: File? = null
        var returned = false
        try {
            return withTimeout(180_000) {
        withContext(Dispatchers.IO) {
            require(CreationCollageTemplate.forCount(sources.size).isNotEmpty())
            require(sources.map { it.uri }.distinct().size == sources.size)
            val directory = File(context.cacheDir, "creation-collage-${UUID.randomUUID()}")
            check(directory.mkdir())
            ownedDirectory = directory
            val hashes = mutableListOf<String>()
            val job = currentCoroutineContext()
            try {
                sources.forEachIndexed { index, source ->
                    job.ensureActive(); verify(source)
                    val target = File(directory, "source-$index")
                    resolver.openInputStream(source.uri)?.use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(65536)
                            var count = 0L
                            while (true) {
                                job.ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                count += n; check(count <= MaximumSourceBytes) { "Source exceeds local limit" }
                                output.write(buffer, 0, n)
                            }
                            output.fd.sync()
                        }
                    } ?: error("Source unavailable")
                    verify(source)
                    hashes += target.inputStream().use { digest(it) { job.ensureActive() } }
                }
                CreationCollagePrepared(directory, sources.toList(), hashes).also { revalidate(it) }
            } catch (failure: Throwable) {
                directory.deleteRecursively()
                throw failure
            }
        }
            }.also { returned = true }
        } finally { if (!returned) ownedDirectory?.deleteRecursively() }
    }

    suspend fun render(prepared: CreationCollagePrepared, layout: CreationCollageLayout): CreationCollageRender {
        var ownedOutput: File? = null
        var returned = false
        try {
            return withContext(Dispatchers.IO) {
        require(layout.order.size == prepared.sources.size)
        revalidate(prepared)
        val job = currentCoroutineContext()
        val output = Bitmap.createBitmap(OutputSize, OutputSize, Bitmap.Config.ARGB_8888)
        val target = File(prepared.directory, "render-${UUID.randomUUID()}.png")
        ownedOutput = target
        var accepted = false
        try {
            val canvas = Canvas(output)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            layout.template.slots().forEachIndexed { slotIndex, slot ->
                job.ensureActive()
                val sourceIndex = layout.order[slotIndex]
                val bitmap = decode(prepared.snapshot(sourceIndex), OutputSize)
                try {
                    drawSlot(canvas, bitmap, slot, layout.crops[sourceIndex], paint)
                } finally { bitmap.recycle() }
            }
            target.outputStream().use { stream ->
                check(output.compress(Bitmap.CompressFormat.PNG, 100, stream))
                stream.fd.sync()
            }
            prepared.sources.forEach(::verify)
            job.ensureActive()
            val hash = target.inputStream().use { digest(it) { job.ensureActive() } }
            accepted = true
            CreationCollageRender(prepared, layout, target, hash)
        } finally {
            output.recycle()
            if (!accepted) target.delete()
        }
    }

        .also { returned = true }
        } finally { if (!returned) ownedOutput?.delete() }
    }

    suspend fun publish(render: CreationCollageRender, onProgress: (Int) -> Unit = {}, onPublished: (Uri) -> Unit = {}): Uri = withTimeout(180_000) {
        withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()
            val prepared = render.prepared
            revalidate(prepared)
            check(render.file.parentFile == prepared.directory && render.file.isFile)
            check(render.file.inputStream().use { digest(it) { job.ensureActive() } } == render.sha256)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(render.file.path, bounds)
            check(bounds.outWidth == OutputSize && bounds.outHeight == OutputSize && bounds.outMimeType == "image/png")
            var pending: Uri? = null
            var committed = false
            try {
                job.ensureActive()
                val destination = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "UGallery-collage-${UUID.randomUUID()}.png")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/UGallery/Collage/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }) ?: error("Output unavailable")
                pending = destination
                onProgress(90)
                resolver.openFileDescriptor(destination, "w")!!.use { descriptor ->
                    android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                        render.file.inputStream().use { input ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                job.ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                            }
                        }
                        output.fd.sync()
                    }
                }
                check(resolver.openInputStream(destination)!!.use { digest(it) { job.ensureActive() } } == render.sha256)
                // Revocation or a source mutation during destination writing must leave no publication.
                revalidate(prepared)
                job.ensureActive()
                check(resolver.update(destination, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
                committed = true
                onPublished(destination)
                onProgress(100)
                destination
            } finally {
                if (!committed) pending?.let { runCatching { resolver.delete(it, null, null) } }
            }
        }
    }

    /** Durable publication path. No automatic cleanup/retry can delete or duplicate a possible commit. */
    suspend fun publish(sessionId: String, render: CreationCollageRender, onProgress: (Int) -> Unit = {}, onPublished: (Uri) -> Unit = {}): Uri =
        publicationJournal.withPublicationLock(sessionId) { publishDurableUnlocked(sessionId, render, onProgress, onPublished) }

    private suspend fun publishDurableUnlocked(
        sessionId: String,
        render: CreationCollageRender,
        onProgress: (Int) -> Unit = {},
        onPublished: (Uri) -> Unit = {},
    ): Uri = withTimeout(180_000) {
        withContext(Dispatchers.IO) {
            val existing = publicationJournal.read(sessionId)
            val prepared = render.prepared
            val initial = CreationCollagePublicationReceipt.validatedCopy(CreationCollagePublicationReceipt(
                sessionId, existing?.token ?: UUID.randomUUID().toString(),
                prepared.sources.map { it.identity }, prepared.hashes, render.layout, render.sha256, render.file.length(),
            ))
            if (existing != null) {
                check(existing.sameRequest(initial)) { "This session belongs to another publication" }
                val recovered = reconcileReceipt(existing)
                check(recovered.status == CreationCollagePublicationStatus.Published) { "Resolve the existing publication before exporting" }
                val result = Uri.parse(requireNotNull(recovered.resultUri))
                onPublished(result); onProgress(100)
                return@withContext result
            }
            val job = currentCoroutineContext()
            revalidate(prepared)
            check(render.file.parentFile == prepared.directory && render.file.isFile)
            check(render.file.inputStream().use { digest(it) { job.ensureActive() } } == render.sha256)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(render.file.path, bounds)
            check(bounds.outWidth == OutputSize && bounds.outHeight == OutputSize && bounds.outMimeType == "image/png")
            job.ensureActive()
            publicationJournal.begin(initial) // Must finish every fsync before any provider mutation.
            job.ensureActive()
            val destination = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "UGallery-collage-${initial.token}.png")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/UGallery/Collage/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: error("Output unavailable")
            // If the process/fsync fails here, Intent records ambiguity. Never guess the URI by name.
            val insertedDestination = checkNotNull(publicationMetadata(destination))
            val inserted = CreationCollagePublicationReceipt.validatedCopy(initial.copy(
                destination = insertedDestination, phase = CreationCollagePublicationPhase.Inserted))
            check(insertedDestination.ownerPackage == context.packageName)
            publicationJournal.advance(initial, inserted)
            onProgress(90)
            resolver.openFileDescriptor(destination, "w")!!.use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                    render.file.inputStream().use { input ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            job.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                        }
                    }
                    output.fd.sync()
                }
            }
            val writtenDestination = checkNotNull(publicationMetadata(destination))
            check(sameDestination(insertedDestination, writtenDestination) && writtenDestination.pending && !writtenDestination.trashed)
            check(writtenDestination.generationModified >= insertedDestination.generationModified)
            val ready = CreationCollagePublicationReceipt.validatedCopy(inserted.copy(
                destination = writtenDestination, phase = CreationCollagePublicationPhase.Ready))
            verifyPublicationBytes(ready, writtenDestination)
            revalidate(prepared)
            job.ensureActive()
            publicationJournal.advance(inserted, ready) // Durable hash + exact pending destination BEFORE the public transition.
            job.ensureActive()
            check(resolver.update(destination, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.GENERATION_ADDED}=? AND " +
                    "${MediaStore.MediaColumns.GENERATION_MODIFIED}=? AND ${MediaStore.MediaColumns.IS_PENDING}=1 AND ${MediaStore.MediaColumns.IS_TRASHED}=0",
                arrayOf(writtenDestination.ownerPackage, writtenDestination.displayName, writtenDestination.relativePath,
                    writtenDestination.generationAdded.toString(), writtenDestination.generationModified.toString())) == 1)
            // From this point cancellation/callback/fsync failure must leave the public image intact.
            val recovered = reconcileReceipt(ready)
            check(recovered.status == CreationCollagePublicationStatus.Published)
            val published = requireNotNull(recovered.receipt)
            publicationJournal.advance(ready, published)
            CreationCollageCommitProbe.afterCommit(context, published)
            onPublished(destination)
            onProgress(100)
            destination
        }
    }

    /** Includes corrupt/nonregular entries as known intent; an I/O error is never interpreted as Missing. */
    suspend fun hasPublication(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        publicationJournal.hasEntry(sessionId)
    }

    /** Does not reopen originals, write the marker, start a render, or mutate MediaStore. */
    suspend fun reconcile(sessionId: String): CreationCollagePublicationRecovery = withTimeout(20_000) {
        withContext(Dispatchers.IO) {
            val receipt = publicationJournal.read(sessionId)
                ?: return@withContext CreationCollagePublicationRecovery(CreationCollagePublicationStatus.Missing)
            reconcileReceipt(receipt)
        }
    }

    /** A verified static PNG preview; the caller owns the returned bitmap. Never opens originals or starts playback. */
    suspend fun loadVerifiedResultPreview(expected: CreationCollagePublicationReceipt): Bitmap? {
        var owned: Bitmap? = null
        var delivered = false
        try {
            return withTimeout(20_000) {
                withContext(Dispatchers.IO) {
                    val checked = CreationCollagePublicationReceipt.validatedCopy(expected)
                    require(checked.phase == CreationCollagePublicationPhase.Published)
                    val destination = requireNotNull(checked.destination)
                    val stored = publicationJournal.read(checked.sessionId) ?: return@withContext null
                    // Ready may be the last durable phase after commit; compare its full request and original destination anchor.
                    if (stored.phase !in listOf(CreationCollagePublicationPhase.Ready, CreationCollagePublicationPhase.Published) ||
                        !stored.sameRequest(checked)) return@withContext null
                    val prior = requireNotNull(stored.destination)
                    if (!sameDestination(prior, destination) || destination.generationModified < prior.generationModified ||
                        (stored.phase == CreationCollagePublicationPhase.Published && stored != checked)) return@withContext null
                    val uri = Uri.parse(destination.uri)
                    if (publicationMetadata(uri) != destination) return@withContext null
                    val bytes = readVerifiedPublicationBytes(checked, destination)
                    currentCoroutineContext().ensureActive()
                    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                        check(info.size.width == 2048 && info.size.height == 2048)
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                    owned = bitmap
                    check(bitmap.width == 2048 && bitmap.height == 2048)
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
    suspend fun retirePublication(expected: CreationCollagePublicationReceipt): Boolean =
        publicationJournal.tryWithPublicationLock(expected.sessionId, { false }) { retirePublicationUnlocked(expected) }

    private suspend fun retirePublicationUnlocked(expected: CreationCollagePublicationReceipt): Boolean = withTimeout(20_000) {
        withContext(Dispatchers.IO) {
            val stored = publicationJournal.read(expected.sessionId) ?: return@withContext false
            val recovered = reconcileReceipt(stored)
            if (recovered.status != CreationCollagePublicationStatus.Published || recovered.receipt != expected) return@withContext false
            publicationJournal.retire(stored)
        }
    }

    suspend fun listPublications(): List<CreationCollagePublicationEntry> = withContext(Dispatchers.IO) { publicationJournal.listEntries() }

    suspend fun inspectResolution(entry: CreationCollagePublicationEntry): CreationCollagePublicationResolution = withContext(Dispatchers.IO) {
        if (entry.unreadable) return@withContext CreationCollagePublicationResolution(entry)
        publicationJournal.tryWithPublicationLock(entry.id, { CreationCollagePublicationResolution(entry, busy = true) }) {
            inspectResolutionUnlocked(entry)
        }
    }

    private suspend fun inspectResolutionUnlocked(expected: CreationCollagePublicationEntry): CreationCollagePublicationResolution {
        val entry = publicationJournal.readEntry(expected.id)
        if (entry != expected || entry.unreadable || entry.receipt == null || entry.journalSha256 == null)
            return CreationCollagePublicationResolution(entry)
        val receipt = entry.receipt
        try {
            val recovery = reconcileReceipt(receipt)
            if (recovery.status != CreationCollagePublicationStatus.Incomplete || receipt.destination == null ||
                receipt.phase !in listOf(CreationCollagePublicationPhase.Inserted, CreationCollagePublicationPhase.Ready)) {
                if (publicationJournal.readEntry(entry.id) != entry) return CreationCollagePublicationResolution(publicationJournal.readEntry(entry.id))
                return CreationCollagePublicationResolution(entry, recovery, canForget = true)
            }
            val original = receipt.destination
            val current = publicationMetadata(Uri.parse(original.uri)) ?: return CreationCollagePublicationResolution(entry, recovery)
            if (!sameDestination(original, current) || !current.pending || current.trashed || current.generationModified < original.generationModified)
                return CreationCollagePublicationResolution(entry, recovery, canForget = true)
            val bytes = inspectPendingBytes(current, receipt.renderSizeBytes)
            var complete = receipt.phase == CreationCollagePublicationPhase.Ready && current == original && !bytes.missing &&
                bytes.size == receipt.renderSizeBytes && bytes.sha256 == receipt.renderSha256
            if (complete) {
                try { verifyPublicationBytes(receipt, current) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: IllegalStateException) { complete = false }
                catch (_: IllegalArgumentException) { complete = false }
            }
            if (publicationMetadata(Uri.parse(current.uri)) != current || publicationJournal.readEntry(entry.id) != entry)
                return CreationCollagePublicationResolution(publicationJournal.readEntry(entry.id))
            return CreationCollagePublicationResolution(entry, recovery, pendingDestination = current, pendingBytesSha256 = bytes.sha256,
                pendingBytesSize = bytes.size, backingFileMissing = bytes.missing, canComplete = complete,
                canRemovePending = true, canForget = true)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { return CreationCollagePublicationResolution(entry.copy(unreadable = true)) }
    }

    suspend fun completePublication(expected: CreationCollagePublicationResolution): Boolean = withContext(Dispatchers.IO) {
        publicationJournal.tryWithPublicationLock(expected.entry.id, { false }) {
            val fresh = inspectResolutionUnlocked(expected.entry)
            if (fresh != expected || !fresh.canComplete) return@tryWithPublicationLock false
            val receipt = checkNotNull(fresh.entry.receipt)
            val current = checkNotNull(fresh.pendingDestination)
            val uri = Uri.parse(current.uri)
            if (resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    pendingSelection(), pendingArguments(current)) != 1) return@tryWithPublicationLock false
            val recovered = reconcileReceipt(receipt)
            if (recovered.status != CreationCollagePublicationStatus.Published) return@tryWithPublicationLock false
            val published = checkNotNull(recovered.receipt)
            publicationJournal.advance(receipt, published)
            CreationCollageCommitProbe.afterCommit(context, published)
            true
        }
    }

    suspend fun removePendingPublication(expected: CreationCollagePublicationResolution): Boolean = withContext(Dispatchers.IO) {
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

    suspend fun forgetPublication(expected: CreationCollagePublicationResolution): Boolean = withContext(Dispatchers.IO) {
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

    private fun pendingArguments(value: CreationCollagePublicationDestination): Array<String> = arrayOf(value.ownerPackage,
        value.displayName, value.relativePath, value.mimeType, value.generationAdded.toString(), value.generationModified.toString(), value.sizeBytes.toString())

    private data class PendingBytes(val sha256: String?, val size: Long?, val missing: Boolean)

    private suspend fun inspectPendingBytes(value: CreationCollagePublicationDestination, limit: Long): PendingBytes {
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
            if (!isCollagePublicationBackingFileMissing(missing)) throw missing
            PendingBytes(null, null, true)
        }
        check(publicationMetadata(uri) == value)
        return result
    }

    private suspend fun reconcileReceipt(receipt: CreationCollagePublicationReceipt): CreationCollagePublicationRecovery {
        fun result(status: CreationCollagePublicationStatus) = CreationCollagePublicationRecovery(status, receipt)
        val expected = receipt.destination ?: return result(CreationCollagePublicationStatus.Incomplete)
        val current = publicationMetadata(Uri.parse(expected.uri)) ?: return result(CreationCollagePublicationStatus.Conflict)
        if (!sameDestination(expected, current) || current.trashed || current.generationModified < expected.generationModified)
            return result(CreationCollagePublicationStatus.Conflict)
        if (receipt.phase == CreationCollagePublicationPhase.Inserted)
            return result(if (current.pending) CreationCollagePublicationStatus.Incomplete else CreationCollagePublicationStatus.Conflict)
        if (current.pending) return result(if (receipt.phase == CreationCollagePublicationPhase.Ready && current == expected)
            CreationCollagePublicationStatus.Incomplete else CreationCollagePublicationStatus.Conflict)
        if (current.sizeBytes != receipt.renderSizeBytes) return result(CreationCollagePublicationStatus.Conflict)
        if (receipt.phase == CreationCollagePublicationPhase.Published && current != expected)
            return result(CreationCollagePublicationStatus.Conflict)
        try { verifyPublicationBytes(receipt, current) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IllegalStateException) { return result(CreationCollagePublicationStatus.Conflict) }
        catch (_: IllegalArgumentException) { return result(CreationCollagePublicationStatus.Conflict) }
        val verified = CreationCollagePublicationReceipt.validatedCopy(receipt.copy(
            destination = current, phase = CreationCollagePublicationPhase.Published))
        return CreationCollagePublicationRecovery(CreationCollagePublicationStatus.Published, verified, current.uri)
    }

    private fun publicationMetadata(uri: Uri): CreationCollagePublicationDestination? {
        val cursor = resolver.query(uri, arrayOf(
        MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.GENERATION_ADDED, MediaStore.MediaColumns.GENERATION_MODIFIED,
        MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.IS_TRASHED,
    ), null, null, null) ?: throw java.io.IOException("Publication metadata query unavailable")
        return cursor.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val result = CreationCollagePublicationDestination(uri.toString(), cursor.getString(0).orEmpty(),
            cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty(), cursor.getString(3).orEmpty(),
            cursor.getLong(4), cursor.getLong(5), cursor.getLong(6), cursor.getInt(7) != 0, cursor.getInt(8) != 0)
        check(!cursor.moveToNext())
        result
    }
    }

    private fun sameDestination(left: CreationCollagePublicationDestination, right: CreationCollagePublicationDestination): Boolean =
        left.uri == right.uri && left.ownerPackage == right.ownerPackage && left.ownerPackage == context.packageName &&
            left.displayName == right.displayName && left.relativePath == right.relativePath &&
            left.mimeType == right.mimeType && left.generationAdded == right.generationAdded

    private suspend fun verifyPublicationBytes(receipt: CreationCollagePublicationReceipt, metadata: CreationCollagePublicationDestination) {
        readVerifiedPublicationBytes(receipt, metadata)
    }

    private suspend fun readVerifiedPublicationBytes(receipt: CreationCollagePublicationReceipt, metadata: CreationCollagePublicationDestination): ByteArray {
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
        check(bytes.size.toLong() == receipt.renderSizeBytes && digest(bytes.inputStream()) == receipt.renderSha256)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        check(bounds.outWidth == OutputSize && bounds.outHeight == OutputSize && bounds.outMimeType == "image/png")
        check(publicationMetadata(uri) == metadata) { "Publication changed during readback" }
        return bytes
    }

    internal fun verify(source: CreationCollageSource) {
        source.expectedGeneration?.let { expected ->
            resolver.query(source.uri, arrayOf(MediaStore.MediaColumns.GENERATION_MODIFIED,
                MediaStore.MediaColumns.GENERATION_ADDED, MediaStore.MediaColumns.IS_TRASHED,
                MediaStore.MediaColumns.IS_PENDING), null, null, null)?.use {
                check(it.moveToFirst() && it.getLong(0) == expected && it.getInt(2) == 0 && it.getInt(3) == 0) { "Source unavailable or changed" }
                source.expectedGenerationAdded?.let { added -> check(it.getLong(1) == added) { "Source replaced" } }
            } ?: error("Source unavailable")
        }
    }

    private suspend fun revalidate(prepared: CreationCollagePrepared) {
        val job = currentCoroutineContext()
        prepared.sources.forEachIndexed { index, source ->
            job.ensureActive(); verify(source)
            val actual = resolver.openInputStream(source.uri)?.use { digest(it) { job.ensureActive() } }
            check(actual == prepared.hashes[index]) { "Source changed or unavailable" }
            verify(source)
        }
    }

    companion object {
        const val OutputSize = 2048
        private const val MaximumSourceBytes = 128L * 1024 * 1024
        internal fun decode(file: File, size: Int): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = minOf(1f, size.toFloat() / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
        }
        internal fun digest(input: InputStream, checkpoint: () -> Unit = {}): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = ByteArray(65536)
            var count = 0L
            while (true) {
                checkpoint()
                val n = input.read(bytes)
                if (n < 0) break
                count += n; check(count <= MaximumSourceBytes) { "Source exceeds local limit" }
                digest.update(bytes, 0, n)
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        private fun drawSlot(canvas: Canvas, bitmap: Bitmap, slot: CollageSlot, crop: CreationCollageCrop, paint: Paint) {
            val destination = RectF(slot.x * OutputSize + 12f, slot.y * OutputSize + 12f,
                (slot.x + slot.width) * OutputSize - 12f, (slot.y + slot.height) * OutputSize - 12f)
            if (slot.padding > 0f) destination.inset(slot.padding, slot.padding)
            val aspect = destination.width() / destination.height()
            val baseWidth = minOf(bitmap.width.toFloat(), bitmap.height * aspect)
            val baseHeight = baseWidth / aspect
            val width = baseWidth / crop.zoom
            val height = baseHeight / crop.zoom
            val left = (bitmap.width - width) * (crop.horizontal + 1f) / 2f
            val top = (bitmap.height - height) * (crop.vertical + 1f) / 2f
            val x = left.toInt().coerceIn(0, bitmap.width - 1)
            val y = top.toInt().coerceIn(0, bitmap.height - 1)
            val source = Rect(x, y, (left + width).toInt().coerceIn(x + 1, bitmap.width),
                (top + height).toInt().coerceIn(y + 1, bitmap.height))
            canvas.save()
            canvas.rotate(slot.rotation, destination.centerX(), destination.centerY())
            if (slot.padding > 0) canvas.drawRect(destination.left - slot.padding, destination.top - slot.padding,
                destination.right + slot.padding, destination.bottom + slot.padding * 2, Paint().apply { color = Color.WHITE })
            canvas.drawBitmap(bitmap, source, destination, paint)
            canvas.restore()
        }
    }
}

/** MediaStore may strip the typed cause across Binder. Only its exact observed ENOENT spelling is accepted. */
internal fun isCollagePublicationBackingFileMissing(failure: java.io.FileNotFoundException): Boolean {
    if (failure.message == "open failed: ENOENT (No such file or directory)") return true
    var cause: Throwable? = failure.cause
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    while (cause != null && seen.add(cause)) {
        if (cause is android.system.ErrnoException && cause.errno == android.system.OsConstants.ENOENT) return true
        cause = cause.cause
    }
    return false
}
