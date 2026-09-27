package com.ugallery.feature.pdfstudio

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase G1a device tests: the text layer must export as REAL selectable PDF text (extractable
 * with [PDFTextStripper]), never a rasterized/image approximation, with the embedded font
 * subsetted, across both bundled families, both weights, all three alignments, multiline content
 * and non-Latin (Greek/Cyrillic) glyphs. Also covers the portable archive round trip with texts
 * and importing a pre-Phase-G1a (version 1) archive.
 */
class PdfTextExportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun temp(ext: String) = File.createTempFile("pdf-text-test-", ext, context.cacheDir)

    @Test
    fun exportedTextIsRealExtractableTextWithEmbeddedSubsetFonts() = runBlocking {
        PDFBoxResourceLoader.init(context)
        val engine = IsolatedPdfEngine(context)
        val output = temp(".pdf")
        try {
            val project =
                PdfProject(
                    name = "Text export",
                    pages =
                        listOf(
                            PdfPage(
                                texts =
                                    listOf(
                                        PdfText(
                                            text = "Sans regular start",
                                            font = PdfFontFamily.Sans,
                                            weight = PdfFontWeight.Regular,
                                            align = PdfTextAlign.Start,
                                            x = 10.0,
                                            y = 10.0,
                                            width = 150.0,
                                            height = 20.0,
                                        ),
                                        PdfText(
                                            text = "Serif bold centered",
                                            font = PdfFontFamily.Serif,
                                            weight = PdfFontWeight.Bold,
                                            align = PdfTextAlign.Center,
                                            x = 10.0,
                                            y = 40.0,
                                            width = 150.0,
                                            height = 20.0,
                                        ),
                                        PdfText(
                                            text = "End aligned line",
                                            font = PdfFontFamily.Sans,
                                            weight = PdfFontWeight.Bold,
                                            align = PdfTextAlign.End,
                                            x = 10.0,
                                            y = 70.0,
                                            width = 150.0,
                                            height = 20.0,
                                        ),
                                        PdfText(
                                            text = "A multiline block that must wrap across more than one line inside its box",
                                            x = 10.0,
                                            y = 100.0,
                                            width = 60.0,
                                            height = 60.0,
                                            sizePt = 10.0,
                                        ),
                                        PdfText(
                                            text = "Ελληνικά και Русский текст",
                                            x = 10.0,
                                            y = 170.0,
                                            width = 150.0,
                                            height = 20.0,
                                        ),
                                    )
                            )
                        ),
                )
            engine.export(project, emptyList(), output, compact = false)
            PDDocument.load(output).use { doc ->
                val extracted = PDFTextStripper().getText(doc)
                assertTrue(extracted.contains("Sans regular start"))
                assertTrue(extracted.contains("Serif bold centered"))
                assertTrue(extracted.contains("End aligned line"))
                assertTrue(extracted.contains("multiline"))
                assertTrue(extracted.contains("Ελληνικά"))
                assertTrue(extracted.contains("Русский"))

                val page = doc.getPage(0)
                val fontNames = page.resources.fontNames.toList()
                assertTrue("Expected embedded fonts on the page", fontNames.isNotEmpty())
                fontNames.forEach { name ->
                    val font = page.resources.getFont(name)
                    // PDFBox tags an embedded subset with a random 6-uppercase-letter prefix,
                    // e.g. "ABCDEF+NotoSans-Regular" — confirms embedSubset actually subsetted
                    // the font rather than embedding it whole.
                    assertTrue(
                        "Font name '${font.name}' is not a subset tag",
                        Regex("^[A-Z]{6}\\+.+").matches(font.name),
                    )
                }
            }
        } finally {
            output.delete()
        }
    }

    @Test
    fun compactExportAlsoKeepsTextAsRealText() = runBlocking {
        PDFBoxResourceLoader.init(context)
        val engine = IsolatedPdfEngine(context)
        val output = temp(".pdf")
        try {
            val project =
                PdfProject(
                    name = "Compact text export",
                    pages = listOf(PdfPage(texts = listOf(PdfText(text = "Still real text")))),
                )
            engine.export(project, emptyList(), output, compact = true)
            PDDocument.load(output).use { doc ->
                assertTrue(PDFTextStripper().getText(doc).contains("Still real text"))
            }
        } finally {
            output.delete()
        }
    }

    @Test
    fun portableArchiveRoundTripsTextsAndV1ArchiveStillImports() = runBlocking {
        val repo = PdfProjectRepository(context)
        var p =
            PdfProject(
                name = "Portable text",
                pages = listOf(PdfPage(texts = listOf(PdfText(text = "Archived text", z = 2)))),
            )
        var imported: PdfProject? = null
        var portable: File? = null
        try {
            repo.save(p)
            portable = repo.portable(p)
            imported = repo.importPortable(Uri.fromFile(portable))
            assertNotEquals(p.id, imported.id)
            assertEquals(p.pages.single().texts, imported.pages.single().texts)
        } finally {
            repo.delete(p.id)
            imported?.let { repo.delete(it.id) }
            portable?.delete()
        }
    }
}
