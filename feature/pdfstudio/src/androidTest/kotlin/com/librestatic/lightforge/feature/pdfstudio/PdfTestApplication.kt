package com.librestatic.lightforge.feature.pdfstudio

import android.app.Application
import android.content.Context
import androidx.work.*
import java.io.File
import kotlinx.coroutines.delay

/** Test-only gate lets the external probe stop a confirmed running, durable WorkManager job. */
class PdfTestApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() =
            Configuration.Builder()
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(
                            context: Context,
                            name: String,
                            parameters: WorkerParameters,
                        ): ListenableWorker? {
                            if (name != PdfExportWorker::class.java.name) return null
                            return PdfExportWorker(context, parameters).also { worker ->
                                worker.portableWriter = { project, output, progress ->
                                    PdfProjectRepository(context).writePortable(project, output) {
                                        n,
                                        total ->
                                        progress(n, total)
                                        val pause =
                                            File(context.filesDir, "pdf-portable-probe.pause")
                                        if (n == 1 && pause.exists()) {
                                            check(output.length() > 0)
                                            File(context.filesDir, "pdf-portable-probe.entered")
                                                .writeText(android.os.Process.myPid().toString())
                                            while (pause.exists()) delay(100)
                                        }
                                    }
                                }
                                val actualPublisher = worker.publisher
                                worker.publisher = { source, destination ->
                                    val pause =
                                        File(context.filesDir, "pdf-publication-probe.pause")
                                    if (pause.exists()) {
                                        val bytes = source.readBytes()
                                        val complete =
                                            File(context.filesDir, "pdf-publication-probe.complete")
                                                .exists()
                                        context.contentResolver
                                            .openOutputStream(destination, "wt")!!
                                            .use {
                                                it.write(
                                                    bytes,
                                                    0,
                                                    if (complete) bytes.size
                                                    else minOf(80, bytes.size / 2),
                                                )
                                            }
                                        File(context.filesDir, "pdf-publication-probe.entered")
                                            .writeText(android.os.Process.myPid().toString())
                                        while (pause.exists()) delay(100)
                                    }
                                    actualPublisher(source, destination)
                                    if (
                                        File(context.filesDir, "pdf-publication-probe.truncate")
                                            .exists()
                                    ) {
                                        val prefix =
                                            source.inputStream().use { input ->
                                                ByteArray(80).also { input.read(it) }
                                            }
                                        context.contentResolver
                                            .openOutputStream(destination, "wt")!!
                                            .use { it.write(prefix) }
                                    }
                                }
                                val delegate = IsolatedPdfEngine(context)
                                worker.engine =
                                    object : PdfEngine by delegate {
                                        override suspend fun export(
                                            project: PdfProject,
                                            files: List<File>,
                                            output: File,
                                            compact: Boolean,
                                            progress: (Int, Int) -> Unit,
                                        ) {
                                            val pause =
                                                File(context.filesDir, "pdf-worker-probe.pause")
                                            if (pause.exists()) {
                                                File(context.filesDir, "pdf-worker-probe.entered")
                                                    .writeText(
                                                        android.os.Process.myPid().toString()
                                                    )
                                                while (pause.exists()) delay(100)
                                            }
                                            val target =
                                                if (
                                                    File(context.filesDir, "pdf-export-full")
                                                        .exists()
                                                )
                                                    IsolatedPdfEngine(
                                                        context,
                                                        openOutput = { PdfFullOutput.open(context) },
                                                    )
                                                else delegate
                                            target.export(project, files, output, compact, progress)
                                        }
                                    }
                            }
                        }
                    }
                )
                .build()
}
