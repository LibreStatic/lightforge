package com.librestatic.lightforge.feature.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.librestatic.lightforge.core.frameinterpolation.DecodedVideoFrame
import com.librestatic.lightforge.core.frameinterpolation.FrameInterpolationBackend
import com.librestatic.lightforge.core.frameinterpolation.FrameInterpolationEngine
import com.librestatic.lightforge.core.frameinterpolation.RifeFrameInterpolator
import com.librestatic.lightforge.core.frameinterpolation.VideoFrameReader
import com.librestatic.lightforge.core.frameinterpolation.interpolateFactor
import com.librestatic.lightforge.core.frameinterpolation.interpolateFactorGuided
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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
 * Maintains a persistent reduced-resolution RIFE buffer ahead of playback once the user first touches
 * the video ([warmUp] on pointer-down) or holds; until then [prepare] is a no-op, so merely viewing
 * a video never runs interpolation.
 * Export still reads the original media at full quality.
 */
class HoldSlowMotionSession(
    context: Context,
    private val uri: Uri,
    private val engine: FrameInterpolationEngine = FrameInterpolationEngine.Automatic,
) : Closeable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow<HoldSlowMotionState>(HoldSlowMotionState.Idle)
    val state: StateFlow<HoldSlowMotionState> = mutableState

    private val bufferLock = Any()
    private val bufferedFrames = ArrayList<BufferedFrame>()
    // Bump the suffix whenever the buffer's resolution or encoding changes so stale frames are not reused.
    private val cacheRoot = File(appContext.cacheDir, "hold-slow-motion/" + uri.cacheKey() + "-v3")
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

    /**
     * Stops rendering the buffer and frees its decoder and interpolator. Frames already written stay
     * cached; the next [warmUp] or [start] resumes the window from scratch.
     */
    fun suspendBuffering() {
        prefetchJob?.cancel()
        prefetchJob = null
        preparingWindowStart = Long.MIN_VALUE
        armed = false
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
        val interpolator = RifeFrameInterpolator(appContext, engine = engine)
        // Vulkan runs RIFE on a small copy only to get its flow and mask, then composes the
        // frames from the high-resolution decode; the CPU backend interpolates at low resolution.
        val guided = interpolator.capability.supportsGuided
        val reader = try {
            VideoFrameReader(appContext, uri, maxLongEdge = if (guided) HighLongEdge else CpuPreviewLongEdge)
        } catch (failure: Throwable) {
            interpolator.close()
            throw failure
        }
        var left: DecodedVideoFrame? = null
        var leftLow: Bitmap? = null
        try {
            val durationMillis = reader.durationMillis
            if (windowStart >= durationMillis) {
                markWindowComplete(directory, windowStart, durationMillis)
                return
            }
            val windowEnd = (windowStart + PrefetchWindowSourceMillis).coerceAtMost(durationMillis)
            reader.seekTo(windowStart)
            left = reader.next() ?: error("Could not decode the preview buffer")
            leftLow = if (guided) left.bitmap.toLowResolution() else null
            var frameIndex = 0
            prefetchBackend = interpolator.capability.backend
            val quality = if (guided) GuidedJpegQuality else CpuJpegQuality
            // JPEG encoding of the high-resolution frames runs beside the next RIFE call; the single
            // consumer keeps frame order, and the bounded queue throttles RIFE if encoding lags.
            coroutineScope {
                val queue = Channel<EncodeJob>(EncodeQueueCapacity) { it.bitmap.recycle() }
                val encoder = launch(Dispatchers.IO) {
                    FileOutputStream(File(directory, IndexFileName), false).bufferedWriter().use { writer ->
                        for (job in queue) {
                            try {
                                val file = File(directory, job.name)
                                FileOutputStream(file).use { output ->
                                    check(job.bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output))
                                }
                                writer.append(job.positionMillis.toString()).append(',').append(job.name).append('\n')
                                writer.flush()
                                synchronized(bufferLock) {
                                    if (bufferedWindowStart == windowStart) {
                                        bufferedFrames += BufferedFrame(job.positionMillis, file)
                                    }
                                }
                            } finally {
                                job.bitmap.recycle()
                            }
                        }
                    }
                }
                try {
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
                        var rightLow: Bitmap? = null
                        val generated = try {
                            if (guided) rightLow = right.bitmap.toLowResolution()
                            when {
                                factor == 1 -> emptyList()
                                guided -> interpolator.interpolateFactorGuided(
                                    leftLow!!, rightLow!!, currentLeft.bitmap, right.bitmap, factor,
                                )
                                else -> interpolator.interpolateFactor(currentLeft.bitmap, right.bitmap, factor)
                            }
                        } catch (failure: Throwable) {
                            right.bitmap.recycle()
                            rightLow?.recycle()
                            throw failure
                        }
                        // Ownership of every sent bitmap passes to the encoder, which recycles it.
                        val frames = listOf(currentLeft.bitmap) + generated
                        left = null
                        var sent = 0
                        try {
                            frames.forEachIndexed { interpolationIndex, frame ->
                                val position = currentLeft.timeMillis + gapMillis * interpolationIndex / factor
                                val name = "frame-" + frameIndex.toString().padStart(6, '0') + ".jpg"
                                frameIndex += 1
                                queue.send(EncodeJob(frame, position, name))
                                sent += 1
                            }
                        } catch (failure: Throwable) {
                            right.bitmap.recycle()
                            rightLow?.recycle()
                            frames.drop(sent).forEach { if (!it.isRecycled) it.recycle() }
                            throw failure
                        }
                        leftLow?.recycle()
                        leftLow = rightLow
                        left = right
                    }
                    queue.close()
                } catch (failure: Throwable) {
                    // Cancelling (not closing) hands still-queued bitmaps to onUndeliveredElement.
                    queue.cancel()
                    throw failure
                }
            }
            coroutineContext.ensureActive()
            markWindowComplete(directory, windowStart, windowEnd)
            trimOldCache(directory)
        } finally {
            left?.bitmap?.takeIf { !it.isRecycled }?.recycle()
            leftLow?.takeIf { !it.isRecycled }?.recycle()
            reader.close()
            interpolator.close()
        }
    }

    /** Always a new, even-sized bitmap whose long edge is at most [LowLongEdge]. */
    private fun Bitmap.toLowResolution(): Bitmap {
        val scale = minOf(1f, LowLongEdge.toFloat() / maxOf(width, height))
        val lowWidth = ((width * scale).toInt() and 1.inv()).coerceAtLeast(2)
        val lowHeight = ((height * scale).toInt() and 1.inv()).coerceAtLeast(2)
        val scaled = Bitmap.createScaledBitmap(this, lowWidth, lowHeight, true)
        return if (scaled === this) copy(Bitmap.Config.ARGB_8888, false) else scaled
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
        /**
         * Playback consumes one 30 fps source gap every 133 ms and needs three RIFE frames for it.
         * RIFE cannot be made sharper by feeding it more pixels on the razr fold's GPU (warm latency:
         * 256 px 18 ms, 320 px 25-31 ms, 352 px 40 ms, 480 px 48 ms, 640 px 82 ms), so on Vulkan it
         * runs at [LowLongEdge] only to produce its flow and fusion mask, and each frame is composed
         * from the [HighLongEdge] decode (320 -> 1280 px: ~41 ms per frame, of which ~14 ms is the
         * CPU compose; 1280x720 JPEG q88 encodes in ~11-16 ms and takes 200-270 KB).
         * Encoding runs beside the next RIFE call, so a pair costs about 3 x 41 ms.
         */
        private const val HighLongEdge = 1280
        private const val LowLongEdge = 320
        private const val CpuPreviewLongEdge = 256
        // The buffer is shown full screen, so JPEG block artefacts get magnified.
        private const val GuidedJpegQuality = 88
        private const val CpuJpegQuality = 92
        private const val EncodeQueueCapacity = 8
        private const val PrefetchWindowSourceMillis = 6_000L
        private const val OutputFrameMillis = 33L
        private const val MinimumOutputFrameMillis = 16L
        private const val MaximumOutputFrameMillis = 100L
        private const val BufferPollMillis = 40L
        private const val MinimumStartFrames = 24
        private const val MaximumCacheBytes = 256L * 1024L * 1024L
        private const val IndexFileName = "frames.csv"
        private const val CompleteFileName = "complete"
    }
}

private class EncodeJob(val bitmap: Bitmap, val positionMillis: Long, val name: String)

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
