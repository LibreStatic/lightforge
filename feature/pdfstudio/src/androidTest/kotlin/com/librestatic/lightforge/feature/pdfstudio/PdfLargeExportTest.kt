package com.librestatic.lightforge.feature.pdfstudio

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PdfLargeExportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun dense(project: PdfProject): PdfProject =
        project
            .copy(
                name = "Large local export",
                pages =
                    List(100) { page ->
                        PdfPage(
                            id = "page-$page-".padEnd(80, 'p'),
                            images =
                                List(24) { index ->
                                    PdfImage(
                                        id = "image-$index-".padEnd(80, 'i'),
                                        asset =
                                            project.assets[
                                                    (page * 24 + index) % project.assets.size]
                                                .hash,
                                        x = 10.0 + index % 6 * 30,
                                        y = 10.0 + index / 6 * 30,
                                        width = 20.0,
                                        height = 20.0,
                                    )
                                },
                        )
                    },
            )
            .validate()

    private fun image(index: Int): File =
        File.createTempFile("large-export-", ".png", context.cacheDir).also { file ->
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(index, 255 - index, index / 2))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }

    @Test
    fun maximumProjectExportsOriginalAndCompactAcrossBinder(): Unit = runBlocking {
        val files = List(128) { image(it) }
        val output = File(context.cacheDir, "large-export-${newId()}.pdf")
        val engine = IsolatedPdfEngine(context)
        try {
            val project =
                dense(
                    PdfProject(
                        name = "Maximum",
                        assets =
                            files.map {
                                PdfAsset(PdfProjectRepository.sha256(it), "image/png", 8, 8)
                            },
                    )
                )
            assertEquals(128, project.usedAssets().size)
            val bytes = PdfCodec.encode(project).toByteArray().size
            assertTrue(bytes > 384 * 1024)
            android.util.Log.i(
                "PdfLargeExport",
                "manifestBytes=$bytes pages=100 images=2400 sources=128",
            )
            for (compact in listOf(false, true)) {
                engine.export(project, files, output, compact)
                assertEquals(100, engine.inspect(output).size)
                assertTrue(output.length() > 0)
                com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
                com.tom_roush.pdfbox.pdmodel.PDDocument.load(output).use { document ->
                    document.pages.forEach { page ->
                        assertEquals(24, page.resources.xObjectNames.count())
                    }
                }
                assertNoManifestHandles()
            }
        } finally {
            files.forEach { it.delete() }
            output.delete()
        }
    }

    @Test
    fun maximumLayoutSurvivesRoomSnapshotAndRealWorker(): Unit = runBlocking {
        val repo = PdfProjectRepository(context)
        val queue = PdfExportQueue(context)
        val source = image(99)
        var project = PdfProject(name = "Large queue")
        var job: PdfExportJob? = null
        try {
            project = repo.import(project, listOf(Uri.fromFile(source)), 0) { _, _ -> }
            val maximum = dense(project)
            job = queue.enqueue(maximum, false)
            assertTrue(job.manifest.toByteArray().size > 384 * 1024)
            withTimeout(90_000) {
                while (true) {
                    val current = requireNotNull(queue.get(job.id))
                    check(current.phase != PdfExportPhase.Failed) { current.error.orEmpty() }
                    if (current.phase == PdfExportPhase.Ready) {
                        assertEquals(maximum, PdfCodec.decode(current.manifest))
                        assertEquals(100, current.completed)
                        break
                    }
                    delay(50)
                }
            }
            assertEquals(100, IsolatedPdfEngine(context).inspect(queue.output(job.id)).size)
        } finally {
            job?.let {
                queue.cancel(it.id)
                queue.remove(it.id)
            }
            repo.delete(project.id)
            source.delete()
        }
    }

    @Test
    fun unusedMetadataDoesNotConsumeDescriptorSlotsOrRequireMissingFiles(): Unit = runBlocking {
        val source = image(17)
        val output = File(context.cacheDir, "unused-export-${newId()}.pdf")
        try {
            val asset = PdfAsset(PdfProjectRepository.sha256(source), "image/png", 8, 8)
            val unused =
                List(160) { PdfAsset(it.toString(16).padStart(64, '0'), "image/png", 8, 8) }
            val project =
                PdfProject(
                        name = "Only referenced sources",
                        assets = unused + asset,
                        pages =
                            listOf(
                                PdfPage(
                                    images =
                                        listOf(
                                            PdfImage(
                                                asset = asset.hash,
                                                width = 20.0,
                                                height = 20.0,
                                            )
                                        )
                                )
                            ),
                    )
                    .validate()
            IsolatedPdfEngine(context)
                .export(
                    project,
                    unused.map { File(context.cacheDir, "missing-${it.hash}") } + source,
                    output,
                    false,
                )
            assertEquals(1, IsolatedPdfEngine(context).inspect(output).size)
            assertTrue(context.cacheDir.listFiles()!!.none { it.name.startsWith("pdf-manifest-") })
        } finally {
            source.delete()
            output.delete()
        }
    }

    @Test
    fun isolatedManifestValidationReturnsTypedErrorsAndKeepsServiceUsable(): Unit = runBlocking {
        val connected = java.util.concurrent.CompletableFuture<IPdfProcessor>()
        val connection =
            object : android.content.ServiceConnection {
                override fun onServiceConnected(
                    name: android.content.ComponentName?,
                    binder: android.os.IBinder?,
                ) {
                    connected.complete(IPdfProcessor.Stub.asInterface(binder))
                }

                override fun onServiceDisconnected(name: android.content.ComponentName?) = Unit
            }
        assertTrue(
            context.bindService(
                android.content.Intent(context, PdfProcessingService::class.java),
                connection,
                android.content.Context.BIND_AUTO_CREATE,
            )
        )
        val manifest = File.createTempFile("manifest-input-", ".json", context.cacheDir)
        val output = File.createTempFile("manifest-output-", ".pdf", context.cacheDir)
        try {
            val service =
                withContext(Dispatchers.IO) {
                    connected.get(15, java.util.concurrent.TimeUnit.SECONDS)
                }
            fun call(bytes: ByteArray): String {
                manifest.writeBytes(bytes)
                return android.os.ParcelFileDescriptor.open(
                        manifest,
                        android.os.ParcelFileDescriptor.MODE_READ_ONLY,
                    )
                    .use { input ->
                        android.os.ParcelFileDescriptor.open(
                                output,
                                android.os.ParcelFileDescriptor.MODE_READ_WRITE or
                                    android.os.ParcelFileDescriptor.MODE_TRUNCATE,
                            )
                            .use { out ->
                                service.exportPdf(newId(), input, false, emptyList(), out, null)
                            }
                    }
            }
            assertEquals("LimitExceeded", call(ByteArray(PdfExportManifest.LIMIT + 1) { 32 }))
            assertEquals("InvalidInput", call(byteArrayOf(0xff.toByte())))
            assertEquals(0L, output.length())
            assertEquals(
                "",
                call(PdfExportManifest.encode(PdfProject(name = "After invalid input"))),
            )
            assertEquals(1, IsolatedPdfEngine(context).inspect(output).size)
        } finally {
            context.unbindService(connection)
            manifest.delete()
            output.delete()
        }
    }

    private fun assertNoManifestHandles() {
        assertTrue(context.cacheDir.listFiles()!!.none { it.name.startsWith("pdf-manifest-") })
        val retained =
            File("/proc/self/fd")
                .listFiles()!!
                .mapNotNull { runCatching { android.system.Os.readlink(it.path) }.getOrNull() }
                .filter { it.contains("pdf-manifest-") }
        assertTrue("Retained manifest descriptors: $retained", retained.isEmpty())
    }

    @Test
    fun manifestSpaceLossReturnsStorageFullBeforeOpeningOutput(): Unit = runBlocking {
        var checks = 0
        val output = File(context.cacheDir, "manifest-space-${newId()}.pdf")
        val engine =
            IsolatedPdfEngine(
                context,
                freeBytes = { if (++checks == 1) Long.MAX_VALUE else 0 },
                openOutput = { error("Output must not open after manifest preflight fails") },
            )
        try {
            val error =
                runCatching {
                        engine.export(PdfProject(name = "Space loss"), emptyList(), output, false)
                    }
                    .exceptionOrNull()!!
            assertEquals(PdfFailure.StorageFull, PdfFailure.from(error))
            assertEquals(2, checks)
            assertFalse(output.exists())
            assertNoManifestHandles()
        } finally {
            output.delete()
        }
    }

    @Test
    fun outputOpenFailureClosesAlreadyUnlinkedManifest(): Unit = runBlocking {
        val output = File(context.cacheDir, "manifest-open-${newId()}.pdf")
        val engine =
            IsolatedPdfEngine(context, openOutput = { throw java.io.IOException("ENOSPC") })
        try {
            val error =
                runCatching {
                        engine.export(
                            PdfProject(name = "Output failure"),
                            emptyList(),
                            output,
                            false,
                        )
                    }
                    .exceptionOrNull()!!
            assertEquals(PdfFailure.StorageFull, PdfFailure.from(error))
            assertFalse(output.exists())
            assertNoManifestHandles()
        } finally {
            output.delete()
        }
    }
}
