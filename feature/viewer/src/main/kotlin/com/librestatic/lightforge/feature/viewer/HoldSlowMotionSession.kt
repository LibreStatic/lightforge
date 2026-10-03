package com.librestatic.lightforge.feature.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.librestatic.lightforge.core.frameinterpolation.DecodedVideoFrame
import com.librestatic.lightforge.core.frameinterpolation.FrameInterpolationBackend
import com.librestatic.lightforge.core.frameinterpolation.RifeFrameInterpolator
import com.librestatic.lightforge.core.frameinterpolation.VideoFrameReader
import com.librestatic.lightforge.core.frameinterpolation.interpolateFactor
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
            // Pace by source timestamps so 15 fps CCTV and 60 fps phone footage both play at [Speed].
            val following = synchronized(bufferLock) { bufferedFrames.getOrNull(cursor) }
            delay(
                following?.let { ((it.sourcePositionMillis - next.sourcePositionMillis) / Speed).toLong() }
                    ?.coerceIn(MinimumOutputFrameMillis, MaximumOutputFrameMillis)
                    ?: OutputFrameMillis,
            )
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
        val reader = VideoFrameReader(appContext, uri, maxLongEdge = PreviewLongEdge)
        val interpolator = try {
            RifeFrameInterpolator(appContext)
        } catch (failure: Throwable) {
            reader.close()
            throw failure
        }
        var left: DecodedVideoFrame? = null
        try {
            val durationMillis = reader.durationMillis
            if (windowStart >= durationMillis) {
                markWindowComplete(directory, windowStart, durationMillis)
                return
            }
            val windowEnd = (windowStart + PrefetchWindowSourceMillis).coerceAtMost(durationMillis)
            reader.seekTo(windowStart)
            left = reader.next() ?: error("Could not decode the preview buffer")
            var frameIndex = 0
            prefetchBackend = interpolator.capability.backend
            FileOutputStream(File(directory, IndexFileName), false).bufferedWriter().use { writer ->
                while (coroutineContext.isActive) {
                    val currentLeft = left ?: break
                    if (currentLeft.timeMillis >= windowEnd) break
                    val right = reader.next() ?: break
                    val gapMillis = right.timeMillis - currentLeft.timeMillis
                    if (gapMillis <= 0 || right.bitmap.width != currentLeft.bitmap.width ||
                        right.bitmap.height != currentLeft.bitmap.height
                    ) {
                        right.bitmap.recycle()
                        continue
                    }
                    val factor = interpolationFactor(gapMillis)
                    val generated = try {
                        if (factor == 1) emptyList()
                        else interpolator.interpolateFactor(currentLeft.bitmap, right.bitmap, factor)
                    } catch (failure: Throwable) {
                        right.bitmap.recycle()
                        throw failure
                    }
                    val frames = listOf(currentLeft.bitmap) + generated
                    try {
                        frames.forEachIndexed { interpolationIndex, frame ->
                            coroutineContext.ensureActive()
                            val position = currentLeft.timeMillis + gapMillis * interpolationIndex / factor
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
                        right.bitmap.recycle()
                        throw failure
                    } finally {
                        generated.forEach { if (!it.isRecycled) it.recycle() }
                    }
                    currentLeft.bitmap.recycle()
                    left = right
                }
            }
            coroutineContext.ensureActive()
            markWindowComplete(directory, windowStart, windowEnd)
            trimOldCache(directory)
        } finally {
            left?.bitmap?.takeIf { !it.isRecycled }?.recycle()
            reader.close()
            interpolator.close()
        }
    }

    private fun markWindowComplete(directory: File, windowStart: Long, windowEnd: Long) {
        File(directory, CompleteFileName).writeText(windowEnd.toString())
        synchronized(bufferLock) {
            if (bufferedWindowStart == windowStart) bufferComplete = true
        }
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
        private const val PreviewLongEdge = 256
        private const val PreviewJpegQuality = 82
        private const val PrefetchWindowSourceMillis = 6_000L
        private const val OutputFrameMillis = 33L
        private const val MinimumOutputFrameMillis = 16L
        private const val MaximumOutputFrameMillis = 100L
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

/**
 * Intermediate frames per source pair so output frames land roughly every 33 ms at
 * [HoldSlowMotionSession.Speed]: 4 for 30 fps, 8 for 15 fps CCTV, 2 for 60 fps.
 */
internal fun interpolationFactor(sourceGapMillis: Long): Int = when {
    sourceGapMillis <= 12L -> 1
    sourceGapMillis <= 24L -> 2
    sourceGapMillis <= 48L -> 4
    else -> 8
}

private fun Long.windowStart(): Long = this / 4_000L * 4_000L

private fun Uri.cacheKey(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toString().toByteArray())
    return digest.take(12).joinToString("") { "%02x".format(it) }
}

private fun MutableStateFlow<HoldSlowMotionState>.replaceFrame(next: HoldSlowMotionState) {
    value = next
}
