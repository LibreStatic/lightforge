package com.librestatic.lightforge.feature.pdfstudio

import android.graphics.*
import android.graphics.pdf.PdfDocument
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PdfSourcePagesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun temp(suffix: String) =
        File.createTempFile("source-lifetime-", suffix, context.cacheDir)

    private fun fixture(label: String, color: Int): File =
        temp(".pdf").also { file ->
            val image = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
            image.eraseColor(color)
            val document = PdfDocument()
            try {
                repeat(2) { index ->
                    val page =
                        document.startPage(
                            PdfDocument.PageInfo.Builder(320, 400, index + 1).create()
                        )
                    page.canvas.drawColor(Color.WHITE)
                    page.canvas.drawText(
                        "$label page $index",
                        25f,
                        45f,
                        Paint().apply {
                            textSize = 20f
                            this.color = Color.BLACK
                        },
                    )
                    page.canvas.drawBitmap(image, null, Rect(30, 80, 130, 180), null)
                    document.finishPage(page)
                }
                file.outputStream().use(document::writeTo)
            } finally {
                document.close()
            }
            image.recycle()
        }

    @Test
    fun clonedStreamsRemainReadableAfterAllSourcesCloseAndConsecutivePagesShareResources() {
        PDFBoxResourceLoader.init(context)
        val a = fixture("Embedded vector A", Color.RED)
        val b = fixture("Embedded vector B", Color.BLUE)
        val output = temp(".pdf")
        val opened = mutableListOf<PDDocument>()
        try {
            PDDocument().use { destination ->
                PdfSourcePages(destination) { key ->
                        assertTrue(opened.all { it.document.isClosed })
                        PDDocument.load(if (key == "a") a else b).also { opened.add(it) }
                    }
                    .use { session ->
                        session.append("a", 0, 0)
                        session.append("a", 1, 90)
                        assertEquals(1, opened.size)
                        val first = destination.getPage(0).resources
                        val second = destination.getPage(1).resources
                        val font = first.fontNames.first()
                        assertSame(first.getFont(font).cosObject, second.getFont(font).cosObject)
                        session.append("b", 0, 0)
                        session.append("a", 0, 0)
                        assertEquals(3, opened.size)
                    }
                assertTrue(opened.all { it.document.isClosed })
                destination.save(output)
            }
            PDDocument.load(output).use { document ->
                assertEquals(4, document.numberOfPages)
                assertEquals(90, document.getPage(1).rotation)
                // Normalize only the in-memory extraction view; rotation is asserted separately.
                document.getPage(1).rotation = 0
                val text = PDFTextStripper().getText(document)
                assertTrue(text, text.contains("Embedded vector A page 1"))
                assertTrue(PDFTextStripper().getText(document).contains("Embedded vector B page 0"))
                document.pages.forEach { page ->
                    page.resources.xObjectNames.forEach { name ->
                        val stream = page.resources.getXObject(name).cosObject
                        assertTrue(stream.createInputStream().use { it.readBytes().isNotEmpty() })
                    }
                }
            }
        } finally {
            a.delete()
            b.delete()
            output.delete()
        }
    }

    @Test
    fun hundredSourcesNeverRetainMoreThanOneParsedDocument() {
        PDFBoxResourceLoader.init(context)
        val opened = mutableListOf<PDDocument>()
        PDDocument().use { output ->
            PdfSourcePages(output) {
                    assertTrue(opened.all { it.document.isClosed })
                    PDDocument().apply { addPage(PDPage()) }.also { opened.add(it) }
                }
                .use { session -> repeat(100) { session.append("source-$it", 0, 0) } }
            assertEquals(100, output.numberOfPages)
            assertEquals(100, opened.size)
            assertTrue(opened.all { it.document.isClosed })
        }
    }

    @Test
    fun failedLoadAndInvalidPageCloseTheirSourceSessions() {
        PDFBoxResourceLoader.init(context)
        val opened = mutableListOf<PDDocument>()
        PDDocument().use { output ->
            val session =
                PdfSourcePages(output) { key ->
                    assertTrue(opened.all { it.document.isClosed })
                    if (key == "missing") throw java.io.FileNotFoundException("Fixture")
                    PDDocument().apply { addPage(PDPage()) }.also { opened.add(it) }
                }
            assertTrue(
                runCatching {
                        session.use {
                            it.append("one", 0, 0)
                            it.append("missing", 0, 0)
                        }
                    }
                    .isFailure
            )
            assertTrue(opened.all { it.document.isClosed })
            assertTrue(runCatching { session.use { it.append("two", 10, 0) } }.isFailure)
            assertTrue(opened.all { it.document.isClosed })
            session.close()
        }
    }

    @Test
    fun realIsolatedExportPreservesPixelsAcrossSourceSwitchesAndReopens(): Unit = runBlocking {
        val a = fixture("Source A", Color.RED)
        val b = fixture("Source B", Color.BLUE)
        val output = temp(".pdf")
        val expected = temp(".png")
        val actual = temp(".png")
        val engine = IsolatedPdfEngine(context)
        try {
            val hashes = listOf(a, b).map { PdfProjectRepository.sha256(it) }
            val order = listOf(0 to 0, 0 to 1, 1 to 0, 0 to 0, 1 to 1)
            val project =
                PdfProject(
                    name = "Resource lifetime",
                    assets = hashes.map { PdfAsset(it, "application/pdf") },
                    pages =
                        order.map { (source, page) ->
                            PdfPage(source = hashes[source], sourcePage = page)
                        },
                )
            engine.export(project, listOf(a, b), output, false)
            assertEquals(5, engine.inspect(output).size)
            for ((index, pair) in order.withIndex()) {
                engine.preview(listOf(a, b)[pair.first], pair.second, expected)
                engine.preview(output, index, actual)
                val left = BitmapFactory.decodeFile(expected.path)
                val right = BitmapFactory.decodeFile(actual.path)
                try {
                    assertEquals(left.width, right.width)
                    assertEquals(left.height, right.height)
                    assertTrue("Rendered page $index changed", left.sameAs(right))
                } finally {
                    left.recycle()
                    right.recycle()
                }
            }
            val invalid = project.copy(pages = listOf(project.pages[0].copy(sourcePage = 99)))
            assertTrue(
                runCatching { engine.export(invalid, listOf(a, b), output, false) }.isFailure
            )
            assertFalse(output.exists())
            engine.export(project, listOf(a, b), output, false)
            assertEquals(5, engine.inspect(output).size)
        } finally {
            listOf(a, b, output, expected, actual).forEach { it.delete() }
        }
    }

    @Test
    fun inheritedGeometryAndNestedTransparencySurviveIndependentSourceClosure(): Unit =
        runBlocking {
            PDFBoxResourceLoader.init(context)
            val source = temp(".pdf")
            val output = temp(".pdf")
            val before = temp(".png")
            val after = temp(".png")
            try {
                PDDocument().use { document ->
                    val page = PDPage(PDRectangle(12f, 15f, 320f, 400f))
                    document.addPage(page)
                    page.cropBox = PDRectangle(22f, 25f, 300f, 380f)
                    page.trimBox = PDRectangle(32f, 35f, 280f, 360f)
                    page.bleedBox = PDRectangle(27f, 30f, 290f, 370f)
                    page.artBox = PDRectangle(42f, 45f, 260f, 340f)
                    page.userUnit = 1.5f
                    val group =
                        COSDictionary().apply {
                            setItem(COSName.TYPE, COSName.GROUP)
                            setItem(COSName.S, COSName.getPDFName("Transparency"))
                            setBoolean(COSName.getPDFName("I"), true)
                        }
                    page.cosObject.setItem(COSName.GROUP, group)
                    val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.argb(160, 0, 180, 80))
                    val nested =
                        PDFormXObject(document).apply {
                            resources = PDResources()
                            bBox = PDRectangle(200f, 200f)
                            resources.put(
                                COSName.getPDFName("Image"),
                                LosslessFactory.createFromImage(document, bitmap),
                            )
                            cosObject.createOutputStream().use {
                                it.write("q 100 0 0 100 40 60 cm /Image Do Q".toByteArray())
                            }
                            cosObject.setItem(COSName.GROUP, group)
                        }
                    bitmap.recycle()
                    val outer =
                        PDFormXObject(document).apply {
                            resources = PDResources()
                            bBox = PDRectangle(200f, 200f)
                            resources.put(COSName.getPDFName("Nested"), nested)
                            cosObject.createOutputStream().use {
                                it.write("/Nested Do".toByteArray())
                            }
                        }
                    PDPageContentStream(document, page).use { it.drawForm(outer) }
                    // Force inheritance through the source page tree; do not clone that tree.
                    document.pages.cosObject.setItem(COSName.RESOURCES, page.resources)
                    document.pages.cosObject.setItem(COSName.MEDIA_BOX, page.mediaBox)
                    page.cosObject.removeItem(COSName.RESOURCES)
                    page.cosObject.removeItem(COSName.MEDIA_BOX)
                    page.cosObject.setItem(COSName.AA, COSDictionary())
                    document.save(source)
                }
                var input: PDDocument? = null
                PDDocument().use { destination ->
                    PdfSourcePages(destination) { PDDocument.load(source).also { input = it } }
                        .use { it.append("nested", 0, 0) }
                    assertTrue(input!!.document.isClosed)
                    destination.save(output)
                }
                PDDocument.load(source).use { original ->
                    PDDocument.load(output).use { copy ->
                        val a = original.getPage(0)
                        val b = copy.getPage(0)
                        assertEquals(a.mediaBox.toString(), b.mediaBox.toString())
                        assertEquals(a.cropBox.toString(), b.cropBox.toString())
                        assertEquals(a.trimBox.toString(), b.trimBox.toString())
                        assertEquals(a.bleedBox.toString(), b.bleedBox.toString())
                        assertEquals(a.artBox.toString(), b.artBox.toString())
                        assertEquals(1.5f, b.userUnit, 0f)
                        assertNotNull(b.cosObject.getDictionaryObject(COSName.GROUP))
                        assertFalse(b.cosObject.containsKey(COSName.AA))
                        assertTrue(b.annotations.isEmpty())
                    }
                }
                val engine = IsolatedPdfEngine(context)
                engine.preview(source, 0, before)
                engine.preview(output, 0, after)
                val a = BitmapFactory.decodeFile(before.path)
                val b = BitmapFactory.decodeFile(after.path)
                try {
                    assertTrue("Nested form pixels changed", a.sameAs(b))
                } finally {
                    a.recycle()
                    b.recycle()
                }
            } finally {
                listOf(source, output, before, after).forEach { it.delete() }
            }
        }
}
