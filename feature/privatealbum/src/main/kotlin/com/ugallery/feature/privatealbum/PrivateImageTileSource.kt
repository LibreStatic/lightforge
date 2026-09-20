package com.ugallery.feature.privatealbum

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.exifinterface.media.ExifInterface
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** IO-only decoder; Rects and dimensions use display orientation, including mirrored EXIF1..8.
 * Plaintext exists only in decoder/RAM and the readonly proxy, never in a filesystem cache.
 * The UI owns [source], and must also erase/recycle displayed tiles when its lease is revoked.
 */
internal class PrivateImageTileSource(context: Context, private val source: PrivateViewerSource) : AutoCloseable {
    private val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val decodeLock = Any()
    private val closeLock = Any()
    private val released = CountDownLatch(1)
    private val worker: HandlerThread
    private val descriptor: ParcelFileDescriptor
    private val decoder: BitmapRegionDecoder
    private val rawWidth: Int
    private val rawHeight: Int
    private val orientation: Int
    val width: Int
    val height: Int

    init {
        requireIoThread()
        ensureValid()
        require(source.metadata.plaintextBytes > 0) { "Empty private image" }
        worker = HandlerThread("private-image-${source.metadata.mediaId}").apply { start() }
        var opened: ParcelFileDescriptor? = null
        var created: BitmapRegionDecoder? = null
        try {
            val proxy = context.applicationContext.getSystemService(StorageManager::class.java)
                .openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY,
                    object : ProxyFileDescriptorCallback() {
                        private fun checkAccess() {
                            if (closed.get() || !source.valid.value) throw ErrnoException("private image", OsConstants.EACCES)
                        }
                        override fun onGetSize(): Long {
                            checkAccess()
                            return source.metadata.plaintextBytes
                        }
                        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                            checkAccess()
                            if (offset < 0 || size < 0 || size > data.size) throw ErrnoException("private image", OsConstants.EINVAL)
                            if (size == 0 || offset >= source.metadata.plaintextBytes) return 0
                            val requested = minOf(size.toLong(), source.metadata.plaintextBytes - offset).toInt()
                            try {
                                val count = source.readAt(offset, data, 0, requested)
                                checkAccess()
                                if (count <= 0 || count > requested) throw IOException("Private image ended early")
                                return count
                            } catch (failure: Exception) {
                                data.fill(0, 0, requested)
                                throw ErrnoException("private image", if (closed.get() || !source.valid.value) OsConstants.EACCES else OsConstants.EIO, failure)
                            }
                        }
                        override fun onWrite(offset: Long, size: Int, data: ByteArray): Int =
                            throw ErrnoException("readonly private image", OsConstants.EBADF)
                        override fun onFsync() { checkAccess() }
                        override fun onRelease() {
                            released.countDown()
                            worker.quitSafely()
                        }
                    }, Handler(worker.looper))
            opened = proxy
            val exif = ExifInterface(proxy.fileDescriptor)
            orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                .takeIf { it in 1..8 } ?: ExifInterface.ORIENTATION_NORMAL
            Os.lseek(proxy.fileDescriptor, 0L, OsConstants.SEEK_SET)
            @Suppress("DEPRECATION")
            val regionDecoder = BitmapRegionDecoder.newInstance(proxy.fileDescriptor, false)
                ?: throw IOException("Unsupported private image")
            created = regionDecoder
            rawWidth = regionDecoder.width
            rawHeight = regionDecoder.height
            if (rawWidth <= 0 || rawHeight <= 0) throw IOException("Invalid private image dimensions")
            val swapped = orientation in 5..8
            width = if (swapped) rawHeight else rawWidth
            height = if (swapped) rawWidth else rawHeight
            ensureValid()
            descriptor = proxy
            decoder = regionDecoder
            // Compose effects can be paused while an Activity is stopped. This owner is independent.
            lifetime.launch {
                source.valid.first { !it }
                close()
            }
        } catch (failure: Throwable) {
            closed.set(true)
            lifetime.cancel()
            try { created?.recycle() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            try { opened?.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            try { finishWorker(opened != null) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    fun decodeRegion(region: Rect, sampleSize: Int): Bitmap {
        requireIoThread()
        return synchronized(decodeLock) {
            ensureValid()
            require(sampleSize in 1..MAX_SAMPLE && (sampleSize and (sampleSize - 1)) == 0) { "Power-of-two sample required" }
            val requested = Rect(region)
            require(requested.left >= 0 && requested.top >= 0 && requested.right <= width && requested.bottom <= height && !requested.isEmpty) {
                "Private tile is outside image bounds"
            }
            val raw = displayRegionToRaw(requested, orientation, rawWidth, rawHeight)
            val sampledWidth = (raw.width().toLong() + sampleSize - 1) / sampleSize
            val sampledHeight = (raw.height().toLong() + sampleSize - 1) / sampleSize
            require(sampledWidth > 0 && sampledHeight > 0 && sampledWidth * sampledHeight <= MAX_TILE_PIXELS) {
                "Private tile exceeds its bitmap budget"
            }
            var result: Bitmap? = null
            try {
                val decoded = decoder.decodeRegion(raw, BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }) ?: throw IOException("Private tile decoding failed")
                result = decoded
                ensureValid()
                if (orientation != ExifInterface.ORIENTATION_NORMAL) {
                    val oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height,
                        orientationMatrix(orientation, decoded.width, decoded.height), false)
                    if (oriented !== decoded) { result = oriented; decoded.recycle() }
                }
                ensureValid()
                checkNotNull(result)
            } catch (failure: Throwable) {
                result?.recycle()
                throw failure
            }
        }
    }

    override fun close() {
        requireIoThread()
        synchronized(closeLock) { closeOwned() }
    }

    private fun closeOwned() {
        if (!closed.compareAndSet(false, true)) return
        lifetime.cancel()
        var failure: Throwable? = null
        synchronized(decodeLock) {
            try { decoder.recycle() } catch (error: Throwable) { failure = error }
            try { descriptor.close() } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        try { finishWorker(true) } catch (error: Throwable) {
            if (failure == null) failure = error else failure!!.addSuppressed(error)
        }
        failure?.let { throw it }
    }

    private fun finishWorker(hasDescriptor: Boolean) {
        var interrupted = false
        var releaseObserved = !hasDescriptor
        try {
            if (hasDescriptor) releaseObserved = released.await(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) { interrupted = true }
        finally { worker.quitSafely() }
        try { worker.join(CLOSE_WAIT_MS) } catch (_: InterruptedException) { interrupted = true }
        if (interrupted) Thread.currentThread().interrupt()
        if (worker.isAlive || !releaseObserved) throw IOException("Private image proxy did not finish closing")
    }

    private fun ensureValid() {
        if (closed.get() || !source.valid.value) throw IOException("Private image source was revoked")
    }

    private companion object {
        const val MAX_SAMPLE = 1 shl 20
        const val MAX_TILE_PIXELS = 4_194_304L
        const val CLOSE_WAIT_MS = 2_000L

        fun requireIoThread() {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Private image work must run off main" }
        }

        // Integer edge transforms avoid float rounding when a large image supplies a small tile.
        fun displayRegionToRaw(rect: Rect, orientation: Int, w: Int, h: Int): Rect {
            val l = rect.left; val t = rect.top; val r = rect.right; val b = rect.bottom
            return when (orientation) {
                2 -> Rect(w - r, t, w - l, b)
                3 -> Rect(w - r, h - b, w - l, h - t)
                4 -> Rect(l, h - b, r, h - t)
                5 -> Rect(t, l, b, r)
                6 -> Rect(t, h - r, b, h - l)
                7 -> Rect(w - b, h - r, w - t, h - l)
                8 -> Rect(w - b, l, w - t, r)
                else -> Rect(rect)
            }
        }

        fun orientationMatrix(orientation: Int, width: Int, height: Int): Matrix {
            val w = width.toFloat()
            val h = height.toFloat()
            val values = when (orientation) {
                2 -> floatArrayOf(-1f, 0f, w, 0f, 1f, 0f, 0f, 0f, 1f)
                3 -> floatArrayOf(-1f, 0f, w, 0f, -1f, h, 0f, 0f, 1f)
                4 -> floatArrayOf(1f, 0f, 0f, 0f, -1f, h, 0f, 0f, 1f)
                5 -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
                6 -> floatArrayOf(0f, -1f, h, 1f, 0f, 0f, 0f, 0f, 1f)
                7 -> floatArrayOf(0f, -1f, h, -1f, 0f, w, 0f, 0f, 1f)
                8 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, w, 0f, 0f, 1f)
                else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            }
            return Matrix().apply { setValues(values) }
        }
    }
}
