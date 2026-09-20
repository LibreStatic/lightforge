package com.ugallery.feature.pdfstudio

import android.content.Context
import android.os.*
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants

/**
 * Test-only FUSE output: the actual renderer write syscall receives ENOSPC, without filling a disk.
 */
internal object PdfFullOutput {
    val writes = java.util.concurrent.atomic.AtomicInteger()

    fun open(context: Context): ParcelFileDescriptor {
        val thread = HandlerThread("pdf-enospc-fixture").apply { start() }
        try {
            return context
                .getSystemService(StorageManager::class.java)
                .openProxyFileDescriptor(
                    ParcelFileDescriptor.MODE_WRITE_ONLY,
                    object : ProxyFileDescriptorCallback() {
                        override fun onGetSize() = 0L

                        override fun onWrite(offset: Long, size: Int, data: ByteArray): Int {
                            writes.incrementAndGet()
                            throw ErrnoException("fixture write", OsConstants.ENOSPC)
                        }

                        override fun onFsync() = Unit

                        override fun onRelease() {
                            thread.quitSafely()
                        }
                    },
                    Handler(thread.looper),
                )
        } catch (e: Throwable) {
            thread.quitSafely()
            throw e
        }
    }
}
