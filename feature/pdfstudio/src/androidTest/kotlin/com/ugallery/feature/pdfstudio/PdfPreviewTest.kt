package com.ugallery.feature.pdfstudio

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PdfPreviewTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun png(file: File, size: Int = 1024) {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun decodeKeysIncludeSizeRotationAndRevisionAndEvictionDoesNotRecycleDisplayedPixels(): Unit =
        runBlocking {
            val source = File.createTempFile("bitmap-store-", ".png", context.cacheDir)
            png(source)
            val store = PdfBitmapStore(128 * 1024)
            try {
                val small = store.load(source, 0, 64)
                assertSame(small, store.load(source, 0, 64))
                val larger = store.load(source, 90, 256)
                assertEquals(64, small.width)
                assertEquals(256, larger.width)
                assertNotSame(small, larger)
                assertTrue(store.retainedBytes <= 128 * 1024)
                assertFalse(small.isRecycled)
                assertFalse(larger.isRecycled)
                store.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
                assertEquals(0, store.retainedBytes)
                assertEquals(android.graphics.Color.BLUE, small.getPixel(0, 0))
                val next = store.load(source, 0, 64)
                assertNotSame(small, next)
                source.setLastModified(source.lastModified() + 2000)
                assertNotSame(next, store.load(source, 0, 64))
            } finally {
                source.delete()
                store.onLowMemory()
            }
        }

    @Test
    fun decoderMemoryFailureReleasesSlotAndCacheWithoutRecyclingDisplayedImage(): Unit =
        runBlocking {
            val first = File.createTempFile("preview-oom-a-", ".png", context.cacheDir)
            val second = File.createTempFile("preview-oom-b-", ".png", context.cacheDir)
            png(first)
            png(second)
            var fail = false
            val store =
                PdfBitmapStore(
                    decoder = { file, side ->
                        if (fail) {
                            fail = false
                            throw OutOfMemoryError("injected decoder pressure")
                        }
                        decodePdfBitmap(file, side)
                    }
                )
            try {
                val displayed = store.load(first, 0, 128)
                fail = true
                val error = runCatching { store.load(second, 0, 128) }.exceptionOrNull()!!
                assertEquals(PdfFailure.MemoryPressure, PdfFailure.from(error))
                assertEquals(0, store.retainedBytes)
                assertFalse(displayed.isRecycled)
                assertEquals(128, store.load(second, 0, 128).width)
            } finally {
                first.delete()
                second.delete()
                store.onLowMemory()
            }
        }

    @Test
    fun denseRotatedCanvasStaysWithinItsDeclaredPixelBudget(): Unit = runBlocking {
        val directory = File(context.cacheDir, "dense-preview-${newId()}").apply { mkdirs() }
        val store = PdfBitmapStore()
        try {
            val source = File(directory, "source.png")
            png(source)
            val side = PdfPreviewPolicy.side(24, false)
            val bitmaps = coroutineScope {
                (0 until 24)
                    .map { index ->
                        async {
                            val file = File(directory, "$index.png")
                            source.copyTo(file)
                            store.load(file, 45, side)
                        }
                    }
                    .awaitAll()
            }
            assertTrue(
                bitmaps.sumOf { it.allocationByteCount.toLong() } <= PdfPreviewPolicy.CANVAS_BYTES
            )
            assertTrue(store.retainedBytes <= PdfPreviewPolicy.MEMORY_BYTES)
            assertTrue(bitmaps.all { !it.isRecycled })
        } finally {
            store.onLowMemory()
            directory.deleteRecursively()
        }
    }

    @Test
    fun repositorySharesIsolatedPdfPreviewAcrossSizesAndRepairsColdCache(): Unit = runBlocking {
        val input = File.createTempFile("preview-vector-", ".pdf", context.cacheDir)
        val document = android.graphics.pdf.PdfDocument()
        try {
            val page =
                document.startPage(
                    android.graphics.pdf.PdfDocument.PageInfo.Builder(200, 100, 1).create()
                )
            page.canvas.drawColor(android.graphics.Color.RED)
            document.finishPage(page)
            input.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
        val real = IsolatedPdfEngine(context)
        val renders = java.util.concurrent.atomic.AtomicInteger()
        val counted =
            object : PdfEngine by real {
                override suspend fun preview(file: File, page: Int, output: File) {
                    renders.incrementAndGet()
                    real.preview(file, page, output)
                }
            }
        val repo = PdfProjectRepository(context, counted)
        val project =
            repo.import(
                PdfProject(name = "Real preview cache"),
                listOf(android.net.Uri.fromFile(input)),
                0,
            ) { _, _ ->
            }
        val page = project.pages.last()
        val cached = File(context.cacheDir, "pdf-previews-v1/${page.source}-${page.sourcePage}.png")
        try {
            cached.delete()
            val results = coroutineScope {
                (0 until 8)
                    .map { n -> async { repo.previewBitmap(page, if (n % 2 == 0) 64 else 256)!! } }
                    .awaitAll()
            }
            assertEquals(1, renders.get())
            assertEquals(64, results[0].width)
            assertEquals(256, results[1].width)
            assertSame(results[0], results[2])
            assertSame(results[1], results[3])
            val rotated = repo.previewBitmap(page.copy(rotation = 90), 128)!!
            assertEquals(64, rotated.width)
            assertEquals(128, rotated.height)
            cached.writeText("damaged cache")
            PdfBitmapStore.get(context).onLowMemory()
            assertEquals(128, repo.previewBitmap(page, 128)!!.width)
            assertEquals(2, renders.get())
            assertEquals(PdfProjectRepository.sha256(input), page.source)
        } finally {
            repo.delete(project.id)
            input.delete()
            cached.delete()
            PdfBitmapStore.get(context).onLowMemory()
        }
    }

    @Test
    fun realPngReadbackRepairsCorruptDiskPreviewAndKeepsOriginal(): Unit = runBlocking {
        val root = File(context.cacheDir, "preview-disk-${newId()}").apply { mkdirs() }
        val original = File(root, "original.png")
        png(original)
        val originalHash = PdfProjectRepository.sha256(original)
        val cache = PdfDiskPreviewCache(root)
        val store = PdfBitmapStore()
        val hash = "b".repeat(64)
        var renders = 0
        suspend fun read() =
            cache.read(
                hash,
                0,
                {
                    renders++
                    original.copyTo(it, overwrite = true)
                },
                { store.load(it, 0, 128) },
            )
        try {
            assertEquals(128, read().width)
            val cached = File(root, "pdf-previews-v1/$hash-0.png")
            // Correct PNG signature, damaged payload: corruption is detected by actual
            // ImageDecoder.
            cached.writeBytes(cached.readBytes().copyOf(40))
            store.onLowMemory()
            assertEquals(128, read().width)
            assertEquals(2, renders)
            assertEquals(originalHash, PdfProjectRepository.sha256(original))
        } finally {
            store.onLowMemory()
            root.deleteRecursively()
        }
    }
}
