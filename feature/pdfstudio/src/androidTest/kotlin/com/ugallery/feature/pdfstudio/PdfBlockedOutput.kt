package com.ugallery.feature.pdfstudio

import android.content.Context
import android.os.*
import android.os.storage.StorageManager
import java.io.File
import java.io.RandomAccessFile

/** Test-only write-through FUSE gate: renderer is blocked in a real output syscall. */
internal object PdfBlockedOutput {
    fun open(context: Context, output: File, entered: (Long) -> Unit): ParcelFileDescriptor {
        val thread = HandlerThread("pdf-blocked-output").apply { start() }
        val file = RandomAccessFile(output, "rw")
        var first = true
        return context
            .getSystemService(StorageManager::class.java)
            .openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_WRITE_ONLY,
                object : ProxyFileDescriptorCallback() {
                    override fun onGetSize() = file.length()

                    override fun onWrite(offset: Long, size: Int, data: ByteArray): Int {
                        file.seek(offset)
                        file.write(data, 0, size)
                        file.fd.sync()
                        if (first) {
                            first = false
                            entered(file.length())
                        }
                        return size
                    }

                    override fun onFsync() {
                        file.fd.sync()
                    }

                    override fun onRelease() {
                        file.close()
                        thread.quitSafely()
                    }
                },
                Handler(thread.looper),
            )
    }
}
