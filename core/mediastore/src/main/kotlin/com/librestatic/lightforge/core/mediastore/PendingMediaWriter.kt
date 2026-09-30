package com.librestatic.lightforge.core.mediastore

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import com.librestatic.lightforge.core.model.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest
import java.io.File
import kotlin.coroutines.coroutineContext

data class MediaWriteSpec(
    val destinationVolume: String,
    val kind: MediaKind,
    val displayName: String,
    val mimeType: String,
    val relativePath: String,
) {
    init {
        require(destinationVolume.isNotBlank())
        require(displayName.isNotBlank() && '/' !in displayName && '\\' !in displayName)
        require(mimeType.contains('/'))
        require(relativePath.isNotBlank() && ".." !in relativePath)
    }
}

data class PendingWriteSnapshot(
    val pendingUri: String,
    val spec: MediaWriteSpec,
    val startedAtMillis: Long,
) : java.io.Serializable

data class PublishedCopy(
    val uri: Uri,
    val bytes: Long,
    val sha256: String,
)

data class MoveCopyReady(
    val copy: PublishedCopy,
    val source: MediaActionTarget,
    val requiredDeleteAction: MediaAction = MediaAction.Delete,
)

fun interface DestinationSpaceProbe {
    /** Null means the provider cannot expose a reliable preflight and rollback remains authoritative. */
    fun availableBytes(volumeName: String): Long?
}

class InsufficientDestinationSpaceException(required: Long, available: Long) :
    IOException("Destination requires $required bytes but only $available are available")

