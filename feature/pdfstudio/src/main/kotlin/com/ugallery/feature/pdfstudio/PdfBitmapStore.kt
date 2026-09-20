package com.ugallery.feature.pdfstudio

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.util.LruCache
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One decoder at a time; evictions drop references, never recycle a bitmap still shown by Compose.
 */
internal class PdfBitmapStore(
    maxBytes: Int = PdfPreviewPolicy.MEMORY_BYTES,
    private val decoder: (File, Int) -> Bitmap = ::decodePdfBitmap,
) : ComponentCallbacks2 {
    private data class Key(
        val path: String,
        val size: Long,
        val modified: Long,
        val rotation: Int,
        val side: Int,
    )

    private val cache =
        object : LruCache<Key, Bitmap>(maxBytes) {
            override fun sizeOf(key: Key, value: Bitmap) = value.allocationByteCount
        }
    private val gate = Mutex()
    private var epoch = 0
    internal val retainedBytes: Int
        get() = cache.size()

    suspend fun load(file: File, rotation: Int, maxSide: Int, immutable: Boolean = false): Bitmap =
        withContext(Dispatchers.IO) {
            require(maxSide in 1..1024)
            gate.withLock {
                currentCoroutineContext().ensureActive()
                val key =
                    Key(
                        file.canonicalPath,
                        file.length(),
                        if (immutable) 0L else file.lastModified(),
                        ((rotation % 360) + 360) % 360,
                        maxSide,
                    )
                cache.get(key)?.let {
                    return@withLock it
                }
                val generation = synchronized(cache) { epoch }
                var decoded: Bitmap? = null
                var result: Bitmap? = null
                try {
                    decoded = decoder(file, maxSide)
                    currentCoroutineContext().ensureActive()
                    result =
                        if (key.rotation == 0) decoded
                        else
                            Bitmap.createBitmap(
                                decoded,
                                0,
                                0,
                                decoded.width,
                                decoded.height,
                                Matrix().apply { postRotate(key.rotation.toFloat()) },
                                true,
                            )
                    if (result !== decoded) decoded.recycle()
                    decoded = null
                    require(result.allocationByteCount <= PdfPreviewPolicy.rotatedBytes(maxSide))
                    currentCoroutineContext().ensureActive()
                    synchronized(cache) { if (epoch == generation) cache.put(key, result) }
                    result
                } catch (e: Throwable) {
                    synchronized(cache) { if (cache.get(key) === result) cache.remove(key) }
                    decoded?.takeUnless { it.isRecycled }?.recycle()
                    result?.takeUnless { it.isRecycled }?.recycle()
                    if (e is OutOfMemoryError) {
                        onLowMemory()
                        throw PdfOperationFailure(PdfFailure.MemoryPressure)
                    }
                    throw e
                }
            }
        }

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) onLowMemory()
    }

    override fun onLowMemory() {
        synchronized(cache) {
            epoch++
            cache.evictAll()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    companion object {
        @Volatile private var instance: PdfBitmapStore? = null

        fun get(context: Context): PdfBitmapStore =
            instance
                ?: synchronized(this) {
                    instance
                        ?: PdfBitmapStore().also {
                            context.applicationContext.registerComponentCallbacks(it)
                            instance = it
                        }
                }
    }
}

internal fun decodePdfBitmap(file: File, maxSide: Int): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        val scale = minOf(1.0, maxSide.toDouble() / maxOf(info.size.width, info.size.height))
        decoder.setTargetSize(
            maxOf(1, (info.size.width * scale).toInt()),
            maxOf(1, (info.size.height * scale).toInt()),
        )
    }
