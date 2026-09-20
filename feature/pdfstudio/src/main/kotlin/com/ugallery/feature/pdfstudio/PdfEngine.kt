package com.ugallery.feature.pdfstudio

import android.content.*
import android.os.*
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*

interface PdfEngine {
    suspend fun inspect(file: File): List<PdfPage>

    suspend fun preview(file: File, page: Int, output: File)

    suspend fun export(
        project: PdfProject,
        files: List<File>,
        output: File,
        compact: Boolean,
        progress: (Int, Int) -> Unit = { _, _ -> },
    )
}

class IsolatedPdfEngine(
    private val context: Context,
    freeBytes: (File) -> Long = { it.usableSpace },
    private val openOutput: (File) -> ParcelFileDescriptor = { output ->
        ParcelFileDescriptor.open(
            output,
            ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE or
                ParcelFileDescriptor.MODE_READ_WRITE,
        )
    },
) : PdfEngine {
    private val storage = PdfStorageBudget(freeBytes)

    private suspend fun <T> execute(id: String = newId(), block: (IPdfProcessor) -> T): T =
        withContext(Dispatchers.IO) {
            var connection: ServiceConnection? = null
            var remote: IPdfProcessor? = null
            try {
                val processor =
                    withTimeoutOrNull(30_000) {
                        suspendCancellableCoroutine<IPdfProcessor> { continuation ->
                            val c =
                                object : ServiceConnection {
                                    override fun onServiceConnected(
                                        name: ComponentName?,
                                        binder: IBinder?,
                                    ) {
                                        if (continuation.isActive)
                                            continuation.resume(
                                                IPdfProcessor.Stub.asInterface(binder)
                                            )
                                    }

                                    override fun onServiceDisconnected(name: ComponentName?) = Unit

                                    override fun onBindingDied(name: ComponentName?) {
                                        if (continuation.isActive)
                                            continuation.resumeWithException(
                                                PdfOperationFailure(PdfFailure.RendererUnavailable)
                                            )
                                    }

                                    override fun onNullBinding(name: ComponentName?) {
                                        if (continuation.isActive)
                                            continuation.resumeWithException(
                                                PdfOperationFailure(PdfFailure.RendererUnavailable)
                                            )
                                    }
                                }
                            connection = c
                            if (
                                !context.bindService(
                                    Intent(context, PdfProcessingService::class.java),
                                    c,
                                    Context.BIND_AUTO_CREATE,
                                )
                            ) {
                                connection = null
                                continuation.resumeWithException(
                                    PdfOperationFailure(PdfFailure.RendererUnavailable)
                                )
                            }
                        }
                    } ?: throw PdfOperationFailure(PdfFailure.RendererUnavailable)
                remote = processor
                coroutineContext.ensureActive()
                suspendCancellableCoroutine<T> { continuation ->
                    continuation.invokeOnCancellation {
                        cancellations.execute { runCatching { processor.cancel(id) } }
                    }
                    workers.execute operation@{
                        if (!continuation.isActive) return@operation
                        try {
                            val value = block(processor)
                            if (continuation.isActive) continuation.resume(value)
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }
                    }
                }
            } finally {
                remote?.let { processor ->
                    cancellations.execute { runCatching { processor.cancel(id) } }
                }
                connection?.let { runCatching { context.unbindService(it) } }
            }
        }

    companion object {
        private val workers =
            java.util.concurrent.Executors.newFixedThreadPool(2) { task ->
                Thread(task, "pdf-binder").apply { isDaemon = true }
            }
        private val cancellations =
            java.util.concurrent.Executors.newSingleThreadExecutor { task ->
                Thread(task, "pdf-binder-cancel").apply { isDaemon = true }
            }
    }

    override suspend fun inspect(file: File): List<PdfPage> = execute { service ->
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            val json = org.json.JSONObject(service.inspect(fd))
            if (json.has("error"))
                throw PdfOperationFailure(
                    PdfFailure.remote(json.optString("error"), PdfFailure.InvalidPdf)
                )
            val pages = json.getJSONArray("pages")
            List(pages.length()) { n ->
                val o = pages.getJSONObject(n)
                PdfPage(
                    width = o.getDouble("w"),
                    height = o.getDouble("h"),
                    sourcePage = n,
                    rotation = o.getInt("rotation"),
                    margin = 0.0,
                )
            }
        }
    }

    override suspend fun preview(file: File, page: Int, output: File) {
        storage.beforeWrite(output)
        try {
            execute { service ->
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
                    openOutput(output).use { out ->
                        val error = service.preview(input, page, 1024, out)
                        if (error.isNotEmpty()) throw PdfOperationFailure(PdfFailure.remote(error))
                    }
                }
            }
        } catch (e: Throwable) {
            if (output.isFile) output.delete()
            throw e
        }
    }

    override suspend fun export(
        project: PdfProject,
        files: List<File>,
        output: File,
        compact: Boolean,
        progress: (Int, Int) -> Unit,
    ) {
        storage.beforeWrite(output)
        val jobId = newId()
        try {
            execute(jobId) { service ->
                val fds = mutableListOf<ParcelFileDescriptor>()
                try {
                    project.validate()
                    require(files.size == project.assets.size)
                    val used = project.usedAssets()
                    val snapshot = project.copy(assets = project.assets.filter { it.hash in used })
                    val bytes = PdfExportManifest.encode(snapshot)
                    project.assets
                        .zip(files)
                        .filter { (asset, _) -> asset.hash in used }
                        .forEach { (_, file) ->
                            fds.add(
                                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                            )
                        }
                    manifestDescriptor(bytes, output).use { manifest ->
                        openOutput(output).use { out ->
                            val error =
                                service.exportPdf(
                                    jobId,
                                    manifest,
                                    compact,
                                    fds,
                                    out,
                                    object : IPdfProgress.Stub() {
                                        override fun onPage(completed: Int, total: Int) {
                                            progress(completed, total)
                                        }
                                    },
                                )
                            if (error.isNotEmpty())
                                throw PdfOperationFailure(PdfFailure.remote(error))
                        }
                    }
                } finally {
                    fds.forEach { it.close() }
                }
            }
        } catch (e: Throwable) {
            if (output.isFile) output.delete()
            throw e
        }
    }

    /** The isolated process receives bytes, never a private path or a large Binder String. */
    private fun manifestDescriptor(bytes: ByteArray, output: File): ParcelFileDescriptor {
        // Same backing volume as the local destination, even when openOutput wraps a proxy FD.
        val file = File.createTempFile("pdf-manifest-", ".json", output.absoluteFile.parentFile)
        var input: ParcelFileDescriptor? = null
        try {
            storage.beforeWrite(file, bytes.size.toLong())
            FileOutputStream(file).use { writer ->
                input = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                // Both descriptors now own the inode: process death cannot leave a named manifest.
                check(file.delete()) { "Manifest unlink failed" }
                writer.write(bytes)
            }
            return requireNotNull(input)
        } catch (e: Throwable) {
            input?.close()
            throw e
        } finally {
            file.delete()
        }
    }
}