class PendingMediaWriter(
    private val resolver: ContentResolver,
    private val spaceProbe: DestinationSpaceProbe = DestinationSpaceProbe { null },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val persistPending: (PendingWriteSnapshot?) -> Unit = {},
) {
    /**
     * Streams into a pending item without a named plaintext staging file. The caller must persist
     * intent before calling and persist the exact inserted URI in onInserted before any bytes.
     * Only a fully verified pending item is returned. Publication and failure cleanup deliberately
     * remain with the caller's durable, reviewed recovery protocol, including callback failures.
     */
    suspend fun stageStream(
        spec: MediaWriteSpec,
        expectedSha256: String,
        onInserted: suspend (Uri) -> Unit,
        write: suspend (java.io.OutputStream) -> Unit,
        beforeOpen: suspend (Uri) -> Unit = {},
    ): PublishedCopy = withContext(ioDispatcher) {
        require(expectedSha256.matches(Regex("[0-9a-f]{64}")))
        coroutineContext.ensureActive()
        val pending = resolver.insert(spec.collection(), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, spec.displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, spec.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, spec.relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: throw IOException("MediaStore rejected pending output")
        // If admission/receipt persistence fails, no plaintext has been written. Never adopt or
        // unconditionally delete the new row: the caller retains its durable uncertain intent.
        onInserted(pending)
        coroutineContext.ensureActive()
        val context = coroutineContext
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        beforeOpen(pending)
        coroutineContext.ensureActive()
        val descriptor = resolver.openFileDescriptor(pending, "w")
            ?: throw IOException("Could not open pending output")
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
            val counted = object : java.io.OutputStream() {
                override fun write(value: Int) {
                    context.ensureActive()
                    output.write(value); digest.update(value.toByte()); bytes = Math.addExact(bytes, 1L)
                }
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    context.ensureActive()
                    output.write(buffer, offset, length); digest.update(buffer, offset, length)
                    bytes = Math.addExact(bytes, length.toLong())
                }
                override fun flush() = output.flush()
            }
            write(counted) // PrivateAlbumCrypto must reach authenticated EOF before returning.
            context.ensureActive()
            output.flush()
            output.fd.sync()
        }
        check(bytes > 0 && digest.digest().hex() == expectedSha256) { "Stream source authentication failed" }
        check(resolver.openFileDescriptor(pending, "r")?.use { it.statSize } == bytes) { "Stream destination size mismatch" }
        val actual = resolver.openInputStream(pending)?.use { input ->
            val verify = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BufferBytes)
            while (true) {
                context.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) verify.update(buffer, 0, count)
            }
            verify.digest().hex()
        } ?: throw IOException("Could not verify streamed output")
        check(actual == expectedSha256) { "Stream destination digest mismatch" }
        context.ensureActive()
        check(pendingFlag(pending) == 1) { "Stream output changed before verification" }
        PublishedCopy(pending, bytes, expectedSha256)
    }

    /** Publishes an already-rendered local file through the same pending/verify protocol as a URI copy. */
    suspend fun publishFile(
        source: File,
        spec: MediaWriteSpec,
        onProgress: suspend (Long) -> Unit = {},
        onVerifying: suspend () -> Unit = {},
        beforePublish: suspend () -> Unit = {},
    ): PublishedCopy = withContext(ioDispatcher) {
        require(source.isFile && source.length() > 0) { "Rendered source is empty" }
        val expectedSize = source.length()
        val available = spaceProbe.availableBytes(spec.destinationVolume)
        if (available != null && available < expectedSize) {
            throw InsufficientDestinationSpaceException(expectedSize, available)
        }
        val pending = resolver.insert(spec.collection(), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, spec.displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, spec.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, spec.relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: error("MediaStore rejected pending output")
        persistPending(PendingWriteSnapshot(pending.toString(), spec, nowMillis()))
        try {
            val sourceDigest = MessageDigest.getInstance("SHA-256")
            val copied = pendingOutput(pending) { output ->
                source.inputStream().use { input ->
                    val buffer = ByteArray(BufferBytes)
                    var total = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        sourceDigest.update(buffer, 0, read)
                        total += read
                        onProgress(total)
                    }
                    total
                }
            }
            check(copied == expectedSize) { "Rendered source changed while publishing" }
            val expectedDigest = sourceDigest.digest().hex()
            onVerifying()
            verifyAndPublish(pending, copied, expectedDigest, beforePublish)
            persistPending(null)
            PublishedCopy(pending, copied, expectedDigest)
        } catch (cancelled: CancellationException) {
            resolver.delete(pending, null, null)
            persistPending(null)
            throw cancelled
        } catch (failure: Throwable) {
            resolver.delete(pending, null, null)
            persistPending(null)
            throw failure
        }
    }

    suspend fun copy(
        source: Uri,
        spec: MediaWriteSpec,
        onProgress: suspend (Long) -> Unit = {},
    ): PublishedCopy = withContext(ioDispatcher) {
        val expectedSize = sourceSize(source)
        val available = spaceProbe.availableBytes(spec.destinationVolume)
        if (expectedSize != null && available != null && available < expectedSize) {
            throw InsufficientDestinationSpaceException(expectedSize, available)
        }
        val pending = resolver.insert(spec.collection(), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, spec.displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, spec.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, spec.relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: error("MediaStore rejected pending output")
        persistPending(PendingWriteSnapshot(pending.toString(), spec, nowMillis()))
        try {
            val sourceDigest = MessageDigest.getInstance("SHA-256")
            val copied = resolver.openInputStream(source)?.use { input ->
                resolver.openOutputStream(pending, "w")?.use { output ->
                    val buffer = ByteArray(BufferBytes)
                    var total = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        sourceDigest.update(buffer, 0, read)
                        total += read
                        onProgress(total)
                    }
                    output.flush()
                    total
                }
            } ?: throw IOException("Could not open source or destination")
            check(copied > 0) { "Refusing to publish an empty copy" }
            if (expectedSize != null) check(copied == expectedSize) { "Source changed while copying" }
            val destinationSize = resolver.openFileDescriptor(pending, "r")?.use { it.statSize }
            check(destinationSize == copied) { "Destination size verification failed" }
            val expectedDigest = sourceDigest.digest().hex()
            val destinationDigest = resolver.openInputStream(pending)?.use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(BufferBytes)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) digest.update(buffer, 0, read)
                }
                digest.digest().hex()
            } ?: throw IOException("Could not verify destination")
            check(destinationDigest == expectedDigest) { "Destination digest verification failed" }
            check(
                resolver.update(
                    pending,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                ) == 1,
            ) { "MediaStore did not publish the verified copy" }
            check(pendingFlag(pending) == 0) { "Published output remained pending" }
            persistPending(null)
            PublishedCopy(pending, copied, expectedDigest)
        } catch (cancelled: CancellationException) {
            resolver.delete(pending, null, null)
            persistPending(null)
            throw cancelled
        } catch (failure: Throwable) {
            resolver.delete(pending, null, null)
            persistPending(null)
            throw failure
        }
    }

    suspend fun prepareMove(
        sourceUri: Uri,
        source: MediaActionTarget,
        destination: MediaWriteSpec,
    ): MoveCopyReady = MoveCopyReady(copy(sourceUri, destination), source)

    fun recoverOwnedPending(
        ownerPackageName: String,
        relativePathPrefix: String,
        volumes: Set<String>,
        olderThanEpochSeconds: Long,
    ): Int {
        require(ownerPackageName.isNotBlank() && relativePathPrefix.isNotBlank())
        return volumes.sumOf { volume ->
            listOf(MediaKind.Image, MediaKind.Video).sumOf { kind ->
                val collection = collection(volume, kind)
                val ids = buildList {
                    resolver.query(
                        collection,
                        arrayOf(
                            MediaStore.MediaColumns._ID,
                            MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            MediaStore.MediaColumns.DATE_ADDED,
                        ),
                        Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY) },
                        null,
                    )?.use { cursor ->
                        while (cursor.moveToNext()) {
                            if (cursor.getString(1) == ownerPackageName &&
                                cursor.getString(2).orEmpty().startsWith(relativePathPrefix) &&
                                cursor.getLong(3) < olderThanEpochSeconds
                            ) add(cursor.getLong(0))
                        }
                    }
                }
                ids.sumOf { resolver.delete(ContentUris.withAppendedId(collection, it), null, null) }
            }
        }
    }

    private fun sourceSize(uri: Uri): Long? = resolver.openAssetFileDescriptor(uri, "r")?.use {
        it.length.takeIf { length -> length >= 0 }
    }

    private fun pendingFlag(uri: Uri): Int? = resolver.query(
        uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null,
    )?.use { if (it.moveToFirst()) it.getInt(0) else null }

    private fun MediaWriteSpec.collection() = collection(destinationVolume, kind)

    private suspend fun pendingOutput(
        pending: Uri,
        block: suspend (java.io.OutputStream) -> Long,
    ): Long {
        val output = resolver.openOutputStream(pending, "w")
            ?: throw IOException("Could not open pending output")
        return try {
            block(output)
        } finally {
            output.close()
        }
    }

    private suspend fun verifyAndPublish(
        pending: Uri,
        copied: Long,
        expectedDigest: String,
        beforePublish: suspend () -> Unit,
    ) {
        check(resolver.openFileDescriptor(pending, "r")?.use { it.statSize } == copied) {
            "Destination size verification failed"
        }
        val destinationDigest = resolver.openInputStream(pending)?.use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BufferBytes)
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
            digest.digest().hex()
        } ?: throw IOException("Could not verify destination")
        check(destinationDigest == expectedDigest) { "Destination digest verification failed" }
        // Long verification must finish before the caller's final source/authorization check.
        coroutineContext.ensureActive()
        beforePublish()
        coroutineContext.ensureActive()
        check(
            resolver.update(
                pending,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            ) == 1,
        ) { "MediaStore did not publish the verified copy" }
        check(pendingFlag(pending) == 0) { "Published output remained pending" }
    }

    private fun collection(volume: String, kind: MediaKind): Uri = when (kind) {
        MediaKind.Image -> MediaStore.Images.Media.getContentUri(volume)
        MediaKind.Video -> MediaStore.Video.Media.getContentUri(volume)
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private companion object { const val BufferBytes = 256 * 1_024 }
}
