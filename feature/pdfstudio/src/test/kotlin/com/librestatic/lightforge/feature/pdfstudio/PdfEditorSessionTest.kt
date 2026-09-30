package com.librestatic.lightforge.feature.pdfstudio

import org.junit.Assert.*
import org.junit.Test

class PdfEditorSessionTest {
    private fun project() =
        PdfProject(
            name = "Session",
            pages = listOf(PdfPage(), PdfPage()),
            columns = 3,
            gap = 7.5,
            snap = true,
        )

    @Test
    fun restoresSelectionAndHistory() {
        val p = project()
        val s =
            PdfEditorSession(
                pageId = p.pages[1].id,
                selectedPages = setOf(p.pages[0].id),
                undo = listOf(p.copy(name = "Before")),
                redo = listOf(p.copy(name = "After")),
                zoom = 2f,
                panX = 10f,
                panY = -20f,
            )
        assertEquals(s, PdfEditorSessionCodec.decode(PdfEditorSessionCodec.encode(s), p))
    }

    @Test
    fun layoutPreferencesRoundTrip() {
        val p = project()
        assertEquals(p, PdfCodec.decode(PdfCodec.encode(p)))
    }

    @Test
    fun oldProjectGetsCompatibleDefaults() {
        val o = org.json.JSONObject(PdfCodec.encode(project()))
        listOf("columns", "gap", "snap").forEach(o::remove)
        val p = PdfCodec.decode(o.toString())
        assertEquals(2, p.columns)
        assertEquals(4.0, p.gap, 0.0)
        assertFalse(p.snap)
    }

    @Test
    fun missingSelectionAndNonFiniteViewportNormalize() {
        val p = project()
        val s =
            PdfEditorSession(
                    pageId = "missing",
                    imageId = "missing",
                    selectedPages = setOf("missing"),
                    zoom = Float.NaN,
                    panX = Float.POSITIVE_INFINITY,
                )
                .normalized(p)
        assertEquals(p.pages[0].id, s.pageId)
        assertNull(s.imageId)
        assertTrue(s.selectedPages.isEmpty())
        assertEquals(1f, s.zoom, 0f)
        assertEquals(0f, s.panX, 0f)
    }

    @Test
    fun historyCannotCrossProjectIdentity() {
        val p = project()
        val s = PdfEditorSession(undo = listOf(p, p.copy(id = newId()))).normalized(p)
        assertEquals(listOf(p), s.undo)
    }

    @Test
    fun hugeHistoryIsBoundedByBytesAndRetainsNewest() {
        val hash = "a".repeat(64)
        val p =
            project()
                .copy(
                    assets = listOf(PdfAsset(hash, "image/jpeg", 10, 10)),
                    pages =
                        List(100) {
                            PdfPage(
                                images = listOf(PdfImage(asset = hash, width = 10.0, height = 10.0))
                            )
                        },
                )
        val snapshots = List(40) { p.copy(name = "History $it") }
        val raw = PdfEditorSessionCodec.encode(PdfEditorSession(undo = snapshots))
        assertTrue(raw.toByteArray().size <= PdfEditorSessionCodec.MAX_BYTES)
        val s = PdfEditorSessionCodec.decode(raw, p)
        assertTrue(s.undo.size < 40)
        assertEquals("History 39", s.undo.last().name)
    }

    @Test(expected = IllegalArgumentException::class)
    fun futureSessionVersionRejected() {
        PdfEditorSessionCodec.decode("{\"version\":2}", project())
    }

    @Test
    fun blankMigratedSessionOpensFirstPage() {
        val p = project()
        assertEquals(p.pages[0].id, PdfEditorSessionCodec.decode("", p).pageId)
    }

    @Test
    fun perPageViewportsRoundTrip() {
        val p = project()
        val s =
            PdfEditorSession(
                pageId = p.pages[0].id,
                viewports =
                    mapOf(
                        p.pages[0].id to PdfViewport(2f, 5f, -5f),
                        p.pages[1].id to PdfViewport(1f, 0f, 0f),
                    ),
            )
        val decoded = PdfEditorSessionCodec.decode(PdfEditorSessionCodec.encode(s), p)
        assertEquals(s.viewports, decoded.viewports)
    }

    @Test
    fun viewportsFromRemovedPagesAreDropped() {
        val p = project()
        val s = PdfEditorSession(viewports = mapOf("missing" to PdfViewport(2f))).normalized(p)
        assertTrue(s.viewports.isEmpty())
    }

    @Test
    fun sessionWithoutViewportsFieldFallsBackToLegacyZoomPan() {
        // Simulates a session encoded before Phase C: no top-level "viewports" key at all.
        val p = project()
        val legacy =
            org.json.JSONObject()
                .put("version", 1)
                .put("page", p.pages[1].id)
                .put("image", org.json.JSONObject.NULL)
                .put("selected", org.json.JSONArray())
                .put("zoom", 2.0)
                .put("panX", 3.0)
                .put("panY", -4.0)
                .put("undo", org.json.JSONArray())
                .put("redo", org.json.JSONArray())
                .toString()
        val decoded = PdfEditorSessionCodec.decode(legacy, p)
        assertEquals(PdfViewport(2f, 3f, -4f), decoded.viewportFor(p.pages[1].id))
        assertEquals(PdfViewport(), decoded.viewportFor(p.pages[0].id))
    }
}
