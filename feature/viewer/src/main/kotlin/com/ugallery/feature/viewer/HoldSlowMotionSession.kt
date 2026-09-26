package com.ugallery.feature.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.ugallery.core.frameinterpolation.FrameInterpolationBackend
import com.ugallery.core.frameinterpolation.RifeFrameInterpolator
import com.ugallery.core.frameinterpolation.interpolateFactor
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

data class SlowMotionClip(
    val uri: Uri,
    val startMillis: Long,
    val endMillis: Long,
    val speed: Float = HoldSlowMotionSession.Speed,
)

sealed interface HoldSlowMotionState {
    data object Idle : HoldSlowMotionState
    data object Buffering : HoldSlowMotionState
    data class Playing(
        val frame: Bitmap,
        val sourcePositionMillis: Long,
        val backend: FrameInterpolationBackend,
    ) : HoldSlowMotionState
    data class ReadyToSave(val clip: SlowMotionClip) : HoldSlowMotionState
    data class Failure(val message: String) : HoldSlowMotionState
}

/**
 * Maintains a persistent low-resolution RIFE buffer ahead of playback once the user first touches
 * the video ([warmUp] on pointer-down) or holds; until then [prepare] is a no-op, so merely viewing
 * a video never runs interpolation.
 * Export still reads the original media at full quality.
 */
