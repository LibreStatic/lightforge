package com.ugallery.feature.pdfstudio

import android.app.Service
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.*
import com.tom_roush.pdfbox.util.Matrix
import java.io.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*
import org.json.*

/** Only file descriptors cross the boundary. This process has no application permissions. */
open class PdfProcessingService : Service() {
    // Intent extras and imported files cannot change these process-lifetime limits.
    protected open fun operationTimeoutMillis(operation: String): Long =
        if (operation == "export") 300_000L else 60_000L

    protected fun <T> bounded(operation: String, id: String = newId(), block: () -> T): T =
        watchdog.run(id, operationTimeoutMillis(operation), block)

    companion object {
        private val watchdog = PdfOperationWatchdog {
            // Only the isolated renderer terminates; a dead Binder releases host worker threads.
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private val cancelled = ConcurrentHashMap<String, AtomicBoolean>()

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
    }

    override fun onBind(intent: Intent?) =
        object : IPdfProcessor.Stub() {
            override fun cancel(id: String) {
                cancelled[id]?.set(true)
                watchdog.cancel(id)
            }

            override fun inspect(source: ParcelFileDescriptor): String =
                bounded("inspect") {
                    try {
                        source.use { fd ->
                            PdfRenderer(fd).use { renderer ->
                                if (renderer.pageCount !in 1..100)
                                    throw PdfOperationFailure(PdfFailure.LimitExceeded)
                                JSONObject()
                                    .put(
                                        "pages",
                                        JSONArray(
                                            List(renderer.pageCount) { n ->
                                                renderer.openPage(n).use { page ->
                                                    JSONObject()
                                                        .put("w", page.width * 25.4 / 72)
                                                        .put("h", page.height * 25.4 / 72)
                                                        .put("rotation", 0)
                                                }
                                            }
                                        ),
                                    )
                                    .toString()
                            }
                        }
                    } catch (e: Exception) {
                        JSONObject().put("error", PdfFailure.from(e).name).toString()
                    } catch (e: OutOfMemoryError) {
                        "{\"error\":\"MemoryPressure\"}"
                    }
                }

            override fun preview(
                source: ParcelFileDescriptor,
                page: Int,
                width: Int,
                output: ParcelFileDescriptor,
            ): String =
                bounded("preview") {
                    output.use {
                        result {
                            source.use { fd ->
                                PdfRenderer(fd).use { renderer ->
                                    renderer.openPage(page).use { p ->
                                        val scale =
                                            width.coerceIn(64, 1024).toFloat() /
                                                max(p.width, p.height)
                                        val bitmap =
                                            Bitmap.createBitmap(
                                                max(1, (p.width * scale).toInt()),
                                                max(1, (p.height * scale).toInt()),
                                                Bitmap.Config.ARGB_8888,
                                            )
                                        try {
                                            bitmap.eraseColor(Color.WHITE)
                                            p.render(
                                                bitmap,
                                                null,
                                                null,
                                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                                            )
                                            budgetedOutput(output).use {
                                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                                            }
                                        } finally {
                                            bitmap.recycle()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

            override fun exportPdf(
                id: String,
                manifest: ParcelFileDescriptor,
                compact: Boolean,
                sources: MutableList<ParcelFileDescriptor>,
                output: ParcelFileDescriptor,
                progress: IPdfProgress?,
            ): String =
                bounded("export", id) {
                    output.use {
                        manifest.use {
                            result {
                                val flag = AtomicBoolean(false)
                                cancelled[id] = flag
                                try {
                                    require(sources.size <= 128)
                                    val project =
                                        ParcelFileDescriptor.AutoCloseInputStream(
                                                ParcelFileDescriptor.dup(manifest.fileDescriptor)
                                            )
                                            .use(PdfExportManifest::read)
                                    require(sources.size == project.assets.size)
                                    val byHash =
                                        project.assets
                                            .mapIndexed { n, a -> a.hash to sources[n] }
                                            .toMap()
                                    fun stream(hash: String): InputStream {
                                        val fd =
                                            ParcelFileDescriptor.dup(
                                                byHash.getValue(hash).fileDescriptor
                                            )
                                        return ParcelFileDescriptor.AutoCloseInputStream(fd).also {
                                            it.channel.position(0)
                                        }
                                    }
                                    PDDocument(
                                            MemoryUsageSetting.setupMainMemoryOnly(
                                                128L * 1024 * 1024
                                            )
                                        )
                                        .use { document ->
                                            // Assets are content-addressed and limited to 128 per
                                            // job.
                                            // Repeated placements share encoded bytes, not just
                                            // decoded
                                            // pixels.
                                            val images = mutableMapOf<String, PDImageXObject>()
                                            PdfSourcePages(document) { hash ->
                                                    stream(hash).use {
                                                        PDDocument.load(
                                                            it,
                                                            MemoryUsageSetting.setupMainMemoryOnly(
                                                                64L * 1024 * 1024
                                                            ),
                                                        )
                                                    }
                                                }
                                                .use { imported ->
                                                    project.pages.forEachIndexed { pageIndex, p ->
                                                        check(!flag.get()) { "Cancelled" }
                                                        if (p.source != null) {
                                                            imported.append(
                                                                p.source,
                                                                p.sourcePage,
                                                                p.rotation,
                                                            )
                                                        } else {
                                                            imported.close()
                                                            val pt = 72f / 25.4f
                                                            val dest =
                                                                PDPage(
                                                                    PDRectangle(
                                                                        (p.width * pt).toFloat(),
                                                                        (p.height * pt).toFloat(),
                                                                    )
                                                                )
                                                            document.addPage(dest)
                                                            PDPageContentStream(document, dest)
                                                                .use { canvas ->
                                                                    p.images.forEach { i ->
                                                                        check(!flag.get()) {
                                                                            "Cancelled"
                                                                        }
                                                                        val asset =
                                                                            project.assets.first {
                                                                                it.hash == i.asset
                                                                            }
                                                                        val image =
                                                                            images.getOrPut(
                                                                                i.asset
                                                                            ) {
                                                                                if (
                                                                                    asset.mime ==
                                                                                        "image/jpeg" &&
                                                                                        !compact
                                                                                ) {
                                                                                    stream(i.asset)
                                                                                        .use {
                                                                                            JPEGFactory
                                                                                                .createFromStream(
                                                                                                    document,
                                                                                                    it,
                                                                                                )
                                                                                        }
                                                                                } else {
                                                                                    val options =
                                                                                        BitmapFactory
                                                                                            .Options()
                                                                                            .apply {
                                                                                                inJustDecodeBounds =
                                                                                                    true
                                                                                            }
                                                                                    stream(i.asset)
                                                                                        .use {
                                                                                            BitmapFactory
                                                                                                .decodeStream(
                                                                                                    it,
                                                                                                    null,
                                                                                                    options,
                                                                                                )
                                                                                        }
                                                                                    require(
                                                                                        options
                                                                                            .outWidth >
                                                                                            0 &&
                                                                                            options
                                                                                                .outHeight >
                                                                                                0
                                                                                    )
                                                                                    val maxSide =
                                                                                        if (compact)
                                                                                            1600
                                                                                        else 8192
                                                                                    options
                                                                                        .inJustDecodeBounds =
                                                                                        false
                                                                                    options
                                                                                        .inSampleSize =
                                                                                        1
                                                                                    while (
                                                                                        max(
                                                                                            options
                                                                                                .outWidth,
                                                                                            options
                                                                                                .outHeight,
                                                                                        ) /
                                                                                            options
                                                                                                .inSampleSize >
                                                                                            maxSide ||
                                                                                            options
                                                                                                .outWidth
                                                                                                .toLong() /
                                                                                                options
                                                                                                    .inSampleSize *
                                                                                                options
                                                                                                    .outHeight /
                                                                                                options
                                                                                                    .inSampleSize >
                                                                                                16_000_000
                                                                                    ) options
                                                                                        .inSampleSize *=
                                                                                        2
                                                                                    if (
                                                                                        !compact &&
                                                                                            options
                                                                                                .inSampleSize !=
                                                                                                1
                                                                                    )
                                                                                        throw PdfOperationFailure(
                                                                                            PdfFailure
                                                                                                .LimitExceeded
                                                                                        )
                                                                                    val bitmap =
                                                                                        stream(
                                                                                                i
                                                                                                    .asset
                                                                                            )
                                                                                            .use {
                                                                                                BitmapFactory
                                                                                                    .decodeStream(
                                                                                                        it,
                                                                                                        null,
                                                                                                        options,
                                                                                                    )
                                                                                            }
                                                                                            ?: error(
                                                                                                "Image decode failed"
                                                                                            )
                                                                                    var rotated =
                                                                                        bitmap
                                                                                    try {

                                                                                        if (compact)
                                                                                            JPEGFactory
                                                                                                .createFromImage(
                                                                                                    document,
                                                                                                    rotated,
                                                                                                    .75f,
                                                                                                )
                                                                                        else
                                                                                            LosslessFactory
                                                                                                .createFromImage(
                                                                                                    document,
                                                                                                    rotated,
                                                                                                )
                                                                                    } finally {
                                                                                        if (
                                                                                            rotated !==
                                                                                                bitmap
                                                                                        )
                                                                                            rotated
                                                                                                .recycle()
                                                                                        bitmap
                                                                                            .recycle()
                                                                                    }
                                                                                }
                                                                            }
                                                                        val swapped =
                                                                            (asset.orientation >=
                                                                                5) xor
                                                                                (i.rotation % 180 !=
                                                                                    0)
                                                                        val iw =
                                                                            if (swapped)
                                                                                image.height
                                                                            else image.width
                                                                        val ih =
                                                                            if (swapped) image.width
                                                                            else image.height
                                                                        val scale =
                                                                            if (
                                                                                i.fit ==
                                                                                    PdfFit.Cover
                                                                            )
                                                                                max(
                                                                                    i.width / iw,
                                                                                    i.height / ih,
                                                                                )
                                                                            else
                                                                                min(
                                                                                    i.width / iw,
                                                                                    i.height / ih,
                                                                                )
                                                                        val w = iw * scale
                                                                        val h = ih * scale
                                                                        val x =
                                                                            i.x +
                                                                                (i.width - w) *
                                                                                    i.focusX
                                                                        val y =
                                                                            i.y +
                                                                                (i.height - h) *
                                                                                    i.focusY
                                                                        canvas.saveGraphicsState()
                                                                        canvas.addRect(
                                                                            (i.x * pt).toFloat(),
                                                                            ((p.height -
                                                                                    i.y -
                                                                                    i.height) * pt)
                                                                                .toFloat(),
                                                                            (i.width * pt)
                                                                                .toFloat(),
                                                                            (i.height * pt)
                                                                                .toFloat(),
                                                                        )
                                                                        canvas.clip()
                                                                        canvas.drawImage(
                                                                            image,
                                                                            orientationMatrix(
                                                                                asset.orientation,
                                                                                i.rotation,
                                                                                (x * pt).toFloat(),
                                                                                ((p.height -
                                                                                        y -
                                                                                        h) * pt)
                                                                                    .toFloat(),
                                                                                (w * pt).toFloat(),
                                                                                (h * pt).toFloat(),
                                                                            ),
                                                                        )
                                                                        canvas
                                                                            .restoreGraphicsState()
                                                                    }
                                                                }
                                                        }
                                                        progress?.onPage(
                                                            pageIndex + 1,
                                                            project.pages.size,
                                                        )
                                                    }
                                                }
                                            check(!flag.get())
                                            document.documentInformation.title = project.name
                                            budgetedOutput(output, manifest).use(document::save)
                                        }
                                } finally {
                                    sources.forEach { it.close() }
                                    cancelled.remove(id)
                                }
                            }
                        }
                    }
                }
        }

    private fun budgetedOutput(
        fd: ParcelFileDescriptor,
        volume: ParcelFileDescriptor = fd,
    ): OutputStream =
        PdfBudgetOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(fd)) {
            val stat = // Manifest inode is on the intended local output volume; proxy FDs may not
                // expose statfs.
                android.system.Os.fstatvfs(volume.fileDescriptor)
            val blocks = stat.f_bavail.coerceAtLeast(0)
            val size = stat.f_frsize.coerceAtLeast(1)
            if (blocks > Long.MAX_VALUE / size) Long.MAX_VALUE else blocks * size
        }

    private fun orientationMatrix(
        exif: Int,
        rotation: Int,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
    ): Matrix {
        fun point(u: Float, v: Float): Pair<Float, Float> {
            var a = u
            var b = 1 - v
            val next =
                when (exif) {
                    2 -> 1 - a to b
                    3 -> 1 - a to 1 - b
                    4 -> a to 1 - b
                    5 -> b to a
                    6 -> 1 - b to a
                    7 -> 1 - b to 1 - a
                    8 -> b to 1 - a
                    else -> a to b
                }
            a = next.first
            b = next.second
            repeat(rotation / 90) {
                val old = a
                a = 1 - b
                b = old
            }
            return x + a * w to y + (1 - b) * h
        }
        val o = point(0f, 0f)
        val r = point(1f, 0f)
        val t = point(0f, 1f)
        return Matrix(
            r.first - o.first,
            r.second - o.second,
            t.first - o.first,
            t.second - o.second,
            o.first,
            o.second,
        )
    }

    private inline fun result(block: () -> Unit): String =
        try {
            block()
            ""
        } catch (e: Exception) {
            PdfFailure.from(e).name
        } catch (e: OutOfMemoryError) {
            PdfFailure.MemoryPressure.name
        }
}
