package com.ugallery.core.editing.video

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** URI plus the indexed generation: replacing an original must never silently change a video. */
data class MemoryVideoSource(
    val uri: Uri,
    val expectedGeneration: Long? = null,
    val expectedGenerationAdded: Long? = null,
)

data class MemoryVideoRequest(
    val sources: List<MemoryVideoSource>,
    val secondsPerPhoto: Int = 3,
    val music: Uri? = null,
) {
    init {
        require(sources.size in 1..120)
        require(secondsPerPhoto in SupportedSecondsPerPhoto)
        require(sources.all { it.uri.scheme in setOf("content", "file") })
        require(music == null || music.scheme in setOf("content", "file"))
    }

    companion object {
        val SupportedSecondsPerPhoto: IntRange = 1..5
    }

    val durationMillis: Long
        get() = sources.size * secondsPerPhoto * 1_000L
}

enum class MemoryVideoPhase {
    Preparing,
    Encoding,
    Publishing,
}

data class MemoryVideoProgress(val phase: MemoryVideoPhase, val percent: Int? = null)

/**
 * Bounded 720p still-image snapshots + local Media3 H.264/AAC composition. Originals are read only.
 * A failing/missing/changed source aborts the entire job, never silently dropping a requested
 * frame. Every temporary file and pending MediaStore row is owned by this operation and rolled back
 * on error.
 */
@UnstableApi
class MemoryVideoExporter(private val context: Context) {
    private val resolver = context.contentResolver

