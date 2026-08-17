package com.ugallery.core.thumbnail

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.os.CancellationSignal
import android.util.Size
import com.ugallery.core.mediastore.MediaStoreUriFactory
import com.ugallery.core.model.MediaKey
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
}

class ThumbnailLoader(
    private val source: ThumbnailSource,
    maxCacheBytes: Long,
    threadCount: Int = 3,
    private val executor: ExecutorService = Executors.newFixedThreadPool(threadCount),
) : Closeable {
    private val cache = WeightedLruCache<ThumbnailRequest, Bitmap>(maxCacheBytes) {
        it.allocationByteCount.toLong()
    }

    init {
        require(threadCount in 1..8)
    }

    fun cached(request: ThumbnailRequest): Bitmap? = cache.get(request)

    suspend fun load(request: ThumbnailRequest): Bitmap = cache.get(request)
        ?: suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            executor.execute {
                try {
                    val bitmap = source.load(request, signal)
                    if (continuation.isActive) {
                        cache.put(request, bitmap)
                        continuation.resumeWith(Result.success(bitmap))
                    } else {
                        bitmap.recycle()
                    }
                } catch (failure: Throwable) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(failure))
                }
            }
        }

    fun onTrimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> cache.clear()
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> cache.trimTo(cache.maxWeight / 4)
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> cache.trimTo(cache.maxWeight / 2)
        }
    }

    override fun close() {
        executor.shutdownNow()
        cache.clear()
    }

    companion object {
        fun native(decoder: NativeImageDecoder, maxCacheBytes: Long): ThumbnailLoader =
            ThumbnailLoader(
                source = ThumbnailSource { request, signal ->
                    decoder.thumbnail(
                        MediaStoreUriFactory.uriFor(request.mediaKey),
                        Size(request.widthPx, request.heightPx),
                        signal,
                    )
                },
                maxCacheBytes = maxCacheBytes,
            )
    }
}
