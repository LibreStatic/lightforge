package com.librestatic.lightforge.feature.pdfstudio

import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase G3 item 3/5: the Custom pages field ("1-3, 5") resolves through the same `pageIds`
 * subset path [Selected] and [Current] already use ([PdfStudioViewModel.buildDraftSnapshot],
 * [PdfStudioViewModel.beginNewExport]) — pages are filtered from the project in document order,
 * never reordered to match the typed token order. This exercises that path end to end: parse a
 * range against a 5-page project, export the filtered snapshot through the real queue/worker/
 * isolated renderer, and confirm the output PDF has exactly the expected pages, in document order.
 */
class PdfCustomPageRangeExportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val queue = PdfExportQueue(context)

    private suspend fun ready(id: String): PdfExportJob =
        withTimeout(45_000) {
            while (true) {
                val row = requireNotNull(queue.get(id))
                if (row.phase == PdfExportPhase.Ready) return@withTimeout row
                check(row.phase != PdfExportPhase.Failed) { row.error.orEmpty() }
                delay(50)
            }
            error("unreachable")
        }

    @Test
    fun customRangeExportsTheRightPagesInDocumentOrder(): Unit = runBlocking {
        // Distinct page widths (mm) so the rendered output's page geometry alone identifies which
        // original page ended up where, without needing any image content.
        val widths = listOf(60.0, 70.0, 80.0, 90.0, 100.0)
        val project =
            PdfProject(name = "Custom range export", pages = widths.map { PdfPage(width = it, height = 297.0) })
                .validate()

        // "4, 1-2" against 5 pages: page 4 (index 3) plus pages 1-2 (indices 0-1) -> merged and
        // sorted into document order [0, 1, 3], never the typed order [3, 0, 1].
        val parsed = PdfPageRange.parse("4, 1-2", project.pages.size)
        assertTrue(parsed is PdfPageRange.Result.Ok)
        val indices = (parsed as PdfPageRange.Result.Ok).indices
        assertEquals(listOf(0, 1, 3), indices)

        val pageIds = indices.map { project.pages[it].id }
        val filtered = project.copy(pages = project.pages.filter { it.id in pageIds })
        assertEquals(3, filtered.pages.size)

        var job: PdfExportJob? = null
        try {
            job = queue.enqueue(filtered, compact = false)
            val done = ready(job.id)
            assertEquals(3, done.completed)
            val outputPages = IsolatedPdfEngine(context).inspect(queue.output(job.id))
            assertEquals(3, outputPages.size)
            // inspect() reports each page's width back in mm (PdfProcessingService converts its
            // PDF points at 72/25.4), so the original pages 1, 2, 4 should appear, in that order.
            val expected = listOf(widths[0], widths[1], widths[3])
            outputPages.map { it.width }.zip(expected).forEach { (actual, want) ->
                assertTrue("expected ~$want, got $actual", abs(actual - want) < 1.0)
            }
        } finally {
            job?.let {
                queue.cancel(it.id)
                queue.remove(it.id)
            }
        }
    }
}
