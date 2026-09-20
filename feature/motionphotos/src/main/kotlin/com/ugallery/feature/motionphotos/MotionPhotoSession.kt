package com.ugallery.feature.motionphotos

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class MotionPhotoInput(
    val uri: Uri,
    val expectedGeneration: Long? = null,
    val expectedGenerationAdded: Long? = null,
) {
    val identity: String get() = motionPhotoInputIdentity(uri.toString(), expectedGeneration, expectedGenerationAdded)
    init {
        require(uri.scheme in setOf("content", "file"))
    }
}

internal const val MaximumMotionDurationUs = 60_000_000L

internal fun motionPhotoInputIdentity(uri: String, modified: Long?, added: Long?): String = "$uri@$modified/$added"

internal fun requireMotionPhotoIdentity(savedIdentity: String, identity: String) {
    check(savedIdentity == identity) { "Saved motion source was replaced" }
}

/** Only -1 means an initial draft; a malformed restored time is never relabelled as initial. */
internal fun restoredMotionPhotoFrame(savedUs: Long, confirmedUs: Long?, defaultUs: Long, durationUs: Long): Long {
    check(durationUs in 1..MaximumMotionDurationUs && defaultUs in 0 until durationUs)
    if (savedUs == -1L) return confirmedUs?.takeIf { it in 0 until durationUs } ?: defaultUs
    check(savedUs in 0 until durationUs) { "Saved motion frame is outside the current video track" }
    return savedUs
}

internal fun requireMotionPhotoGeneration(
    expectedModified: Long, expectedAdded: Long?, actualModified: Long, actualAdded: Long,
    trashed: Boolean, pending: Boolean,
) {
    check(expectedModified >= 0 && (expectedAdded == null || expectedAdded >= 0)) { "Invalid source generation" }
    check(actualModified == expectedModified && !trashed && !pending) { "Source changed or access removed" }
    check(expectedAdded == null || actualAdded == expectedAdded) { "Source replaced" }
}

