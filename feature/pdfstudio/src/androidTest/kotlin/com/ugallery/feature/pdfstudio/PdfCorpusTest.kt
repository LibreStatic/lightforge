package com.ugallery.feature.pdfstudio

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.*
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Deterministic, locally generated cases, not a claim of exhaustive parser fuzz coverage. */
class PdfCorpusTest {
    @Test
    fun boundedMalformedAndComplexCorpusPreservesSourcesAndRecovers(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val folder = File(context.cacheDir, "pdf-corpus-${newId()}").apply { mkdirs() }
        val engine = IsolatedPdfEngine(context)
        try {
            val complex = File(folder, "vector-transparency-rotation.pdf")
            PDDocument().use { doc ->
                repeat(12) { n ->
                    val page =
                        PDPage(PDRectangle(420f, 595f)).apply {
                            rotation = (n % 4) * 90
                            cropBox = PDRectangle(10f, 10f, 400f, 575f)
                        }
                    doc.addPage(page)
                    PDPageContentStream(doc, page).use { content ->
                        content.setGraphicsStateParameters(
                            PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = 0.45f }
                        )
                        repeat(800) { i ->
                            content.setNonStrokingColor(
                                (i % 256),
                                ((i * 7) % 256),
                                ((i * 13) % 256),
                            )
                            content.addRect((i % 40) * 10f, (i % 57) * 10f, 24f, 24f)
                            content.fill()
                        }
                    }
                }
                doc.save(complex)
            }
            val encrypted = File(folder, "encrypted.pdf")
            PDDocument().use { doc ->
                doc.addPage(PDPage())
                doc.protect(
                    StandardProtectionPolicy("owner-fixture", "reader-fixture", AccessPermission())
                        .apply { encryptionKeyLength = 128 }
                )
                doc.save(encrypted)
            }
            val cases =
                listOf(
                    File(folder, "empty.pdf").apply { writeBytes(byteArrayOf()) },
                    File(folder, "not-pdf.pdf").apply { writeText("local non-PDF fixture") },
                    File(folder, "truncated-header.pdf").apply {
                        writeBytes(complex.readBytes().take(16).toByteArray())
                    },
                    File(folder, "broken-root.pdf").apply {
                        writeText(
                            "%PDF-1.7\n1 0 obj << /Type /Catalog /Pages 99 0 R >> endobj\ntrailer << /Root 1 0 R >>\n%%EOF\n"
                        )
                    },
                    encrypted,
                    complex,
                )
            for (file in cases) {
                val hash = PdfProjectRepository.sha256(file)
                val started = SystemClock.elapsedRealtime()
                val png = File(folder, "preview.png")
                val output = File(folder, "export.pdf")
                if (file == complex) {
                    val pages = withTimeout(70_000) { engine.inspect(file) }
                    assertEquals(12, pages.size)
                    for (n in listOf(0, 3, 11)) {
                        withTimeout(70_000) { engine.preview(file, n, png) }
                        val bitmap = BitmapFactory.decodeFile(png.path)
                        assertNotNull(bitmap)
                        bitmap.recycle()
                    }
                    val project =
                        PdfProject(
                            name = "Complex corpus",
                            assets = listOf(PdfAsset(hash, "application/pdf")),
                            pages = pages.map { it.copy(source = hash) },
                        )
                    withTimeout(310_000) { engine.export(project, listOf(file), output, false) }
                    assertEquals(12, engine.inspect(output).size)
                    android.util.Log.i(
                        "PdfCorpus",
                        "${file.name} sha256=$hash bytes=${file.length()} pages=12 preview=3 export=12 elapsedMs=${SystemClock.elapsedRealtime() - started}",
                    )
                } else {
                    val error =
                        runCatching { withTimeout(70_000) { engine.inspect(file) } }
                            .exceptionOrNull()
                    assertNotNull(
                        "Malformed/password input unexpectedly accepted: ${file.name}",
                        error,
                    )
                    assertTrue(
                        "Must return a typed PDF error, not observer cancellation",
                        error is PdfOperationFailure,
                    )
                    val previewError =
                        runCatching { withTimeout(70_000) { engine.preview(file, 0, png) } }
                            .exceptionOrNull()
                    assertTrue(previewError is PdfOperationFailure)
                    assertFalse(png.exists())
                    android.util.Log.i(
                        "PdfCorpus",
                        "${file.name} sha256=$hash bytes=${file.length()} inspect=${PdfFailure.from(error!!)} preview=${PdfFailure.from(previewError!!)} elapsedMs=${SystemClock.elapsedRealtime() - started}",
                    )
                    // A rejected document must not poison the next independent job.
                    engine.export(
                        PdfProject(name = "Deadline recovery"),
                        emptyList(),
                        output,
                        false,
                    )
                    assertEquals(1, engine.inspect(output).size)
                }
                assertEquals(hash, PdfProjectRepository.sha256(file))
                png.delete()
                output.delete()
            }
        } finally {
            folder.deleteRecursively()
        }
    }
}
