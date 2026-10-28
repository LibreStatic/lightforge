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
import java.util.concurrent.atomic.AtomicLong
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
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

/**
 * Result of [HoldSlowMotionSession.stop]. [resumeAtMillis] is the source position of the frame last
 * shown (null if none was); [clip] spans every position shown during the hold, if more than an instant.
 */
data class HoldSlowMotionStop(val resumeAtMillis: Long?, val clip: SlowMotionClip?)

sealed interface HoldSlowMotionState {
    data object Idle : HoldSlowMotionState
    data object Buffering : HoldSlowMotionState
    data class Playing(
        val frame: Bitmap,
        val sourcePositionMillis: Long,
        val backend: FrameInterpolationBackend,
        /** Signed source speed: positive forwards, negative in reverse, 0 while holding a frame. */
        val rate: Float = HoldSlowMotionSession.Speed,
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
    private var bufferWindowEnd = Long.MAX_VALUE
    private var shownPositionMillis: Long? = null
    private var visited = SlowMotionVisitedRange()
    @Volatile private var rate = 0f
    private val pendingScrub = AtomicLong(NoScrub)
    // Conflated: setRate/scrubTo only need to wake the playback loop, never to queue work.
    private val wake = Channel<Unit>(Channel.CONFLATED)

    fun prepare(positionMillis: Long) {
        if (!armed) return
        // A hold drives its own window; the poller passes the paused player position.
        if (playbackJob?.isActive == true) return
        requestWindow(windowFor(positionMillis.coerceAtLeast(0L), slowMotionWindowFor(positionMillis, 1)))
    }

    /** Existing window that can serve [target] soon (see [chooseSlowMotionWindow]), else [freshStart]. */
    private fun windowFor(target: Long, freshStart: Long): Long =
        chooseSlowMotionWindow(target, freshStart, windowCandidates(target))

    /** Cached windows that could cover [target], plus the one being prepared even if not yet on disk. */
    private fun windowCandidates(target: Long): List<SlowMotionWindowCandidate> {
        val preparing = preparingWindowStart
        val found = HashMap<Long, SlowMotionWindowCandidate>()
        cacheRoot.listFiles().orEmpty().forEach { directory ->
            val start = directory.name.removePrefix("window-").takeIf { directory.name != it }?.toLongOrNull()
                ?: return@forEach
            if (!directory.isDirectory || start > target || start + PrefetchWindowSourceMillis <= target) return@forEach
            val completeFile = File(directory, CompleteFileName)
            val complete = completeFile.isFile
            val end = if (complete) {
                runCatching { completeFile.readText().trim().toLongOrNull() }.getOrNull()
            } else null
            found[start] = SlowMotionWindowCandidate(
                start = start,
                lastRenderedMillis = lastRenderedPosition(File(directory, IndexFileName)),
                complete = complete,
                endMillis = end ?: (start + PrefetchWindowSourceMillis),
                preparing = start == preparing,
            )
        }
        if (preparing != Long.MIN_VALUE && preparing <= target && preparing !in found) {
            found[preparing] = SlowMotionWindowCandidate(
                preparing, null, false, preparing + PrefetchWindowSourceMillis, preparing = true,
            )
        }
        return found.values.toList()
    }

    /** Position on the last complete line of [index]; the tail is read so long windows stay cheap. */
    private fun lastRenderedPosition(index: File): Long? = runCatching {
        if (!index.isFile) return@runCatching null
        java.io.RandomAccessFile(index, "r").use { file ->
            val length = file.length()
            val tail = ByteArray(minOf(length, IndexTailBytes.toLong()).toInt())
            file.seek(length - tail.size)
            file.readFully(tail)
            val lines = String(tail, Charsets.UTF_8).split('\n')
            // The segment after the last newline is empty or torn; the first one may be cut at the front.
            val complete = if (length > tail.size) lines.drop(1).dropLast(1) else lines.dropLast(1)
            complete.lastOrNull()?.substringBefore(',')?.toLongOrNull()
        }
    }.getOrNull()

    private fun requestWindow(requestedWindow: Long) {
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
        shownPositionMillis = null
        visited = SlowMotionVisitedRange()
        pendingScrub.set(NoScrub)
        rate = Speed
        mutableState.replaceFrame(HoldSlowMotionState.Buffering)
        armed = true
        val position = positionMillis.coerceAtLeast(0L)
        val requestedWindow = windowFor(position, slowMotionWindowFor(position, 1))
        requestWindow(requestedWindow)
        playbackJob = scope.launch {
            try {
                playBuffered(position, requestedWindow)
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (failure: Throwable) {
                mutableState.replaceFrame(
                    HoldSlowMotionState.Failure(failure.message ?: "Slow motion is unavailable"),
                )
            }
        }
    }

    /**
     * Signed source speed while a hold is active: positive forwards, negative in reverse, 0 holds the
     * current frame. Magnitudes above 1 are clamped. Takes effect without waiting for the next frame.
     */
    fun setRate(rate: Float) {
        this.rate = clampSlowMotionRate(rate)
        wake.trySend(Unit)
    }

    /**
     * Holds the buffered frame nearest [sourceMillis], loading another window if needed. Meant for
     * high-frequency drags: only the latest target is ever decoded.
     */
    fun scrubTo(sourceMillis: Long) {
        rate = 0f
        pendingScrub.set(sourceMillis.coerceAtLeast(0L))
        wake.trySend(Unit)
    }

    fun stop(): HoldSlowMotionStop {
        playbackJob?.cancel()
        playbackJob = null
        val clip = visited.range()?.let { SlowMotionClip(uri, it.first, it.last) }
        mutableState.replaceFrame(clip?.let(HoldSlowMotionState::ReadyToSave) ?: HoldSlowMotionState.Idle)
        return HoldSlowMotionStop(shownPositionMillis, clip)
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

    // Runs on the main thread like [stop]: a frame decoded on a worker thread could otherwise be
    // published after [stop] had already moved to ReadyToSave, leaving the slow-motion overlay stuck.
    private suspend fun playBuffered(positionMillis: Long, firstWindow: Long) {
        while (coroutineContext.isActive && synchronized(bufferLock) { bufferedWindowStart != firstWindow }) {
            delay(BufferPollMillis)
        }
        var target = positionMillis
        var shownEntry: BufferedFrame? = null
        var shownBitmap: Bitmap? = null
        var shownRate = Float.NaN
        var publishedPlaying = false
        var gated = false
        while (coroutineContext.isActive) {
            val scrub = pendingScrub.getAndSet(NoScrub)
            if (scrub != NoScrub) target = scrub
            val currentRate = rate
            val view = synchronized(bufferLock) { snapshotLocked() to bufferFailure }
            view.second?.let(::error)
            when (val plan = planSlowMotionBuffer(target, view.first)) {
                SlowMotionBufferPlan.Empty -> error("No buffered slow-motion frames are available")
                SlowMotionBufferPlan.AwaitFrames -> {
                    mutableState.replaceFrame(HoldSlowMotionState.Buffering)
                    publishedPlaying = false
                    awaitWake(BufferPollMillis)
                    continue
                }
                is SlowMotionBufferPlan.Switch -> {
                    // The chosen window starts at or before the target, so it differs from the current one.
                    requestWindow(windowFor(target, plan.windowStart))
                    mutableState.replaceFrame(HoldSlowMotionState.Buffering)
                    publishedPlaying = false
                    awaitWake(BufferPollMillis)
                    continue
                }
                SlowMotionBufferPlan.Show -> Unit
            }
            val index = synchronized(bufferLock) {
                nearestFrameIndex(bufferedFrames.size, { bufferedFrames[it].sourcePositionMillis }, target)
            }
            if (index < 0) continue
            if (!gated) {
                waitForInitialBuffer(index)
                gated = true
                continue
            }
            val entry = synchronized(bufferLock) { bufferedFrames.getOrNull(index) } ?: continue
            if (entry != shownEntry) {
                val frame = withContext(Dispatchers.Default) { BitmapFactory.decodeFile(entry.file.absolutePath) }
                if (frame == null) {
                    // Undecodable frame: step past it, or wait for the next command while holding.
                    if (currentRate == 0f) awaitWake(Long.MAX_VALUE) else target = entry.sourcePositionMillis +
                        if (currentRate > 0f) 1 else -1
                    continue
                }
                shownEntry = entry
                shownBitmap = frame
                shownRate = Float.NaN
                shownPositionMillis = entry.sourcePositionMillis
                visited.include(entry.sourcePositionMillis)
            }
            val bitmap = shownBitmap ?: continue
            if (!publishedPlaying || shownRate != currentRate) {
                mutableState.replaceFrame(
                    HoldSlowMotionState.Playing(bitmap, entry.sourcePositionMillis, prefetchBackend, currentRate),
                )
                shownRate = currentRate
                publishedPlaying = true
            }
            val direction = if (currentRate > 0f) 1 else if (currentRate < 0f) -1 else 0
            if (direction == 0) {
                awaitWake(Long.MAX_VALUE)
                continue
            }
            val neighbour = synchronized(bufferLock) { bufferedFrames.getOrNull(index + direction) }
            if (neighbour != null) {
                // Pace by source timestamps so 15 fps CCTV and 60 fps phone footage both play at the rate.
                val delayMillis = slowMotionPaceDelayMillis(
                    neighbour.sourcePositionMillis - entry.sourcePositionMillis,
                    currentRate,
                ) ?: continue
                if (!sleepUnlessInterrupted(delayMillis, currentRate)) target = neighbour.sourcePositionMillis
                continue
            }
            // At the edge of the buffer: step past it so the plan can switch window, wait for the
            // renderer, or (at the start/end of the video) keep holding this frame.
            val beyond = entry.sourcePositionMillis + direction
            if (planSlowMotionBuffer(beyond, view.first) == SlowMotionBufferPlan.Show) {
                awaitWake(Long.MAX_VALUE)
            } else {
                target = beyond
            }
        }
    }

    private fun snapshotLocked(): SlowMotionBufferSnapshot = SlowMotionBufferSnapshot(
        windowStart = bufferedWindowStart,
        count = bufferedFrames.size,
        firstPositionMillis = bufferedFrames.firstOrNull()?.sourcePositionMillis ?: 0L,
        lastPositionMillis = bufferedFrames.lastOrNull()?.sourcePositionMillis ?: 0L,
        complete = bufferComplete,
        windowEndMillis = bufferWindowEnd,
    )

    /** True if a command arrived (or [timeoutMillis] elapsed without one: false). */
    private suspend fun awaitWake(timeoutMillis: Long): Boolean =
        if (timeoutMillis == Long.MAX_VALUE) {
            wake.receive()
            true
        } else {
            withTimeoutOrNull(timeoutMillis) { wake.receive() } != null
        }

    /** Sleeps [millis]; returns true if a scrub or a rate change cut the wait short. */
    private suspend fun sleepUnlessInterrupted(millis: Long, rateAtStart: Float): Boolean {
        val deadline = System.nanoTime() + millis * 1_000_000L
        while (true) {
            val remainingMillis = (deadline - System.nanoTime()) / 1_000_000L
            if (remainingMillis <= 0L) return false
            if (!awaitWake(remainingMillis)) return false
            if (pendingScrub.get() != NoScrub || rate != rateAtStart) return true
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
            var previous = Long.MIN_VALUE
            index.useLines { lines ->
                lines.mapNotNull { line ->
                    val parts = line.split(',', limit = 2)
                    val position = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                    val frame = parts.getOrNull(1)?.let(directory::resolve) ?: return@mapNotNull null
                    // Positions must increase; anything else is a torn or stale line.
                    if (position <= previous || !frame.isFile) return@mapNotNull null
                    previous = position
                    BufferedFrame(position, frame)
                }.toList()
            }
        } else emptyList()
        synchronized(bufferLock) {
            bufferedWindowStart = windowStart
            bufferedFrames.clear()
            bufferedFrames += loaded
            bufferComplete = File(directory, CompleteFileName).isFile
            bufferWindowEnd = if (bufferComplete) {
                File(directory, CompleteFileName).readText().trim().toLongOrNull()
                    ?: (windowStart + PrefetchWindowSourceMillis)
            } else windowStart + PrefetchWindowSourceMillis
            bufferFailure = null
        }
    }

    private suspend fun renderWindow(windowStart: Long) {
        val directory = windowDirectory(windowStart)
        directory.mkdirs()
        // Frames [loadWindow] found are kept and the window resumes after them, so a window cancelled
        // half-way is not rendered again from its start.
        val resumed = synchronized(bufferLock) {
            if (bufferedWindowStart != windowStart) return
            bufferComplete = false
            bufferWindowEnd = windowStart + PrefetchWindowSourceMillis
            bufferFailure = null
            bufferedFrames.toList()
        }
        withContext(Dispatchers.IO) {
            if (resumed.isEmpty()) {
                directory.listFiles()?.forEach(File::delete)
                return@withContext
            }
            // Rewrite the index from the loaded frames (dropping a torn last line) and delete the rest.
            File(directory, IndexFileName).bufferedWriter().use { writer ->
                resumed.forEach { writer.append(it.sourcePositionMillis.toString()).append(',').append(it.file.name).append('\n') }
            }
            val referenced = resumed.mapTo(HashSet()) { it.file.name }
            directory.listFiles()?.forEach { file ->
                if (file.name != IndexFileName && file.name !in referenced) file.delete()
            }
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
            val lastResumed = resumed.lastOrNull()?.sourcePositionMillis
            if (lastResumed == null) {
                reader.seekTo(windowStart)
                left = reader.next() ?: error("Could not decode the preview buffer")
            } else {
                // The next decoded frame is the right side of the pair that was being written.
                reader.seekTo(lastResumed + 1)
                var next = reader.next()
                while (next != null && next.timeMillis <= lastResumed) {
                    next.bitmap.recycle()
                    next = reader.next()
                }
                left = next
                if (left == null) {
                    markWindowComplete(directory, windowStart, windowEnd)
                    return
                }
            }
            leftLow = if (guided) left.bitmap.toLowResolution() else null
            var frameIndex = resumed.maxOfOrNull { it.file.nameWithoutExtension.removePrefix("frame-").toIntOrNull() ?: -1 }
                ?.plus(1) ?: 0
            prefetchBackend = interpolator.capability.backend
            val quality = if (guided) GuidedJpegQuality else CpuJpegQuality
            // JPEG encoding of the high-resolution frames runs beside the next RIFE call; the single
            // consumer keeps frame order, and the bounded queue throttles RIFE if encoding lags.
            coroutineScope {
                val queue = Channel<EncodeJob>(EncodeQueueCapacity) { it.bitmap.recycle() }
                val encoder = launch(Dispatchers.IO) {
                    FileOutputStream(File(directory, IndexFileName), resumed.isNotEmpty()).bufferedWriter().use { writer ->
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
            if (bufferedWindowStart == windowStart) {
                bufferComplete = true
                bufferWindowEnd = windowEnd
            }
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
        private const val PrefetchWindowSourceMillis = SlowMotionWindowSpanMillis
        private const val IndexTailBytes = 512
        private const val NoScrub = Long.MIN_VALUE
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

private fun Uri.cacheKey(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toString().toByteArray())
    return digest.take(12).joinToString("") { "%02x".format(it) }
}

private fun MutableStateFlow<HoldSlowMotionState>.replaceFrame(next: HoldSlowMotionState) {
    value = next
}
