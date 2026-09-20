package com.ugallery.feature.motionphotos

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.io.InputStream
import java.io.FileNotFoundException
import android.system.ErrnoException
import android.system.OsConstants
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*

/** Exact provider errno evidence only; neither a generic missing message nor _size=0 proves ENOENT. */
internal fun isMotionPublicationBackingFileMissing(failure: FileNotFoundException): Boolean =
    generateSequence<Throwable>(failure) { it.cause }.any { it is ErrnoException && it.errno == OsConstants.ENOENT } ||
        failure.message == "open failed: ENOENT (No such file or directory)"

/** Recovery is independent of MotionPhotoSession: public results never require reopening originals. */
class MotionPhotoPublication(
    private val context: Context,
    private val journal: MotionPhotoPublicationJournal = MotionPhotoPublicationJournal(
        File(context.noBackupFilesDir.canonicalFile, "motion-publications")),
) {
    private val resolver = context.contentResolver

    suspend fun hasPublication(publicationId: String): Boolean = withContext(Dispatchers.IO) { journal.hasEntry(publicationId) }

    suspend fun reconcile(publicationId: String): MotionPhotoPublicationRecovery = withTimeout(60_000) {
        withContext(Dispatchers.IO) {
            val stored = journal.read(publicationId)
                ?: return@withContext MotionPhotoPublicationRecovery(MotionPhotoPublicationStatus.Missing)
            reconcileReceipt(stored)
        }
    }

    suspend fun retire(expected: MotionPhotoPublicationReceipt): Boolean = journal.tryWithPublicationLock(expected.publicationId, { false }) {
        withTimeout(60_000) {
            withContext(Dispatchers.IO) {
                val stored = journal.read(expected.publicationId) ?: return@withContext false
                val recovered = reconcileReceipt(stored)
                if (recovered.status != MotionPhotoPublicationStatus.Published || recovered.receipt != expected) return@withContext false
                journal.retire(stored)
            }
        }
    }

    suspend fun listPublications(): List<MotionPhotoPublicationEntry> = withContext(Dispatchers.IO) { journal.listEntries() }

    suspend fun inspectResolution(entry: MotionPhotoPublicationEntry): MotionPhotoPublicationResolution =
        journal.tryWithPublicationLock(entry.id, { MotionPhotoPublicationResolution(entry, busy = true) }) {
            withTimeout(60_000) { withContext(Dispatchers.IO) { inspectResolutionUnlocked(entry) } }
        }

    suspend fun completePublication(expected: MotionPhotoPublicationResolution): Boolean =
        journal.tryWithPublicationLock(expected.entry.id, { false }) {
            withTimeout(60_000) { withContext(Dispatchers.IO) {
                if (!expected.canComplete || inspectResolutionUnlocked(expected.entry) != expected) return@withContext false
                val receipt = requireNotNull(expected.entry.receipt)
                val pending = requireNotNull(expected.pendingDestination)
                if (journal.readEntry(expected.entry.id) != expected.entry) return@withContext false
                val uri = Uri.parse(pending.uri)
                val updated = resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    SnapshotWhere, snapshotArguments(pending))
                if (updated != 1) return@withContext false
                val recovered = reconcileReceipt(receipt)
                if (recovered.status != MotionPhotoPublicationStatus.Published || journal.readEntry(expected.entry.id) != expected.entry)
                    return@withContext false
                journal.advance(receipt, requireNotNull(recovered.receipt))
                true
            } }
        }

    suspend fun removePendingPublication(expected: MotionPhotoPublicationResolution): Boolean =
        journal.tryWithPublicationLock(expected.entry.id, { false }) {
            withTimeout(60_000) { withContext(Dispatchers.IO) {
                if (!expected.canRemovePending || inspectResolutionUnlocked(expected.entry) != expected) return@withContext false
                val pending = requireNotNull(expected.pendingDestination)
                if (journal.readEntry(expected.entry.id) != expected.entry) return@withContext false
                val uri = Uri.parse(pending.uri)
                if (resolver.delete(uri, SnapshotWhere, snapshotArguments(pending)) != 1) return@withContext false
                if (publicationMetadata(uri, strictQuery = true) != null) return@withContext false
                journal.forget(expected.entry)
            } }
        }

    suspend fun forgetPublication(expected: MotionPhotoPublicationResolution): Boolean =
        journal.tryWithPublicationLock(expected.entry.id, { false }) {
            withContext(Dispatchers.IO) {
                expected.canForget && !expected.busy && journal.forget(expected.entry)
            }
        }

    private suspend fun inspectResolutionUnlocked(entry: MotionPhotoPublicationEntry): MotionPhotoPublicationResolution {
        val currentEntry = journal.readEntry(entry.id)
        if (currentEntry != entry || entry.unreadable || entry.receipt == null || entry.journalSha256 == null)
            return MotionPhotoPublicationResolution(currentEntry)
        val receipt = entry.receipt
        val recovery = try { reconcileReceipt(receipt) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { null }
        val base = MotionPhotoPublicationResolution(entry, recovery = recovery, canForget = true)
        if (recovery?.status != MotionPhotoPublicationStatus.Incomplete) return base
        val anchored = receipt.destination ?: return base
        if (receipt.phase !in listOf(MotionPhotoPublicationPhase.Inserted, MotionPhotoPublicationPhase.Ready)) return base
        val metadata = try { publicationMetadata(Uri.parse(anchored.uri), strictQuery = true) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { return base }
            ?: return base
        if (!sameDestination(anchored, metadata) || !metadata.pending || metadata.trashed || metadata.generationModified < anchored.generationModified)
            return base
        val observation = try { pendingBytes(metadata) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { return base }
        if (journal.readEntry(entry.id) != entry || publicationMetadata(Uri.parse(metadata.uri), strictQuery = true) != metadata)
            return MotionPhotoPublicationResolution(journal.readEntry(entry.id))
        var complete = receipt.phase == MotionPhotoPublicationPhase.Ready && metadata == anchored &&
            !observation.missing && observation.hash == receipt.renderSha256 && observation.size == receipt.renderSizeBytes
        if (complete) {
            complete = try { withVerifiedResultFile(receipt, metadata) { }; true }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { false }
        }
        if (journal.readEntry(entry.id) != entry || publicationMetadata(Uri.parse(metadata.uri), strictQuery = true) != metadata)
            return MotionPhotoPublicationResolution(journal.readEntry(entry.id))
        return base.copy(pendingDestination = metadata, pendingBytesSha256 = observation.hash,
            pendingBytesSize = observation.size, backingFileMissing = observation.missing,
            canComplete = complete, canRemovePending = true)
    }

    private data class PendingBytes(val hash: String? = null, val size: Long? = null, val missing: Boolean = false)

    /** _size NULL/0 is metadata, never evidence that a stream is empty or absent. */
    private suspend fun pendingBytes(metadata: MotionPhotoPublicationDestination): PendingBytes {
        val uri = Uri.parse(metadata.uri)
        val stream = try { resolver.openInputStream(uri) ?: error("Pending stream open returned no descriptor") }
        catch (failure: FileNotFoundException) {
            // Only opening the exact stream may establish absence. Mid-read or metadata-query errors do not.
            if (!isMotionPublicationBackingFileMissing(failure)) throw failure
            check(publicationMetadata(uri, strictQuery = true) == metadata)
            return PendingBytes(missing = true)
        }
        stream.use {
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536); var size = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = it.read(buffer); if (count < 0) break
                check(count.toLong() <= Long.MAX_VALUE - size); size += count
                digest.update(buffer, 0, count)
            }
            check(publicationMetadata(uri, strictQuery = true) == metadata)
            return PendingBytes(digest.digest().joinToString("") { byte -> "%02x".format(byte) }, size)
        }
    }

    private fun snapshotArguments(value: MotionPhotoPublicationDestination): Array<String> = arrayOf(
        value.ownerPackage, value.displayName, value.relativePath, value.mimeType, value.generationAdded.toString(),
        value.generationModified.toString(), value.sizeBytes.toString(), if (value.pending) "1" else "0", if (value.trashed) "1" else "0")

    /** A static preview; ownership transfers only after the final identity/hash checks and dispatcher return. */
    suspend fun loadVerifiedPreview(expected: MotionPhotoPublicationReceipt, maxDimension: Int = 1600): Bitmap? {
        require(maxDimension in 96..4096)
        var owned: Bitmap? = null
        var delivered = false
        try {
            return withTimeout(60_000) {
                withContext(Dispatchers.IO) {
                    val checked = MotionPhotoPublicationReceipt.validatedCopy(expected)
                    require(checked.phase == MotionPhotoPublicationPhase.Published)
                    val stored = journal.read(checked.publicationId) ?: return@withContext null
                    val metadata = requireNotNull(checked.destination)
                    if (!matchesPublished(stored, checked) || publicationMetadata(Uri.parse(metadata.uri)) != metadata)
                        return@withContext null
                    withVerifiedResultFile(checked, metadata) { file ->
                        currentCoroutineContext().ensureActive()
                        val bitmap = if (checked.kind == MotionPhotoPublicationKind.Frame) {
                            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                                val scale = minOf(1f, maxDimension.toFloat() / maxOf(info.size.width, info.size.height))
                                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1),
                                    (info.size.height * scale).toInt().coerceAtLeast(1))
                            }
                        } else {
                            val retriever = MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(file.absolutePath)
                                retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST, maxDimension, maxDimension)
                                    ?: error("Video preview is not decodable")
                            } finally { retriever.release() }
                        }
                        owned = bitmap
                        if (journal.read(checked.publicationId) != stored || publicationMetadata(Uri.parse(metadata.uri)) != metadata)
                            return@withVerifiedResultFile null
                        verifyResultStream(checked, metadata)
                        if (journal.read(checked.publicationId) != stored || publicationMetadata(Uri.parse(metadata.uri)) != metadata)
                            return@withVerifiedResultFile null
                        currentCoroutineContext().ensureActive()
                        bitmap
                    }
                }
            }.also { delivered = it != null }
        } finally { if (!delivered) owned?.recycle() }
    }

    internal suspend fun existingResult(
        publicationId: String, input: MotionPhotoInput, sourceSha256: String,
        kind: MotionPhotoPublicationKind, timeUs: Long?, durationUs: Long,
    ): Uri? = withContext(Dispatchers.IO) {
        val stored = journal.read(publicationId) ?: return@withContext null
        check(stored.sourceUri == input.uri.toString() && stored.generationModified == input.expectedGeneration &&
            stored.generationAdded == input.expectedGenerationAdded && stored.sourceIdentity == input.identity &&
            stored.sourceSha256 == sourceSha256 && stored.kind == kind && stored.selectedTimeUs == timeUs && stored.durationUs == durationUs) {
            "This publication belongs to another motion request"
        }
        val recovered = reconcileReceipt(stored)
        check(recovered.status == MotionPhotoPublicationStatus.Published) { "Resolve the existing publication before exporting" }
        Uri.parse(requireNotNull(recovered.resultUri))
    }

    internal suspend fun publish(
        publicationId: String, input: MotionPhotoInput, sourceSha256: String,
        kind: MotionPhotoPublicationKind, timeUs: Long?, durationUs: Long, file: File,
        onPublished: (Uri) -> Unit,
    ): Uri = journal.withPublicationLock(publicationId) {
        publishUnlocked(publicationId, input, sourceSha256, kind, timeUs, durationUs, file, onPublished)
    }

    private suspend fun publishUnlocked(
        publicationId: String, input: MotionPhotoInput, sourceSha256: String,
        kind: MotionPhotoPublicationKind, timeUs: Long?, durationUs: Long, file: File,
        onPublished: (Uri) -> Unit,
    ): Uri = withTimeout(180_000) {
        withContext(Dispatchers.IO) {
            existingResult(publicationId, input, sourceSha256, kind, timeUs, durationUs)?.let {
                onPublished(it); return@withContext it
            }
            val job = currentCoroutineContext()
            check(file.isFile)
            val size = file.length()
            val hash = file.inputStream().use { digest(it, size) }
            val initial = MotionPhotoPublicationReceipt.validatedCopy(MotionPhotoPublicationReceipt(
                publicationId, UUID.randomUUID().toString(), input.uri.toString(), input.expectedGeneration,
                input.expectedGenerationAdded, input.identity, sourceSha256, kind, timeUs, durationUs, hash, size))
            validateResultFile(initial, file)
            verifyOriginal(input, sourceSha256)
            job.ensureActive(); journal.begin(initial); job.ensureActive()
            val video = kind == MotionPhotoPublicationKind.Clip
            val destination = resolver.insert(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "UGallery-Motion-${initial.token}.${if (video) "mp4" else "jpg"}")
                    put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, if (video) "Movies/UGallery/Motion/" else "Pictures/UGallery/Motion/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }) ?: error("Pending creation failed")
            // Insert-to-journal failure deliberately retains Intent with unknown destination; never adopt by name.
            val insertedMetadata = checkNotNull(publicationMetadata(destination))
            val inserted = MotionPhotoPublicationReceipt.validatedCopy(initial.copy(
                destination = insertedMetadata, phase = MotionPhotoPublicationPhase.Inserted))
            check(insertedMetadata.ownerPackage == context.packageName)
            journal.advance(initial, inserted)
            resolver.openFileDescriptor(destination, "w")!!.use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                    file.inputStream().use { stream ->
                        val bytes = ByteArray(65536); var count = 0L
                        while (true) {
                            job.ensureActive(); val n = stream.read(bytes); if (n < 0) break
                            check(n <= size - count); count += n; output.write(bytes, 0, n)
                        }
                        check(count == size)
                    }
                    output.fd.sync()
                }
            }
            val written = checkNotNull(publicationMetadata(destination))
            check(sameDestination(insertedMetadata, written) && written.pending && !written.trashed &&
                written.generationModified >= insertedMetadata.generationModified)
            val ready = MotionPhotoPublicationReceipt.validatedCopy(inserted.copy(destination = written, phase = MotionPhotoPublicationPhase.Ready))
            withVerifiedResultFile(ready, written) { }
            verifyOriginal(input, sourceSha256)
            job.ensureActive(); journal.advance(inserted, ready); job.ensureActive()
            check(resolver.update(destination, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.GENERATION_ADDED}=? AND " +
                    "${MediaStore.MediaColumns.GENERATION_MODIFIED}=? AND ${MediaStore.MediaColumns.IS_PENDING}=1 AND ${MediaStore.MediaColumns.IS_TRASHED}=0",
                arrayOf(written.ownerPackage, written.displayName, written.relativePath, written.generationAdded.toString(), written.generationModified.toString())) == 1)
            // There is intentionally no provider cleanup path: cancellation, callback and fsync failures preserve possible commits.
            val recovered = reconcileReceipt(ready)
            check(recovered.status == MotionPhotoPublicationStatus.Published)
            val published = requireNotNull(recovered.receipt)
            journal.advance(ready, published)
            MotionPhotoCommitProbe.afterCommit(context, published)
            onPublished(destination)
            destination
        }
    }

    private suspend fun verifyOriginal(input: MotionPhotoInput, expectedHash: String) {
        MotionPhotoSession.verifyGeneration(context, input)
        val hash = resolver.openInputStream(input.uri)?.use { digest(it, 512L * 1024 * 1024, exact = false) }
            ?: error("Original unavailable")
        check(hash == expectedHash) { "Original bytes changed" }
        MotionPhotoSession.verifyGeneration(context, input)
    }

    private suspend fun reconcileReceipt(stored: MotionPhotoPublicationReceipt): MotionPhotoPublicationRecovery {
        fun result(status: MotionPhotoPublicationStatus) = MotionPhotoPublicationRecovery(status, stored)
        val expected = stored.destination ?: return result(MotionPhotoPublicationStatus.Incomplete)
        val current = publicationMetadata(Uri.parse(expected.uri)) ?: return result(MotionPhotoPublicationStatus.Conflict)
        if (!sameDestination(expected, current) || current.trashed || current.generationModified < expected.generationModified)
            return result(MotionPhotoPublicationStatus.Conflict)
        if (stored.phase == MotionPhotoPublicationPhase.Inserted)
            return result(if (current.pending) MotionPhotoPublicationStatus.Incomplete else MotionPhotoPublicationStatus.Conflict)
        if (current.pending) return result(if (stored.phase == MotionPhotoPublicationPhase.Ready && current == expected)
            MotionPhotoPublicationStatus.Incomplete else MotionPhotoPublicationStatus.Conflict)
        if (current.sizeBytes != stored.renderSizeBytes || (stored.phase == MotionPhotoPublicationPhase.Published && current != expected))
            return result(MotionPhotoPublicationStatus.Conflict)
        try { withVerifiedResultFile(stored, current) { } }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: IllegalStateException) { return result(MotionPhotoPublicationStatus.Conflict) }
        catch (_: IllegalArgumentException) { return result(MotionPhotoPublicationStatus.Conflict) }
        if (journal.read(stored.publicationId) != stored) return result(MotionPhotoPublicationStatus.Conflict)
        val verified = MotionPhotoPublicationReceipt.validatedCopy(stored.copy(destination = current, phase = MotionPhotoPublicationPhase.Published))
        return MotionPhotoPublicationRecovery(MotionPhotoPublicationStatus.Published, verified, current.uri)
    }

    private fun matchesPublished(stored: MotionPhotoPublicationReceipt, expected: MotionPhotoPublicationReceipt): Boolean {
        if (stored.phase !in listOf(MotionPhotoPublicationPhase.Ready, MotionPhotoPublicationPhase.Published) || !stored.sameRequest(expected)) return false
        val prior = stored.destination ?: return false; val current = expected.destination ?: return false
        return sameDestination(prior, current) && current.generationModified >= prior.generationModified &&
            (stored.phase != MotionPhotoPublicationPhase.Published || stored == expected)
    }

    private fun publicationMetadata(uri: Uri, strictQuery: Boolean = false): MotionPhotoPublicationDestination? {
        val cursor = resolver.query(uri, arrayOf(
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.GENERATION_ADDED, MediaStore.MediaColumns.GENERATION_MODIFIED,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.IS_TRASHED), null, null, null)
        if (cursor == null) { check(!strictQuery) { "Provider query returned no cursor" }; return null }
        return cursor.use {
            if (!it.moveToFirst()) return@use null
            val value = MotionPhotoPublicationDestination(uri.toString(), it.getString(0).orEmpty(), it.getString(1).orEmpty(),
                it.getString(2).orEmpty(), it.getString(3).orEmpty(), it.getLong(4), it.getLong(5), it.getLong(6), it.getInt(7) != 0, it.getInt(8) != 0)
            check(!it.moveToNext()); value
        }
    }

    private fun sameDestination(a: MotionPhotoPublicationDestination, b: MotionPhotoPublicationDestination): Boolean =
        a.uri == b.uri && a.ownerPackage == b.ownerPackage && a.ownerPackage == context.packageName &&
            a.displayName == b.displayName && a.relativePath == b.relativePath && a.mimeType == b.mimeType && a.generationAdded == b.generationAdded

    private suspend fun <T> withVerifiedResultFile(
        receipt: MotionPhotoPublicationReceipt, metadata: MotionPhotoPublicationDestination, action: suspend (File) -> T,
    ): T {
        val directory = File(context.cacheDir.canonicalFile, "motion-publication-check-${UUID.randomUUID()}")
        check(directory.mkdir())
        try {
            val file = File(directory, if (receipt.kind == MotionPhotoPublicationKind.Frame) "result.jpg" else "result.mp4")
            val uri = Uri.parse(metadata.uri)
            val hash = resolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> digest(input, receipt.renderSizeBytes) { bytes, count -> output.write(bytes, 0, count) } }
            } ?: error("Published bytes unavailable")
            check(hash == receipt.renderSha256)
            validateResultFile(receipt, file)
            check(publicationMetadata(uri) == metadata) { "Publication changed during readback" }
            return action(file)
        } finally { directory.deleteRecursively() }
    }

    private suspend fun verifyResultStream(receipt: MotionPhotoPublicationReceipt, metadata: MotionPhotoPublicationDestination) {
        val uri = Uri.parse(metadata.uri)
        check(resolver.openInputStream(uri)?.use { digest(it, receipt.renderSizeBytes) } == receipt.renderSha256)
        check(publicationMetadata(uri) == metadata) { "Publication changed during readback" }
    }

    private fun validateResultFile(receipt: MotionPhotoPublicationReceipt, file: File) {
        if (receipt.kind == MotionPhotoPublicationKind.Clip) check(MotionPhotoSession.validateVideo(file) == receipt.durationUs)
        else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            check(bounds.outMimeType == "image/jpeg" && bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096)
            val sample = BitmapFactory.Options().apply { inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 128) }
            checkNotNull(BitmapFactory.decodeFile(file.absolutePath, sample)) { "JPEG validation failed" }.recycle()
        }
    }

    private suspend fun digest(input: InputStream, limit: Long, exact: Boolean = true,
        consume: ((ByteArray, Int) -> Unit)? = null): String {
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536); var total = 0L
        val job = currentCoroutineContext()
        while (true) {
            job.ensureActive(); val count = input.read(buffer); if (count < 0) break
            check(count <= limit - total) { "Unexpected result length" }; total += count
            digest.update(buffer, 0, count); consume?.invoke(buffer, count)
        }
        check(!exact || total == limit) { "Unexpected result length" }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private companion object {
        const val SnapshotWhere = "owner_package_name=? AND _display_name=? AND relative_path=? AND mime_type=? AND " +
            "generation_added=CAST(? AS INTEGER) AND generation_modified=CAST(? AS INTEGER) AND " +
            "COALESCE(_size,0)=CAST(? AS INTEGER) AND is_pending=CAST(? AS INTEGER) AND is_trashed=CAST(? AS INTEGER)"
    }

}