    suspend fun export(
        request: MemoryVideoRequest,
        onProgress: (MemoryVideoProgress) -> Unit = {},
    ): Uri =
        withTimeout(300_000) {
            withContext(Dispatchers.IO) {
                val job = currentCoroutineContext()
                val dir = File(context.cacheDir, "memory-video-${UUID.randomUUID()}")
                check(dir.mkdir())
                var pending: Uri? = null
                var committed = false
                try {
                    val frames =
                        request.sources.mapIndexed { index, source ->
                            job.ensureActive()
                            onProgress(
                                MemoryVideoProgress(
                                    MemoryVideoPhase.Preparing,
                                    index * 100 / request.sources.size,
                                )
                            )
                            verifyGeneration(source)
                            val file = File(dir, "frame-$index.jpg")
                            snapshot(source.uri, file)
                            verifyGeneration(source)
                            file
                        }
                    val music =
                        request.music?.let { uri ->
                            File(dir, "music").also { file ->
                                resolver.openInputStream(uri)?.use { input ->
                                    file.outputStream().use { output ->
                                        val buffer = ByteArray(64 * 1024)
                                        var size = 0L
                                        while (true) {
                                            job.ensureActive()
                                            val read = input.read(buffer)
                                            if (read < 0) break
                                            size += read
                                            check(size <= 128L * 1024 * 1024) {
                                                "Music exceeds local export limit"
                                            }
                                            output.write(buffer, 0, read)
                                        }
                                    }
                                } ?: error("Music is no longer accessible")
                                check(hasTrack(file, "audio/")) {
                                    "Selected file has no audio track"
                                }
                            }
                        }
                    val output = File(dir, "result.mp4")
                    encode(request, frames, music, output, onProgress)
                    job.ensureActive()
                    validate(output, request.durationMillis, music != null)
                    onProgress(MemoryVideoProgress(MemoryVideoPhase.Publishing))
                    val collection =
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    pending =
                        resolver.insert(
                            collection,
                            ContentValues().apply {
                                put(
                                    MediaStore.MediaColumns.DISPLAY_NAME,
                                    "UGallery-Memory-${UUID.randomUUID()}.mp4",
                                )
                                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                                put(
                                    MediaStore.MediaColumns.RELATIVE_PATH,
                                    "Movies/UGallery/Memories",
                                )
                                put(MediaStore.MediaColumns.IS_PENDING, 1)
                            },
                        ) ?: error("Pending publication rejected")
                    val target = checkNotNull(pending)
                    resolver.openOutputStream(target, "w")?.use { stream ->
                        output.inputStream().use { input ->
                            val bytes = ByteArray(64 * 1024)
                            while (true) {
                                job.ensureActive()
                                val count = input.read(bytes)
                                if (count < 0) break
                                stream.write(bytes, 0, count)
                            }
                        }
                    } ?: error("Pending publication is not writable")
                    check(
                        resolver.openFileDescriptor(target, "r")?.use { it.statSize } ==
                            output.length()
                    )
                    // Linearization point: cancellation before commit removes the row; afterwards
                    // it is a result.
                    job.ensureActive()
                    check(
                        resolver.update(
                            target,
                            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                            null,
                            null,
                        ) == 1
                    ) {
                        "Publication commit rejected"
                    }
                    committed = true
                    target
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) {
                        if (!committed) pending?.let { resolver.delete(it, null, null) }
                        dir.deleteRecursively()
                    }
                }
            }
        }

    private fun verifyGeneration(source: MemoryVideoSource) {
        if (source.expectedGeneration == null && source.expectedGenerationAdded == null) return
        resolver.query(
            source.uri,
            arrayOf(MediaStore.MediaColumns.GENERATION_MODIFIED, MediaStore.MediaColumns.GENERATION_ADDED),
            null, null, null,
        )?.use {
            check(it.moveToFirst() &&
                (source.expectedGeneration == null || it.getLong(0) == source.expectedGeneration) &&
                (source.expectedGenerationAdded == null || it.getLong(1) == source.expectedGenerationAdded)
            ) { "Photo changed or access was removed" }
        } ?: error("Photo access was removed")
    }

    private fun snapshot(uri: Uri, destination: File) {
        val bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _
                ->
                val scale = minOf(1280f / info.size.width, 720f / info.size.height, 1f)
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
        try {
            val frame = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(frame)
                canvas.drawColor(Color.BLACK)
                val scale = minOf(1280f / bitmap.width, 720f / bitmap.height)
                val width = (bitmap.width * scale).toInt()
                val height = (bitmap.height * scale).toInt()
                val left = (1280 - width) / 2
                val top = (720 - height) / 2
                canvas.drawBitmap(
                    bitmap,
                    null,
                    Rect(left, top, left + width, top + height),
                    Paint(Paint.FILTER_BITMAP_FLAG),
                )
                destination.outputStream().use {
                    check(frame.compress(Bitmap.CompressFormat.JPEG, 92, it))
                }
            } finally {
                frame.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun encode(
        request: MemoryVideoRequest,
        frames: List<File>,
        music: File?,
        output: File,
        onProgress: (MemoryVideoProgress) -> Unit,
    ) =
        withContext(Dispatchers.Main.immediate) {
            val video = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
            frames.forEach { frame ->
                video.addItem(
                    EditedMediaItem.Builder(
                            MediaItem.Builder()
                                .setUri(Uri.fromFile(frame))
                                .setMimeType("image/jpeg")
                                .setImageDurationMs(request.secondsPerPhoto * 1_000L)
                                .build()
                        )
                        .setFrameRate(30)
                        .setRemoveAudio(true)
                        .build()
                )
            }
            val sequences = mutableListOf(video.build())
            music?.let {
                sequences +=
                    EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
                        .addItem(
                            EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(it)))
                                .setRemoveVideo(true)
                                .build()
                        )
                        .setIsLooping(true)
                        .build()
            }
            val composition = Composition.Builder(sequences).build()
            val handler = Handler(Looper.getMainLooper())
            var transformer: Transformer? = null
            var poll: Runnable? = null
            try {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val encoder =
                        Transformer.Builder(context.applicationContext)
                            .setVideoMimeType(MimeTypes.VIDEO_H264)
                            .setAudioMimeType(MimeTypes.AUDIO_AAC)
                            .addListener(
                                object : Transformer.Listener {
                                    override fun onCompleted(
                                        composition: Composition,
                                        exportResult: ExportResult,
                                    ) {
                                        if (continuation.isActive) continuation.resume(Unit)
                                    }

                                    override fun onError(
                                        composition: Composition,
                                        exportResult: ExportResult,
                                        exportException: ExportException,
                                    ) {
                                        if (continuation.isActive)
                                            continuation.resumeWithException(exportException)
                                    }
                                }
                            )
                            .build()
                    transformer = encoder
                    val holder = ProgressHolder()
                    poll =
                        object : Runnable {
                            override fun run() {
                                if (!continuation.isActive) return
                                val percent =
                                    if (
                                        encoder.getProgress(holder) ==
                                            Transformer.PROGRESS_STATE_AVAILABLE
                                    )
                                        holder.progress
                                    else null
                                onProgress(MemoryVideoProgress(MemoryVideoPhase.Encoding, percent))
                                handler.postDelayed(this, 250)
                            }
                        }
                    encoder.start(composition, output.absolutePath)
                    handler.post(checkNotNull(poll))
                }
            } finally {
                // Await cancellation on the Transformer application looper before deleting
                // input/output files.
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    poll?.let(handler::removeCallbacks)
                    transformer?.cancel()
                }
            }
        }

    private fun hasTrack(file: File, prefix: String): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).any {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) ==
                    true
            }
        } finally {
            extractor.release()
        }
    }

    private fun validate(file: File, expectedMillis: Long, audio: Boolean) {
        check(file.length() > 0 && hasTrack(file, "video/avc")) { "Export has no H.264 video" }
        check(hasTrack(file, "audio/") == audio) { "Unexpected audio tracks" }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val duration =
                retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            check(kotlin.math.abs(duration - expectedMillis) <= 250L) {
                "Unexpected video duration"
            }
            check(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) == "1280"
            )
            check(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) == "720"
            )
            val first = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)
            check(first != null) { "First frame is undecodable" }
            first.recycle()
            val last =
                retriever.getFrameAtTime(
                    (expectedMillis - 100) * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                )
            check(last != null) { "Last frame is undecodable" }
            last.recycle()
        } finally {
            retriever.release()
        }
    }
}
