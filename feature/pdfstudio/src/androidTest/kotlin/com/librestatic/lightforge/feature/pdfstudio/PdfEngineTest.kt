package com.librestatic.lightforge.feature.pdfstudio

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PdfEngineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun temp(ext: String) = File.createTempFile("pdf-test-", ext, context.cacheDir)

    private fun vector(): File =
        temp(".pdf").also { file ->
            val doc = PdfDocument()
            try {
                val p = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
                p.canvas.drawText(
                    "Vector text survives",
                    40f,
                    60f,
                    Paint().apply {
                        textSize = 20f
                        color = Color.BLACK
                    },
                )
                doc.finishPage(p)
                file.outputStream().use(doc::writeTo)
            } finally {
                doc.close()
            }
        }

    private fun jpeg(): File =
        temp(".jpg").also { file ->
            val b = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.drawColor(Color.RED)
            c.drawRect(200f, 0f, 400f, 200f, Paint().apply { color = Color.BLUE })
            file.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            b.recycle()
        }

    @Test
    fun isolatedExportPreservesVectorTextAndImagePage() = runBlocking {
        PDFBoxResourceLoader.init(context)
        val source = vector()
        val image = jpeg()
        val engine = IsolatedPdfEngine(context)
        val preview = temp(".png")
        val output = temp(".pdf")
        try {
            val imported = engine.inspect(source)
            assertEquals(1, imported.size)
            engine.preview(source, 0, preview)
            assertNotNull(BitmapFactory.decodeFile(preview.path))
            val a = PdfProjectRepository.sha256(source)
            val b = PdfProjectRepository.sha256(image)
            val p =
                PdfProject(
                    name = "Actual export",
                    assets =
                        listOf(PdfAsset(a, "application/pdf"), PdfAsset(b, "image/jpeg", 400, 200)),
                    pages =
                        listOf(
                            imported.single().copy(source = a),
                            PdfPage(
                                images = listOf(PdfImage(asset = b, width = 100.0, height = 100.0))
                            ),
                        ),
                )
            val pages = java.util.concurrent.CopyOnWriteArrayList<Pair<Int, Int>>()
            engine.export(p, listOf(source, image), output, false) { n, total ->
                pages.add(n to total)
            }
            kotlinx.coroutines.withTimeout(5000) {
                while (pages.size < 2) kotlinx.coroutines.delay(20)
            }
            assertEquals(listOf(1 to 2, 2 to 2), pages.toList())
            assertEquals(2, engine.inspect(output).size)
            PDDocument.load(output).use { doc ->
                assertTrue(PDFTextStripper().getText(doc).contains("Vector text survives"))
                assertEquals(2, doc.numberOfPages)
            }
            val raster = temp(".png")
            try {
                engine.preview(output, 1, raster)
                val bitmap = BitmapFactory.decodeFile(raster.path)
                assertNotNull(bitmap)
                bitmap.recycle()
            } finally {
                raster.delete()
            }
            engine.export(p, listOf(source, image), output, true)
            assertEquals(2, engine.inspect(output).size)
        } finally {
            listOf(source, image, preview, output).forEach { it.delete() }
        }
    }

    @Test
    fun durableSourcesDeduplicateAndPortableRoundTrips() = runBlocking {
        val repo = PdfProjectRepository(context)
        val image = jpeg()
        var p = PdfProject(name = "Durable project")
        var imported: PdfProject? = null
        var portable: File? = null
        try {
            p = repo.import(p, listOf(Uri.fromFile(image), Uri.fromFile(image)), 0) { _, _ -> }
            assertEquals(1, p.assets.size)
            assertEquals(2, p.pages.single().images.size)
            image.delete()
            assertTrue(repo.file(p.assets.single().hash).exists())
            assertNotNull(repo.load(p.id))
            portable = repo.portable(p)
            imported = repo.importPortable(Uri.fromFile(portable))
            assertNotEquals(p.id, imported.id)
            assertEquals(p.pages, imported.pages)
            repo.delete(p.id)
            assertTrue(repo.file(imported.assets.single().hash).exists())
        } finally {
            repo.delete(p.id)
            imported?.let { repo.delete(it.id) }
            portable?.delete()
            image.delete()
        }
    }

    @Test
    fun badBatchDoesNotChangeSavedProject() = runBlocking {
        val repo = PdfProjectRepository(context)
        val p = PdfProject(name = "Atomic import")
        repo.save(p)
        val good = jpeg()
        val bad = temp(".pdf").apply { writeText("%PDF-broken") }
        try {
            val error =
                runCatching {
                        repo.import(p, listOf(Uri.fromFile(good), Uri.fromFile(bad)), 0) { _, _ -> }
                    }
                    .exceptionOrNull()
            assertNotNull(error)
            assertTrue(repo.load(p.id)!!.pages.single().images.isEmpty())
        } finally {
            repo.delete(p.id)
            good.delete()
            bad.delete()
        }
    }

    @Test
    fun importsHtmlV3AndPreservesLayout(): Unit = runBlocking {
        val repo = PdfProjectRepository(context)
        val image = jpeg()
        val json = temp(".json")
        var imported: PdfProject? = null
        try {
            val url =
                "data:image/jpeg;base64," +
                    android.util.Base64.encodeToString(
                        image.readBytes(),
                        android.util.Base64.NO_WRAP,
                    )
            val item =
                org.json
                    .JSONObject()
                    .put("src", "source")
                    .put("x", 12)
                    .put("y", 18)
                    .put("w", 80)
                    .put("h", 40)
                    .put("fit", "contain")
                    .put("fx", .5)
                    .put("fy", .5)
                    .put("lock", true)
            val page =
                org.json
                    .JSONObject()
                    .put("kind", "canvas")
                    .put("w", 210)
                    .put("h", 297)
                    .put("margin", 10)
                    .put("items", org.json.JSONArray().put(item))
            json.writeText(
                org.json
                    .JSONObject()
                    .put("schema", "lightforge.pdf-studio")
                    .put("version", 3)
                    .put("name", "Browser migration")
                    .put("units", "mm")
                    .put("dpi", 300)
                    .put(
                        "assets",
                        org.json.JSONObject().put("source", org.json.JSONObject().put("url", url)),
                    )
                    .put("pages", org.json.JSONArray().put(page))
                    .toString()
            )
            imported = repo.importPortable(Uri.fromFile(json))
            val migrated = imported.pages.single().images.single()
            assertEquals(12.0, migrated.x, 0.0)
            assertEquals(80.0, migrated.width, 0.0)
            assertEquals(PdfFit.Contain, migrated.fit)
            val out = repo.prepareExport(imported, false)
            assertTrue(out.length() > 0)
            out.delete()
        } finally {
            imported?.let { repo.delete(it.id) }
            json.delete()
            image.delete()
        }
    }

    @Test
    fun containDoesNotStretchAndExifRotationIsApplied() = runBlocking {
        val source = jpeg()
        val out = temp(".pdf")
        val preview = temp(".png")
        val engine = IsolatedPdfEngine(context)
        try {
            val hash = PdfProjectRepository.sha256(source)
            val p =
                PdfProject(
                    name = "Orientation",
                    assets = listOf(PdfAsset(hash, "image/jpeg", 200, 400, 6)),
                    pages =
                        listOf(
                            PdfPage(
                                width = 100.0,
                                height = 100.0,
                                margin = 0.0,
                                images =
                                    listOf(
                                        PdfImage(
                                            asset = hash,
                                            x = 0.0,
                                            y = 0.0,
                                            width = 100.0,
                                            height = 100.0,
                                        )
                                    ),
                            )
                        ),
                )
            engine.export(p, listOf(source), out, false)
            engine.preview(out, 0, preview)
            val b = BitmapFactory.decodeFile(preview.path)
            // A 2:1 raw source rotated clockwise fills the middle half horizontally.
            assertTrue(Color.red(b.getPixel(b.width / 2, b.height / 4)) > 180)
            assertTrue(Color.blue(b.getPixel(b.width / 2, b.height * 3 / 4)) > 180)
            assertTrue(Color.green(b.getPixel(b.width / 10, b.height / 2)) > 240)
            b.recycle()
        } finally {
            source.delete()
            out.delete()
            preview.delete()
        }
    }

    @Test
    fun portableRejectsTraversal() = runBlocking {
        val zip = temp(".zip")
        try {
            ZipOutputStream(zip.outputStream()).use {
                it.putNextEntry(ZipEntry("../escape"))
                it.write(byteArrayOf(1))
                it.closeEntry()
            }
            assertNotNull(
                runCatching { PdfProjectRepository(context).importPortable(Uri.fromFile(zip)) }
                    .exceptionOrNull()
            )
            assertFalse(File(context.filesDir, "escape").exists())
        } finally {
            zip.delete()
        }
    }
}
