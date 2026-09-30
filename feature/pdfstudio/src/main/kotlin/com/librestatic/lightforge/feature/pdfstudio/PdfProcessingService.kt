package com.librestatic.lightforge.feature.pdfstudio

import android.app.Service
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
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
        pdfOperationTimeoutMillis(operation)

    protected fun <T> bounded(operation: String, id: String = newId(), block: () -> T): T =
        watchdog.run(id, operationTimeoutMillis(operation), block)

    companion object {
        private val watchdog = PdfOperationWatchdog {
            // Only the isolated renderer terminates; a dead Binder releases host worker threads.
            android.os.Process.killProcess(android.os.Process.myPid())
        }

        /** Conventional single-spacing line height as a multiple of point size (Phase G1a). */
        private const val LINE_HEIGHT_FACTOR = 1.2f
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
                                            // Fonts (Phase G1a): one PDType0Font per family/
                                            // weight combination actually used, shared across
                                            // every page/text — same caching shape as [images].
                                            val fonts =
                                                mutableMapOf<
                                                    Pair<PdfFontFamily, PdfFontWeight>, PDType0Font
                                                >()
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
                                                            renderCanvasPage(
                                                                document = document,
                                                                project = project,
                                                                p = p,
                                                                compact = compact,
                                                                images = images,
                                                                fonts = fonts,
                                                                flag = flag,
                                                                stream = ::stream,
                                                            )
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

    /** One PDF page's own content stream: images and texts interleaved in [PdfLayers] paint
     * order. Extracted from the pre-Phase-G1a per-image loop unchanged (see [drawImage]) so
     * adding the text layer doesn't change existing image export behavior. */
    private fun renderCanvasPage(
        document: PDDocument,
        project: PdfProject,
        p: PdfPage,
        compact: Boolean,
        images: MutableMap<String, PDImageXObject>,
        fonts: MutableMap<Pair<PdfFontFamily, PdfFontWeight>, PDType0Font>,
        flag: AtomicBoolean,
        stream: (String) -> InputStream,
    ) {
        val pt = 72f / 25.4f
        val dest = PDPage(PDRectangle((p.width * pt).toFloat(), (p.height * pt).toFloat()))
        document.addPage(dest)
        PDPageContentStream(document, dest).use { canvas ->
            PdfLayers.order(p).forEach { element ->
                check(!flag.get()) { "Cancelled" }
                when (element) {
                    is PdfLayers.Element.Img ->
                        drawImage(document, canvas, project, p, element.image, pt, compact, images, stream)
                    is PdfLayers.Element.Txt -> drawText(document, canvas, p, element.text, pt, fonts)
                }
            }
        }
    }

    /** Draws one image, exactly as before Phase G1a (only extracted into its own function so it
     * can interleave with [drawText] via [renderCanvasPage]/[PdfLayers]). */
    private fun drawImage(
        document: PDDocument,
        canvas: PDPageContentStream,
        project: PdfProject,
        p: PdfPage,
        i: PdfImage,
        pt: Float,
        compact: Boolean,
        images: MutableMap<String, PDImageXObject>,
        stream: (String) -> InputStream,
    ) {
        val asset = project.assets.first { it.hash == i.asset }
        val image =
            images.getOrPut(i.asset) {
                if (asset.mime == "image/jpeg" && !compact) {
                    stream(i.asset).use { JPEGFactory.createFromStream(document, it) }
                } else {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    stream(i.asset).use { BitmapFactory.decodeStream(it, null, options) }
                    require(options.outWidth > 0 && options.outHeight > 0)
                    val maxSide = if (compact) 1600 else 8192
                    options.inJustDecodeBounds = false
                    options.inSampleSize = 1
                    while (
                        max(options.outWidth, options.outHeight) / options.inSampleSize > maxSide ||
                            options.outWidth.toLong() / options.inSampleSize *
                                options.outHeight / options.inSampleSize > 16_000_000
                    )
                        options.inSampleSize *= 2
                    if (!compact && options.inSampleSize != 1)
                        throw PdfOperationFailure(PdfFailure.LimitExceeded)
                    val bitmap =
                        stream(i.asset).use { BitmapFactory.decodeStream(it, null, options) }
                            ?: error("Image decode failed")
                    var rotated = bitmap
                    try {
                        if (compact) JPEGFactory.createFromImage(document, rotated, .75f)
                        else LosslessFactory.createFromImage(document, rotated)
                    } finally {
                        if (rotated !== bitmap) rotated.recycle()
                        bitmap.recycle()
                    }
                }
            }
        val swapped = (asset.orientation >= 5) xor (i.rotation % 180 != 0)
        val iw = if (swapped) image.height else image.width
        val ih = if (swapped) image.width else image.height
        // Shared with the canvas via PdfPrintLayout.contentRect so the exporter and the on-screen
        // preview always paint the exact same Fill(Cover)/Fit(Contain) geometry (WYSIWYG).
        val content = PdfPrintLayout.contentRect(i.width, i.height, iw.toDouble(), ih.toDouble(), i.fit, i.focusX, i.focusY)
        val w = content.width
        val h = content.height
        val x = i.x + content.x
        val y = i.y + content.y
        canvas.saveGraphicsState()
        canvas.addRect(
            (i.x * pt).toFloat(),
            ((p.height - i.y - i.height) * pt).toFloat(),
            (i.width * pt).toFloat(),
            (i.height * pt).toFloat(),
        )
        canvas.clip()
        canvas.drawImage(
            image,
            orientationMatrix(
                asset.orientation,
                i.rotation,
                (x * pt).toFloat(),
                ((p.height - y - h) * pt).toFloat(),
                (w * pt).toFloat(),
                (h * pt).toFloat(),
            ),
        )
        canvas.restoreGraphicsState()
    }

    /**
     * Draws one text box (Phase G1a): word-wraps [text]'s string to the box width with
     * [PdfTextWrap] (shared, pure line-breaking — Compose will call the same function in G1b),
     * clips to the box like [drawImage] does, and positions the first baseline using the font's
     * own ascent metric (`PDFontDescriptor.getAscent()`, in 1/1000 em — the same metric backing
     * `hhea`/`OS2` ascent that Compose's `TextMeasurer` reads), so both renderers place the same
     * text at the same spot. Lines use a fixed 1.2x line-height multiple of the point size — a
     * conventional single-spacing value chosen so short boxes still fit at least one line.
     */
    private fun drawText(
        document: PDDocument,
        canvas: PDPageContentStream,
        p: PdfPage,
        text: PdfText,
        pt: Float,
        fonts: MutableMap<Pair<PdfFontFamily, PdfFontWeight>, PDType0Font>,
    ) {
        val font = font(document, fonts, text.font, text.weight)
        val sizePt = text.sizePt.toFloat()
        fun measure(s: String) = font.getStringWidth(s) / 1000f * sizePt
        val boxLeft = (text.x * pt).toFloat()
        val boxWidth = (text.width * pt).toFloat()
        val boxHeight = (text.height * pt).toFloat()
        // Top edge of the box, measured from the PDF page's bottom-left origin (same convention
        // as drawImage's addRect above).
        val boxTop = ((p.height - text.y) * pt).toFloat()
        val lineHeight = sizePt * LINE_HEIGHT_FACTOR
        val maxLines = (boxHeight / lineHeight).toInt().coerceAtLeast(0)
        val lines = PdfTextWrap.wrap(text.text, boxWidth, maxLines, ::measure)
        if (lines.isEmpty()) return
        val (r, g, b) = PdfPaperTokens.rgb(text.ink)
        canvas.saveGraphicsState()
        canvas.addRect(boxLeft, boxTop - boxHeight, boxWidth, boxHeight)
        canvas.clip()
        canvas.setNonStrokingColor(r, g, b)
        val ascent = (font.fontDescriptor?.ascent ?: 800f) / 1000f * sizePt
        var baseline = boxTop - ascent
        for (line in lines) {
            val lineWidth = measure(line)
            val x =
                when (text.align) {
                    PdfTextAlign.Start -> boxLeft
                    PdfTextAlign.Center -> boxLeft + (boxWidth - lineWidth) / 2f
                    PdfTextAlign.End -> boxLeft + boxWidth - lineWidth
                }
            canvas.beginText()
            canvas.setFont(font, sizePt)
            canvas.newLineAtOffset(x, baseline)
            canvas.showText(line)
            canvas.endText()
            baseline -= lineHeight
        }
        canvas.restoreGraphicsState()
    }

    /** One embedded PDType0Font per family/weight, loaded from the bundled asset TTF and cached
     * in [cache] for the life of the export (like [images] above). `embedSubset = true` so the
     * exported PDF only carries the glyphs it actually uses. */
    private fun font(
        document: PDDocument,
        cache: MutableMap<Pair<PdfFontFamily, PdfFontWeight>, PDType0Font>,
        family: PdfFontFamily,
        weight: PdfFontWeight,
    ): PDType0Font =
        cache.getOrPut(family to weight) {
            val name =
                when (family to weight) {
                    PdfFontFamily.Sans to PdfFontWeight.Regular -> "fonts/NotoSans-Regular.ttf"
                    PdfFontFamily.Sans to PdfFontWeight.Bold -> "fonts/NotoSans-Bold.ttf"
                    PdfFontFamily.Serif to PdfFontWeight.Regular -> "fonts/NotoSerif-Regular.ttf"
                    PdfFontFamily.Serif to PdfFontWeight.Bold -> "fonts/NotoSerif-Bold.ttf"
                    else -> error("Unreachable: every PdfFontFamily x PdfFontWeight combination is listed above")
                }
            assets.open(name).use { PDType0Font.load(document, it, true) }
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

/**
 * Production per-operation renderer deadline, shared by the watchdog in [PdfProcessingService]
 * and the host-side collateral-death check in [IsolatedPdfEngine] so the two can never disagree.
 */
internal fun pdfOperationTimeoutMillis(operation: String): Long =
    if (operation == "export") 300_000L else 60_000L
