package com.librestatic.lightforge.core.thumbnail

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.CancellationSignal
import android.util.Size
import com.librestatic.lightforge.core.mediastore.MediaStoreUriFactory
import com.librestatic.lightforge.core.model.MediaKey
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class ThumbnailRequest(
    val mediaKey: MediaKey,
    val generationModified: Long,
    val widthPx: Int,
    val heightPx: Int,
) {
    init {
        require(generationModified >= 0)
        require(widthPx > 0 && heightPx > 0)
    }
}

fun interface ThumbnailSource {
    fun load(request: ThumbnailRequest, cancellationSignal: CancellationSignal): Bitmap
}

/** Small synchronized weighted LRU used to make the memory ceiling deterministic and testable. */
class WeightedLruCache<K : Any, V : Any>(
    val maxWeight: Long,
    private val weightOf: (V) -> Long,
) {
    private val values = LinkedHashMap<K, V>(16, 0.75f, true)
    var currentWeight: Long = 0
        private set

    init { require(maxWeight > 0) }

    @Synchronized fun get(key: K): V? = values[key]

    @Synchronized fun put(key: K, value: V) {
        val weight = weightOf(value).coerceAtLeast(0)
        values.put(key, value)?.let { currentWeight -= weightOf(it).coerceAtLeast(0) }
        currentWeight += weight
        trimTo(maxWeight)
    }

    @Synchronized fun trimTo(targetWeight: Long) {
        val iterator = values.entries.iterator()
        while (currentWeight > targetWeight.coerceAtLeast(0) && iterator.hasNext()) {
            currentWeight -= weightOf(iterator.next().value).coerceAtLeast(0)
            iterator.remove()
        }
    }

    @Synchronized fun clear() = trimTo(0)
    @Synchronized fun size(): Int = values.size
    @Synchronized fun entries(): List<Pair<K, V>> = values.map { it.key to it.value }
}

enum class ThumbnailLoadPriority(internal val order: Int) { Visible(0), Prefetch(1) }

