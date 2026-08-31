package com.ugallery.core.mediastore

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import com.ugallery.core.model.MediaKind
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
    /** Publishes an already-rendered local file through the same pending/verify protocol as a URI copy. */
    suspend fun publishFile(
        source: File,
        spec: MediaWriteSpec,
        onProgress: suspend (Long) -> Unit = {},
        onVerifying: suspend () -> Unit = {},
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
            verifyAndPublish(pending, copied, expectedDigest)
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

    private fun verifyAndPublish(pending: Uri, copied: Long, expectedDigest: String) {
        check(resolver.openFileDescriptor(pending, "r")?.use { it.statSize } == copied) {
            "Destination size verification failed"
        }
        val destinationDigest = resolver.openInputStream(pending)?.use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BufferBytes)
            while (true) {
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
    }

    private fun collection(volume: String, kind: MediaKind): Uri = when (kind) {
        MediaKind.Image -> MediaStore.Images.Media.getContentUri(volume)
        MediaKind.Video -> MediaStore.Video.Media.getContentUri(volume)
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private companion object { const val BufferBytes = 256 * 1_024 }
}
