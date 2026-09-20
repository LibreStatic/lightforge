package com.ugallery.feature.pdfstudio

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PdfRuntimePressureTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo = PdfProjectRepository(context)

    private fun image(large: Boolean = false): File {
        val f = File.createTempFile("pressure-", ".png", context.cacheDir)
        val side = if (large) 256 else 32
        val b = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        if (large) {
            val random = java.util.Random(91)
            b.setPixels(
                IntArray(side * side) { random.nextInt() or (0xff shl 24) },
                0,
                side,
                0,
                0,
                side,
                side,
            )
        }
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        b.recycle()
        return f
    }

    @Test
    fun actualExportCancellationRemovesPartialAndNextExportSucceeds(): Unit = runBlocking {
        val engine = IsolatedPdfEngine(context)
        val p = PdfProject(name = "Cancellation pressure", pages = List(100) { PdfPage() })
        val partial = File(context.cacheDir, "cancel-pressure-${newId()}.pdf")
        val next = File(context.cacheDir, "after-cancel-${newId()}.pdf")
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val job = async {
            engine.export(p, emptyList(), partial, false) { n, _ ->
                if (n == 1) {
                    entered.complete(Unit)
                    check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                }
            }
        }
        try {
            withTimeout(15_000) { entered.await() }
            job.cancel()
            release.countDown()
            job.join()
            assertTrue(job.isCancelled)
            assertFalse(partial.exists())
            engine.export(p.copy(pages = p.pages.take(1)), emptyList(), next, false)
            assertEquals(1, engine.inspect(next).size)
        } finally {
            release.countDown()
            job.cancelAndJoin()
            partial.delete()
            next.delete()
        }
    }

    @Test
    fun realEnospcCrossesIsolatedBinderAndNextExportSucceeds(): Unit = runBlocking {
        val engine = IsolatedPdfEngine(context)
        val p = PdfProject(name = "Kernel ENOSPC")
        val writesBefore = PdfFullOutput.writes.get()
        val error =
            runCatching {
                    IsolatedPdfEngine(context, openOutput = { PdfFullOutput.open(context) })
                        .export(
                            p,
                            emptyList(),
                            File(context.cacheDir, "enospc-${newId()}.pdf"),
                            false,
                        )
                }
                .exceptionOrNull()!!
        assertEquals(PdfFailure.StorageFull, PdfFailure.from(error))
        assertEquals(PdfFailure.StorageFull, (error as PdfOperationFailure).failure)
        assertTrue(PdfFullOutput.writes.get() > writesBefore)
        val output = File(context.cacheDir, "after-enospc-${newId()}.pdf")
        try {
            engine.export(p, emptyList(), output, false)
            assertEquals(1, engine.inspect(output).size)
        } finally {
            output.delete()
        }
    }

    @Test
    fun lowSpaceImportLeavesProjectReceiptAndSourcesUnchanged(): Unit = runBlocking {
        val source = image(true)
        val p = PdfProject(name = "Low space source")
        repo.save(p)
        val before = repo.load(p.id)
        val request =
            PdfImportRequest(
                newId(),
                false,
                p.id,
                p.pages[0].id,
                listOf(Uri.fromFile(source).toString()),
            )
        try {
            var checks = 0
            val constrained =
                PdfProjectRepository(
                    context,
                    freeBytes = {
                        if (++checks == 1) PdfStorageBudget.RESERVE_BYTES + 65536
                        else PdfStorageBudget.RESERVE_BYTES
                    },
                )
            val error =
                runCatching { constrained.importPicked(request) { _, _ -> } }.exceptionOrNull()!!
            assertEquals(PdfFailure.StorageFull, PdfFailure.from(error))
            assertEquals(2, checks)
            assertEquals(before, repo.load(p.id))
            assertNull(PdfProjectDatabase.get(context).imports().get(request.id))
            assertTrue(
                File(context.filesDir, "pdf-studio").listFiles()!!.none {
                    it.isDirectory && it.name.startsWith("import-")
                }
            )
            assertEquals(1, repo.importPicked(request) { _, _ -> }.pages[0].images.size)
        } finally {
            repo.delete(p.id)
            source.delete()
        }
    }

    @Test
    fun lowSpacePortableImportAndExportRemoveOnlyTheirAttempt(): Unit = runBlocking {
        val source = image()
        val p =
            repo.import(PdfProject(name = "Portable space"), listOf(Uri.fromFile(source)), 0) { _, _
                ->
            }
        val archive = repo.portable(p)
        val out = File(context.cacheDir, "full-portable-${newId()}.zip")
        try {
            val constrained = PdfProjectRepository(context, freeBytes = { 0 })
            assertEquals(
                PdfFailure.StorageFull,
                PdfFailure.from(
                    runCatching { constrained.importPortable(Uri.fromFile(archive)) }
                        .exceptionOrNull()!!
                ),
            )
            assertEquals(
                PdfFailure.StorageFull,
                PdfFailure.from(
                    runCatching { constrained.writePortable(p, out) }.exceptionOrNull()!!
                ),
            )
            assertFalse(out.exists())
            assertTrue(archive.isFile)
            assertTrue(repo.file(p.assets.single().hash).isFile)
            val restored = repo.importPortable(Uri.fromFile(archive))
            assertEquals(1, restored.pages[0].images.size)
            repo.delete(restored.id)
        } finally {
            repo.delete(p.id)
            source.delete()
            archive.delete()
            out.delete()
        }
    }

    @Test
    fun queuedRealEnospcPersistsTypedFailureAndRetryProducesPdf(): Unit = runBlocking {
        val marker = File(context.filesDir, "pdf-export-full")
        val queue = PdfExportQueue(context)
        val p = PdfProject(name = "Queued storage failure")
        marker.writeText("fixture kernel sink")
        val job = queue.enqueue(p, false)
        try {
            withTimeout(20_000) {
                while (queue.get(job.id)?.phase != PdfExportPhase.Failed) delay(25)
            }
            assertEquals(PdfFailure.StorageFull.name, queue.get(job.id)!!.error)
            assertFalse(queue.output(job.id).exists())
            marker.delete()
            queue.retry(job.id)
            withTimeout(20_000) {
                while (queue.get(job.id)?.phase != PdfExportPhase.Ready) delay(25)
            }
            assertEquals(1, IsolatedPdfEngine(context).inspect(queue.output(job.id)).size)
        } finally {
            marker.delete()
            queue.cancel(job.id)
            queue.remove(job.id)
        }
    }
}