/** Owns private immutable bytes. Closing is required after operations finish/cancel. */
class MotionPhotoSession
private constructor(
    private val context: Context,
    val input: MotionPhotoInput,
    private val directory: File,
    val clip: File,
    val originalSha256: String,
    val durationUs: Long,
    val defaultTimeUs: Long,
) : Closeable {
    private val lock = Mutex()
    val frameTimesUs: List<Long> = List(6) { it * (durationUs - 1).coerceAtLeast(0) / 5 }

    suspend fun frame(timeUs: Long, maxDimension: Int = 1600): Bitmap =
        lock.withLock {
            require(timeUs in 0 until durationUs && maxDimension in 96..4096)
            withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(clip.absolutePath)
                    retriever.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        maxDimension,
                        maxDimension,
                    ) ?: error("Frame is not decodable")
                } finally {
                    retriever.release()
                }
            }
        }

    /**
     * The caller copies this file while its callback is active, never stores its temporary path.
     */
    suspend fun withFrameFile(timeUs: Long, action: suspend (File) -> Boolean): Boolean {
        withContext(Dispatchers.IO) { verifyGeneration(context, input) }
        val bitmap = frame(timeUs, 4096)
        val file = File(directory, "selected-${UUID.randomUUID()}.jpg")
        try {
            withContext(Dispatchers.IO) {
                file.outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it))
                }
            }
            withContext(Dispatchers.IO) { verifyGeneration(context, input) }
            return action(file)
        } finally {
            bitmap.recycle()
            file.delete()
        }
    }

    suspend fun exportFrame(timeUs: Long): Uri {
        var result: Uri? = null
        withFrameFile(timeUs) { file ->
            result = publish(file, false)
            true
        }
        return checkNotNull(result)
    }

    suspend fun exportClip(): Uri {
        withContext(Dispatchers.IO) { verifyGeneration(context, input) }
        return publish(clip, true)
    }

    /** Durable exports use a caller-owned UUID. A recovered result is returned without opening originals. */
    suspend fun exportFrame(publicationId: String, timeUs: Long, onPublished: (Uri) -> Unit = {}): Uri {
        val publisher = MotionPhotoPublication(context)
        publisher.existingResult(publicationId, input, originalSha256, MotionPhotoPublicationKind.Frame, timeUs, durationUs)?.let {
            onPublished(it); return it
        }
        var result: Uri? = null
        withFrameFile(timeUs) { file ->
            result = publisher.publish(publicationId, input, originalSha256, MotionPhotoPublicationKind.Frame, timeUs, durationUs, file, onPublished)
            true
        }
        return checkNotNull(result)
    }

    suspend fun exportClip(publicationId: String, onPublished: (Uri) -> Unit = {}): Uri =
        MotionPhotoPublication(context).publish(publicationId, input, originalSha256,
            MotionPhotoPublicationKind.Clip, null, durationUs, clip, onPublished)

    private suspend fun publish(file: File, video: Boolean): Uri =
        withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()
            job.ensureActive()
            verifyGeneration(context, input)
            if (video) check(validateVideo(file) == durationUs)
            else {
                val bitmap =
                    BitmapFactory.decodeFile(file.absolutePath) ?: error("JPEG validation failed")
                bitmap.recycle()
            }
            val resolver = context.contentResolver
            val collection =
                if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val output =
                resolver.insert(
                    collection,
                    ContentValues().apply {
                        put(
                            MediaStore.MediaColumns.DISPLAY_NAME,
                            "UGallery-Motion-${UUID.randomUUID()}.${if (video) "mp4" else "jpg"}",
                        )
                        put(
                            MediaStore.MediaColumns.MIME_TYPE,
                            if (video) "video/mp4" else "image/jpeg",
                        )
                        put(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            if (video) "Movies/UGallery/Motion" else "Pictures/UGallery/Motion",
                        )
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    },
                ) ?: error("Pending creation failed")
            var committed = false
            try {
                resolver.openOutputStream(output, "w")!!.use { target ->
                    file.inputStream().use { source ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            job.ensureActive()
                            val n = source.read(buffer)
                            if (n < 0) break
                            target.write(buffer, 0, n)
                        }
                    }
                }
                val copied =
                    resolver.openInputStream(output)!!.use { source ->
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(65536)
                        while (true) {
                            job.ensureActive()
                            val n = source.read(buffer)
                            if (n < 0) break
                            digest.update(buffer, 0, n)
                        }
                        digest.digest().hex()
                    }
                check(copied == hash(file))
                verifyGeneration(context, input)
                job.ensureActive()
                check(
                    resolver.update(
                        output,
                        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                        null,
                        null,
                    ) == 1
                )
                committed = true
                output
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    if (!committed) resolver.delete(output, null, null)
                }
            }
        }

    override fun close() {
        directory.deleteRecursively()
    }

    companion object {
        /**
         * At most 2 MiB of JPEG headers plus bounded MP4 box headers; no copied photo/clip for
         * badges.
         */
        suspend fun inspect(context: Context, input: MotionPhotoInput): Boolean =
            try {
                withTimeout(3_000) {
                    withContext(Dispatchers.IO) {
                        verifyGeneration(context, input)
                        val result =
                            context.contentResolver.openFileDescriptor(input.uri, "r")?.use { fd ->
                                android.os.ParcelFileDescriptor.AutoCloseInputStream(
                                    android.os.ParcelFileDescriptor.dup(fd.fileDescriptor)
                                ).use { stream ->
                                    val info = MotionPhotoParser.parse(stream.channel)
                                    if (!info.isMotionPhoto) false
                                    else {
                                        val extractor = MediaExtractor()
                                        try {
                                            extractor.setDataSource(
                                                fd.fileDescriptor,
                                                info.motionVideoOffset,
                                                info.motionVideoLength,
                                            )
                                            (0 until extractor.trackCount).any {
                                                extractor
                                                    .getTrackFormat(it)
                                                    .getString(MediaFormat.KEY_MIME)
                                                    ?.startsWith("video/") == true
                                            }
                                        } finally {
                                            extractor.release()
                                        }
                                    }
                                }
                            } ?: false
                        verifyGeneration(context, input)
                        result
                    }
                }
            } catch (_: TimeoutCancellationException) {
                false
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                false
            }

        suspend fun open(context: Context, input: MotionPhotoInput): MotionPhotoSession =
            withTimeout(60_000) {
                withContext(Dispatchers.IO) {
                    val directory = File(context.cacheDir, "motion-photo-${UUID.randomUUID()}")
                    check(directory.mkdir())
                    try {
                        verifyGeneration(context, input)
                        val original = File(directory, "source.jpg")
                        context.contentResolver.openInputStream(input.uri)!!.use { source ->
                            original.outputStream().use { target ->
                                val buffer = ByteArray(65536)
                                var size = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val n = source.read(buffer)
                                    if (n < 0) break
                                    size += n
                                    check(size <= 512L * 1024 * 1024) {
                                        "Motion photo exceeds local limit"
                                    }
                                    target.write(buffer, 0, n)
                                }
                            }
                        }
                        verifyGeneration(context, input)
                        val info = RandomAccessFile(original, "r").use(MotionPhotoParser::parse)
                        check(info.isMotionPhoto) { "Motion section unavailable" }
                        val clip = File(directory, "motion.mp4")
                        RandomAccessFile(original, "r").use { source ->
                            source.seek(info.motionVideoOffset)
                            clip.outputStream().use { target ->
                                val buffer = ByteArray(65536)
                                var remaining = info.motionVideoLength
                                while (remaining > 0) {
                                    currentCoroutineContext().ensureActive()
                                    val count = minOf(buffer.size.toLong(), remaining).toInt()
                                    source.readFully(buffer, 0, count)
                                    target.write(buffer, 0, count)
                                    remaining -= count
                                }
                            }
                        }
                        val duration = validateVideo(clip)
                        check(duration in 1..MaximumMotionDurationUs) { "Motion clip exceeds 60 seconds" }
                        MotionPhotoSession(
                            context.applicationContext,
                            input,
                            directory,
                            clip,
                            hash(original),
                            duration,
                            info.presentationTimeUs?.takeIf { it < duration } ?: duration / 2,
                        )
                    } catch (failure: Throwable) {
                        directory.deleteRecursively()
                        throw failure
                    }
                }
            }

        internal fun validateVideo(file: File): Long {
            val extractor = MediaExtractor()
            val duration =
                try {
                    extractor.setDataSource(file.absolutePath)
                    val track =
                        (0 until extractor.trackCount).firstOrNull {
                            extractor
                                .getTrackFormat(it)
                                .getString(MediaFormat.KEY_MIME)
                                ?.startsWith("video/") == true
                        } ?: error("No video track")
                    val format = extractor.getTrackFormat(track)
                    check(format.containsKey(MediaFormat.KEY_DURATION)) {
                        "Video track duration is unknown"
                    }
                    format.getLong(MediaFormat.KEY_DURATION).also { check(it > 0) }
                } finally {
                    extractor.release()
                }
            // Container duration may include audio padding; scrub/key-frame time belongs to the
            // video track.
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val first =
                    retriever.getScaledFrameAtTime(
                        0,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        320,
                        320,
                    ) ?: error("Video is not decodable")
                first.recycle()
                return duration
            } finally {
                retriever.release()
            }
        }

        internal fun verifyGeneration(context: Context, input: MotionPhotoInput) {
            check(input.expectedGenerationAdded == null || input.expectedGeneration != null) { "Incomplete source generation" }
            input.expectedGeneration?.let { expected ->
                check(input.uri.scheme == "content" && input.uri.authority == "media")
                context.contentResolver.query(input.uri, arrayOf(
                    MediaStore.MediaColumns.GENERATION_MODIFIED, MediaStore.MediaColumns.GENERATION_ADDED,
                    MediaStore.MediaColumns.IS_TRASHED, MediaStore.MediaColumns.IS_PENDING,
                ), null, null, null)?.use {
                    check(it.moveToFirst()) { "Source access removed" }
                    requireMotionPhotoGeneration(expected, input.expectedGenerationAdded,
                        it.getLong(0), it.getLong(1), it.getInt(2) != 0, it.getInt(3) != 0)
                    check(!it.moveToNext()) { "Ambiguous motion source" }
                } ?: error("Source access removed")
            }
        }

        internal fun hash(file: File): String =
            file.inputStream().use { stream ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(65536)
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
                digest.digest().hex()
            }

        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    }
}