class ThumbnailLoader(
    private val source: ThumbnailSource,
    maxCacheBytes: Long,
    threadCount: Int = 3,
    private val executor: ExecutorService = priorityExecutor(threadCount),
    val prefetchPolicy: ThumbnailPrefetchPolicy? = null,
    private val callbackContext: Context? = null,
) : Closeable, ComponentCallbacks2 {
    private val cache = WeightedLruCache<ThumbnailRequest, Bitmap>(maxCacheBytes) {
        it.allocationByteCount.toLong()
    }
    private val inFlightLock = Any()
    private val inFlight = mutableMapOf<ThumbnailRequest, InFlightRequest>()
    private val retainedWindows = mutableMapOf<Any, Map<ThumbnailRequest, Bitmap>>()
    private val sequence = AtomicLong()
    private val mutableMemoryPressureGeneration = MutableStateFlow(0L)
    val memoryPressureGeneration = mutableMemoryPressureGeneration.asStateFlow()

    init {
        require(threadCount in 1..8)
        callbackContext?.registerComponentCallbacks(this)
    }

    fun cached(request: ThumbnailRequest): Bitmap? = cache.get(request)

    fun bestCached(mediaKey: MediaKey, generationModified: Long): Bitmap? {
        val retained = synchronized(inFlightLock) {
            retainedWindows.values.asSequence()
                .flatMap { it.entries.asSequence() }
                .map { it.key to it.value }
                .toList()
        }
        return (retained + cache.entries()).asSequence()
            .filter { (request, bitmap) ->
                request.mediaKey == mediaKey && request.generationModified == generationModified && !bitmap.isRecycled
            }
            .maxByOrNull { (request, _) -> request.widthPx.toLong() * request.heightPx }
            ?.second
    }

    fun retainWindow(owner: Any, values: Map<ThumbnailRequest, Bitmap>) {
        synchronized(inFlightLock) { retainedWindows[owner] = values.toMap() }
    }

    fun releaseWindow(owner: Any) {
        synchronized(inFlightLock) { retainedWindows.remove(owner) }
    }

    suspend fun load(
        request: ThumbnailRequest,
        priority: ThumbnailLoadPriority = ThumbnailLoadPriority.Visible,
    ): Bitmap = cache.get(request) ?: suspendCancellableCoroutine { continuation ->
        var taskToStart: PrioritizedDecodeTask? = null
        var taskToPromote: PrioritizedDecodeTask? = null
        synchronized(inFlightLock) {
            cache.get(request)?.let { cached ->
                continuation.resumeWith(Result.success(cached))
                return@synchronized
            }
            val existing = inFlight[request]?.takeUnless { it.signal.isCanceled }
            if (existing == null) {
                val signal = CancellationSignal()
                val flight = InFlightRequest(signal)
                val task = PrioritizedDecodeTask(priority, sequence.incrementAndGet()) {
                    decode(request, flight)
                }
                flight.task = task
                flight.waiters += continuation
                inFlight[request] = flight
                taskToStart = task
            } else {
                existing.waiters += continuation
                val task = existing.task
                if (task != null && priority.order < task.priority.order) taskToPromote = task
            }
        }
        continuation.invokeOnCancellation {
            synchronized(inFlightLock) {
                val flight = inFlight[request] ?: return@synchronized
                flight.waiters.remove(continuation)
                if (flight.waiters.none { it.isActive }) flight.signal.cancel()
            }
        }
        taskToPromote?.let(::promote)
        taskToStart?.let(executor::execute)
    }

    private fun decode(request: ThumbnailRequest, flight: InFlightRequest) {
        val result = runCatching { source.load(request, flight.signal) }
        val waiters = synchronized(inFlightLock) {
            if (inFlight[request] === flight) inFlight.remove(request)
            flight.waiters.toList()
        }
        val active = waiters.filter { it.isActive }
        result.onSuccess { bitmap ->
            if (active.isEmpty()) {
                bitmap.recycle()
            } else {
                cache.put(request, bitmap)
                active.forEach { it.resumeWith(Result.success(bitmap)) }
            }
        }.onFailure { failure ->
            active.forEach { it.resumeWith(Result.failure(failure)) }
        }
    }

    private fun promote(task: PrioritizedDecodeTask) {
        val pool = executor as? ThreadPoolExecutor ?: return
        if (pool.queue.remove(task)) {
            task.priority = ThumbnailLoadPriority.Visible
            pool.execute(task)
        }
    }

    override fun onTrimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> cache.clear()
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> cache.trimTo(cache.maxWeight / 4)
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> cache.trimTo(cache.maxWeight / 2)
            else -> return
        }
        synchronized(inFlightLock) {
            retainedWindows.clear()
        }
        mutableMemoryPressureGeneration.value++
    }

    override fun onLowMemory() = onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    override fun close() {
        callbackContext?.unregisterComponentCallbacks(this)
        executor.shutdownNow()
        synchronized(inFlightLock) {
            inFlight.values.forEach { it.signal.cancel() }
            inFlight.clear()
            retainedWindows.clear()
        }
        cache.clear()
    }

    private class InFlightRequest(val signal: CancellationSignal) {
        val waiters = mutableListOf<CancellableContinuation<Bitmap>>()
        var task: PrioritizedDecodeTask? = null
    }

    private class PrioritizedDecodeTask(
        var priority: ThumbnailLoadPriority,
        private val sequence: Long,
        private val block: () -> Unit,
    ) : Runnable, Comparable<PrioritizedDecodeTask> {
        override fun run() = block()
        override fun compareTo(other: PrioritizedDecodeTask): Int =
            compareValuesBy(this, other, { it.priority.order }, { it.sequence })
    }

    companion object {
        fun native(context: Context, decoder: NativeImageDecoder, maxCacheBytes: Long): ThumbnailLoader =
            ThumbnailLoader(
                source = ThumbnailSource { request, signal ->
                    decoder.thumbnail(
                        MediaStoreUriFactory.uriFor(request.mediaKey),
                        Size(request.widthPx, request.heightPx),
                        signal,
                    )
                },
                maxCacheBytes = maxCacheBytes,
                prefetchPolicy = ThumbnailPrefetchPolicy.detect(context, maxCacheBytes),
                callbackContext = context.applicationContext,
            )

        private fun priorityExecutor(threadCount: Int): ExecutorService {
            val queue: BlockingQueue<Runnable> = PriorityBlockingQueue()
            return ThreadPoolExecutor(
                threadCount,
                threadCount,
                0L,
                TimeUnit.MILLISECONDS,
                queue,
            )
        }
    }
}
