package com.ugallery.core.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.room.withTransaction
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.database.MotionKeyFrameEntity
import com.ugallery.core.mediastore.MediaStoreUriFactory
import com.ugallery.core.model.MediaKey
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** CAS-protected local display choices. The source remains the original JPEG+MP4 for sharing/editing. */
class MotionKeyFrameRepository(
    context: Context,
    private val database: GalleryDatabase,
    private val now: () -> Long = System::currentTimeMillis,
    private val sourceGeneration: suspend (MediaKey) -> Long? = { key ->
        withContext(Dispatchers.IO) {
            context.contentResolver.query(MediaStoreUriFactory.uriFor(key),
                arrayOf(MediaStore.MediaColumns.GENERATION_MODIFIED), null, null, null)?.use {
                if (it.moveToFirst()) it.getLong(0) else null
            }
        }
    },
) {
    private val directory = File(context.applicationContext.filesDir, "motion-key-frames")
    private val dao = database.motionKeyFrameDao()
    private val mutex = Mutex()

    fun observe(key: MediaKey) = dao.observe(key.volumeName, key.mediaStoreId)
    fun versions() = dao.versions()

    suspend fun set(
        key: MediaKey,
        generation: Long,
        timeUs: Long,
        frame: File,
        expectedRevision: String?,
    ): MotionKeyFrameEntity = mutex.withLock {
        require(generation >= 0 && timeUs in 0..600_000_000L)
        withContext(Dispatchers.IO) {
            check(sourceGeneration(key) == generation) { "Motion photo changed or access was removed" }
            check(frame.isFile && frame.length() in 1..MaxFrameBytes) { "Invalid key frame" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(frame.absolutePath, bounds)
            check(bounds.outMimeType == "image/jpeg" && bounds.outWidth in 1..8192 &&
                bounds.outHeight in 1..8192 && bounds.outWidth.toLong() * bounds.outHeight <= 32_000_000) {
                "Invalid key frame image"
            }
            val sample = BitmapFactory.Options().apply {
                inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 128)
            }
            checkNotNull(BitmapFactory.decodeFile(frame.absolutePath, sample)) { "Undecodable key frame" }.recycle()
            check(directory.mkdirs() || directory.isDirectory)
            val revision = UUID.randomUUID().toString()
            val destination = File(directory, "$revision.jpg")
            var committed = false
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                val coroutine = currentCoroutineContext()
                frame.inputStream().use { input -> destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutine.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        bytes += count
                        check(bytes <= MaxFrameBytes)
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                } }
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                check(verifiedFile(destination.name, sha, bytes) != null) { "Key frame write verification failed" }
                check(sourceGeneration(key) == generation) { "Motion photo changed during key frame selection" }
                val value = MotionKeyFrameEntity(key.volumeName, key.mediaStoreId, generation,
                    timeUs, destination.name, sha, bytes, revision, now())
                var previous: MotionKeyFrameEntity? = null
                database.withTransaction {
                    val source = database.libraryDao().media(key.volumeName, key.mediaStoreId)
                    check(source != null && source.mediaType == 1 && source.isAccessible && !source.isTrashed && source.generationModified == generation)
                    previous = dao.get(key.volumeName, key.mediaStoreId)
                    check(previous?.takeIf { it.generationModified == generation }?.revision == expectedRevision) {
                        "The saved key frame changed; review it before replacing it"
                    }
                    dao.put(value)
                }
                committed = true
                previous?.let { old -> verifiedFile(old.fileName, old.sha256, old.sizeBytes)?.delete() }
                value
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    // A cancellation at Room's return dispatcher can follow a committed transaction.
                    // Never delete a frame that the durable row already references.
                    if (!committed && dao.get(key.volumeName, key.mediaStoreId)?.revision != revision)
                        destination.delete()
                }
            }
        }
    }

    suspend fun reset(key: MediaKey, expectedRevision: String): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val previous = database.withTransaction {
                val value = dao.get(key.volumeName, key.mediaStoreId)
                if (value?.revision != expectedRevision) return@withTransaction null
                check(dao.remove(key.volumeName, key.mediaStoreId, expectedRevision) == 1)
                value
            } ?: return@withContext false
            verifiedFile(previous.fileName, previous.sha256, previous.sizeBytes)?.delete()
            true
        }
    }

    /** Called on a decoder worker, never the main thread. Corruption falls back to the original. */
    fun displayUri(key: MediaKey, generation: Long): Uri? {
        val value = dao.currentForDisplay(key.volumeName, key.mediaStoreId, generation) ?: return null
        return verifiedFile(value.fileName, value.sha256, value.sizeBytes)?.let(Uri::fromFile)
    }

    /** Old unreferenced operation files only; a newly staged frame may precede its Room transaction. */
    suspend fun pruneOrphans() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val retained = dao.retainedFiles().toSet()
            directory.listFiles()?.forEach { file ->
                if (ValidFile.matches(file.name) && file.name !in retained && now() - file.lastModified() > 86_400_000L)
                    file.delete()
            }
        }
    }

    private fun verifiedFile(name: String, expected: String, bytes: Long): File? {
        if (!ValidFile.matches(name) || bytes !in 1..MaxFrameBytes) return null
        val file = File(directory, name)
        if (!file.isFile || file.canonicalFile.parentFile != directory.canonicalFile || file.length() != bytes) return null
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var read = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                read += count
                if (read > bytes) return null
                hash.update(buffer, 0, count)
            }
        }
        return file.takeIf { hash.digest().joinToString("") { "%02x".format(it) } == expected }
    }

    companion object {
        private const val MaxFrameBytes = 32L * 1024 * 1024
        private val ValidFile = Regex("[0-9a-f-]{36}\\.jpg")
    }
}