class HoldSlowMotionSession(
    context: Context,
    private val uri: Uri,
) : Closeable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow<HoldSlowMotionState>(HoldSlowMotionState.Idle)
    val state: StateFlow<HoldSlowMotionState> = mutableState

    private val bufferLock = Any()
    private val bufferedFrames = ArrayList<BufferedFrame>()
    private val cacheRoot = File(appContext.cacheDir, "hold-slow-motion/" + uri.cacheKey())
    private var bufferedWindowStart = Long.MIN_VALUE
    private var bufferComplete = false
    private var bufferFailure: String? = null
    private var prefetchBackend = FrameInterpolationBackend.Cpu
    private var prefetchJob: Job? = null
    private var armed = false
    private var preparingWindowStart = Long.MIN_VALUE
    private var playbackJob: Job? = null
    private var holdStartMillis = 0L
    private var sourcePositionMillis = 0L

    fun prepare(positionMillis: Long) {
        if (!armed) return
        val requestedWindow = positionMillis.coerceAtLeast(0L).windowStart()
        if (requestedWindow == preparingWindowStart && (prefetchJob?.isActive == true ||
                synchronized(bufferLock) { bufferedWindowStart == requestedWindow && bufferComplete })
        ) return
        preparingWindowStart = requestedWindow
        val previousPrefetch = prefetchJob
        previousPrefetch?.cancel()
        prefetchJob = scope.launch(Dispatchers.Default) {
            try {
                previousPrefetch?.join()
                withContext(Dispatchers.IO) { loadWindow(requestedWindow) }
                if (synchronized(bufferLock) { bufferComplete }) return@launch
                renderWindow(requestedWindow)
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (failure: Throwable) {
                synchronized(bufferLock) {
                    if (bufferedWindowStart == requestedWindow) {
                        bufferFailure = failure.message ?: "Could not prepare slow motion"
                    }
                }
            }
        }
    }

    /**
     * Arms the buffer on pointer-down, before the hold threshold, so the first hold rarely waits
     * on "Buffering". Videos that are never touched still never interpolate.
     */
    fun warmUp(positionMillis: Long) {
        armed = true
        prepare(positionMillis)
    }

    fun start(positionMillis: Long) {
        if (playbackJob?.isActive == true) return
        holdStartMillis = positionMillis.coerceAtLeast(0L)
        sourcePositionMillis = holdStartMillis
        mutableState.replaceFrame(HoldSlowMotionState.Buffering)
        armed = true
        prepare(positionMillis)
        playbackJob = scope.launch {
            try {
                playBuffered(positionMillis)
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (failure: Throwable) {
                mutableState.replaceFrame(
                    HoldSlowMotionState.Failure(failure.message ?: "Slow motion is unavailable"),
                )
            }
        }
    }

    fun stop(): SlowMotionClip? {
        playbackJob?.cancel()
        playbackJob = null
        val clip = if (sourcePositionMillis > holdStartMillis) {
            SlowMotionClip(uri, holdStartMillis, sourcePositionMillis)
        } else null
        mutableState.replaceFrame(clip?.let(HoldSlowMotionState::ReadyToSave) ?: HoldSlowMotionState.Idle)
        return clip
    }

    fun discardSavedClip() {
        if (playbackJob?.isActive != true) mutableState.replaceFrame(HoldSlowMotionState.Idle)
    }

    private suspend fun playBuffered(positionMillis: Long) = withContext(Dispatchers.Default) {
        val windowStart = positionMillis.coerceAtLeast(0L).windowStart()
        while (isActive && synchronized(bufferLock) { bufferedWindowStart != windowStart }) {
            delay(BufferPollMillis)
        }
        var cursor = synchronized(bufferLock) {
            bufferedFrames.indexOfFirst { it.sourcePositionMillis >= positionMillis }.let {
                if (it >= 0) it else bufferedFrames.size
            }
        }
        waitForInitialBuffer(cursor)
        holdStartMillis = synchronized(bufferLock) {
            bufferedFrames.getOrNull(cursor)?.sourcePositionMillis ?: positionMillis
        }
        sourcePositionMillis = holdStartMillis

        while (isActive) {
            val next = synchronized(bufferLock) { bufferedFrames.getOrNull(cursor) }
            if (next == null) {
                val status = synchronized(bufferLock) { bufferComplete to bufferFailure }
                status.second?.let(::error)
                if (status.first) break
                mutableState.replaceFrame(HoldSlowMotionState.Buffering)
                delay(BufferPollMillis)
                continue
            }
            val frame = BitmapFactory.decodeFile(next.file.absolutePath)
            if (frame == null) {
                cursor += 1
                continue
            }
            sourcePositionMillis = next.sourcePositionMillis
            mutableState.replaceFrame(
                HoldSlowMotionState.Playing(frame, sourcePositionMillis, prefetchBackend),
            )
            cursor += 1
            delay(OutputFrameMillis)
        }
    }

    private suspend fun waitForInitialBuffer(cursor: Int) {
        while (coroutineContext.isActive) {
            val status = synchronized(bufferLock) {
                val available = bufferedFrames.size - cursor
                InitialBufferStatus(
                    ready = available >= MinimumStartFrames || (bufferComplete && available > 0),
                    exhausted = bufferComplete && available <= 0,
                    failure = bufferFailure,
                )
            }
            status.failure?.let(::error)
            if (status.exhausted) error("No buffered slow-motion frames are available")
            if (status.ready) return
            delay(BufferPollMillis)
        }
    }

    private fun loadWindow(windowStart: Long) {
        val directory = windowDirectory(windowStart)
        val index = File(directory, IndexFileName)
        val loaded = if (index.isFile) {
            index.useLines { lines ->
                lines.mapNotNull { line ->
                    val parts = line.split(',', limit = 2)
                    val position = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                    val frame = parts.getOrNull(1)?.let(directory::resolve) ?: return@mapNotNull null
                    frame.takeIf(File::isFile)?.let { BufferedFrame(position, it) }
                }.toList()
            }
        } else emptyList()
        synchronized(bufferLock) {
            bufferedWindowStart = windowStart
            bufferedFrames.clear()
            bufferedFrames += loaded
            bufferComplete = File(directory, CompleteFileName).isFile
            bufferFailure = null
        }
    }

    private suspend fun renderWindow(windowStart: Long) {
        val directory = windowDirectory(windowStart)
        directory.mkdirs()
        directory.listFiles()?.forEach(File::delete)
        synchronized(bufferLock) {
            if (bufferedWindowStart != windowStart) return
            bufferedFrames.clear()
            bufferComplete = false
            bufferFailure = null
        }
        trimOldCache(directory)
        val retriever = MediaMetadataRetriever()
        val interpolator = RifeFrameInterpolator(appContext)
        var left: Bitmap? = null
        try {
            retriever.setDataSource(appContext, uri)
            val durationMillis = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(1L) ?: error("Video duration is unavailable")
            if (windowStart >= durationMillis) {
                markWindowComplete(directory, windowStart, durationMillis)
                return
            }
            val fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toFloatOrNull()?.coerceIn(15f, 60f) ?: 30f
            val stepMillis = (1_000f / fps).roundToInt().coerceAtLeast(16)
            val windowEnd = (windowStart + PrefetchWindowSourceMillis).coerceAtMost(durationMillis)
            var leftTime = windowStart
            left = frameAt(retriever, leftTime) ?: error("Could not decode the preview buffer")
            var frameIndex = 0
            prefetchBackend = interpolator.capability.backend
            FileOutputStream(File(directory, IndexFileName), false).bufferedWriter().use { writer ->
                while (coroutineContext.isActive && leftTime < windowEnd) {
                    val currentLeft = left ?: break
                    val rightTime = (leftTime + stepMillis).coerceAtMost(windowEnd)
                    if (rightTime <= leftTime) break
                    val right = frameAt(retriever, rightTime) ?: break
                    val generated = try {
                        interpolator.interpolateFactor(currentLeft, right, Factor)
                    } catch (failure: Throwable) {
                        if (!right.isRecycled) right.recycle()
                        throw failure
                    }
                    val frames = listOf(currentLeft) + generated
                    try {
                        frames.forEachIndexed { interpolationIndex, frame ->
                            coroutineContext.ensureActive()
                            val position = leftTime + ((rightTime - leftTime) * interpolationIndex / Factor)
                            val name = "frame-" + frameIndex.toString().padStart(6, '0') + ".jpg"
                            val file = File(directory, name)
                            FileOutputStream(file).use { output ->
                                check(frame.compress(Bitmap.CompressFormat.JPEG, PreviewJpegQuality, output))
                            }
                            writer.append(position.toString()).append(',').append(name).append('\n')
                            writer.flush()
                            synchronized(bufferLock) {
                                if (bufferedWindowStart == windowStart) {
                                    bufferedFrames += BufferedFrame(position, file)
                                }
                            }
                            frameIndex += 1
                        }
                    } catch (failure: Throwable) {
                        if (!right.isRecycled) right.recycle()
                        throw failure
                    } finally {
                        generated.forEach { if (!it.isRecycled) it.recycle() }
                    }
                    if (!currentLeft.isRecycled) currentLeft.recycle()
                    left = right
                    leftTime = rightTime
                }
            }
            coroutineContext.ensureActive()
            markWindowComplete(directory, windowStart, windowEnd)
            trimOldCache(directory)
        } finally {
            left?.takeIf { !it.isRecycled }?.recycle()
            interpolator.close()
            retriever.release()
        }
    }

    private fun markWindowComplete(directory: File, windowStart: Long, windowEnd: Long) {
        File(directory, CompleteFileName).writeText(windowEnd.toString())
        synchronized(bufferLock) {
            if (bufferedWindowStart == windowStart) bufferComplete = true
        }
    }

    private fun frameAt(retriever: MediaMetadataRetriever, timeMillis: Long): Bitmap? {
        val frame = retriever.getFrameAtTime(
            timeMillis * 1_000,
            MediaMetadataRetriever.OPTION_CLOSEST,
        ) ?: return null
        val longEdge = maxOf(frame.width, frame.height)
        if (longEdge <= PreviewLongEdge) return frame.asArgb8888()
        val scale = PreviewLongEdge.toFloat() / longEdge
        val scaled = Bitmap.createScaledBitmap(
            frame,
            (frame.width * scale).roundToInt().coerceAtLeast(2),
            (frame.height * scale).roundToInt().coerceAtLeast(2),
            true,
        )
        if (scaled !== frame) frame.recycle()
        return scaled.asArgb8888()
    }

    private fun windowDirectory(windowStart: Long) = File(cacheRoot, "window-" + windowStart)

    private fun trimOldCache(activeWindow: File) {
        val root = cacheRoot.parentFile ?: return
        val directories = root.listFiles()?.filter(File::isDirectory).orEmpty()
            .flatMap { video -> video.listFiles()?.filter { it.isDirectory && it.name.startsWith("window-") }.orEmpty() }
            .sortedByDescending(File::lastModified)
        var retainedBytes = 0L
        directories.forEach { directory ->
            val bytes = directory.walkTopDown().filter(File::isFile).sumOf(File::length)
            if (retainedBytes + bytes <= MaximumCacheBytes || directory == activeWindow) {
                retainedBytes += bytes
            } else {
                directory.deleteRecursively()
            }
        }
    }

    override fun close() {
        playbackJob?.cancel()
        prefetchJob?.cancel()
        mutableState.replaceFrame(HoldSlowMotionState.Idle)
        scope.cancel()
    }

    companion object {
        const val Speed = 0.25f
        private const val Factor = 4
        private const val PreviewLongEdge = 256
        private const val PreviewJpegQuality = 82
        private const val PrefetchWindowSourceMillis = 6_000L
        private const val OutputFrameMillis = 33L
        private const val BufferPollMillis = 40L
        private const val MinimumStartFrames = 24
        private const val MaximumCacheBytes = 96L * 1024L * 1024L
        private const val IndexFileName = "frames.csv"
        private const val CompleteFileName = "complete"
    }
}

private data class BufferedFrame(val sourcePositionMillis: Long, val file: File)

private data class InitialBufferStatus(
    val ready: Boolean,
    val exhausted: Boolean,
    val failure: String?,
)

private fun Long.windowStart(): Long = this / 4_000L * 4_000L

private fun Uri.cacheKey(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toString().toByteArray())
    return digest.take(12).joinToString("") { "%02x".format(it) }
}

private fun Bitmap.asArgb8888(): Bitmap {
    if (config == Bitmap.Config.ARGB_8888) return this
    val converted = copy(Bitmap.Config.ARGB_8888, false)
    recycle()
    return converted
}

private fun MutableStateFlow<HoldSlowMotionState>.replaceFrame(next: HoldSlowMotionState) {
    value = next
}
