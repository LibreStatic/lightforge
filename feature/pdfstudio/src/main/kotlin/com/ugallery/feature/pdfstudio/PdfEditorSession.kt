package com.ugallery.feature.pdfstudio

import org.json.JSONArray
import org.json.JSONObject

/** A page's zoom/pan, kept independently per page (Phase C item 6). */
data class PdfViewport(val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f) {
    fun normalized(): PdfViewport =
        copy(
            zoom = if (zoom.isFinite()) zoom.coerceIn(.5f, 4f) else 1f,
            panX = if (panX.isFinite()) panX.coerceIn(-10_000f, 10_000f) else 0f,
            panY = if (panY.isFinite()) panY.coerceIn(-10_000f, 10_000f) else 0f,
        )
}

/** Durable, bounded editor state; source bytes remain in the shared asset store. */
data class PdfEditorSession(
    val pageId: String? = null,
    val imageId: String? = null,
    val selectedPages: Set<String> = emptySet(),
    val undo: List<PdfProject> = emptyList(),
    val redo: List<PdfProject> = emptyList(),
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    /**
     * Per-page viewport (Phase C item 6): [selectPage] no longer resets zoom/pan, so each page
     * remembers its own. Optional and backward-compatible — an editor session encoded before this
     * field existed simply decodes with an empty map, and [viewportFor] then falls back to the
     * legacy top-level [zoom]/[panX]/[panY] for the page that was current when it was saved (and
     * 1x/centered for every other page), so no migration is needed.
     */
    val viewports: Map<String, PdfViewport> = emptyMap(),
) {
    fun normalized(project: PdfProject): PdfEditorSession {
        val page = project.pages.firstOrNull { it.id == pageId } ?: project.pages.first()
        val pageIds = project.pages.map { it.id }.toSet()
        return copy(
            pageId = page.id,
            imageId = imageId?.takeIf { id -> page.images.any { it.id == id } },
            selectedPages = selectedPages.intersect(pageIds),
            undo = undo.filter { it.id == project.id }.takeLast(40),
            redo = redo.filter { it.id == project.id }.takeLast(40),
            zoom = if (zoom.isFinite()) zoom.coerceIn(.5f, 4f) else 1f,
            panX = if (panX.isFinite()) panX.coerceIn(-10_000f, 10_000f) else 0f,
            panY = if (panY.isFinite()) panY.coerceIn(-10_000f, 10_000f) else 0f,
            viewports = viewports.filterKeys { it in pageIds }.mapValues { it.value.normalized() },
        )
    }

    /** The viewport for [pageId], falling back to the legacy single zoom/pan for [this.pageId]. */
    fun viewportFor(pageId: String): PdfViewport =
        viewports[pageId] ?: if (pageId == this.pageId) PdfViewport(zoom, panX, panY) else PdfViewport()

    fun usedAssets(): Set<String> = (undo + redo).flatMap { it.usedAssets() }.toSet()
}

object PdfEditorSessionCodec {
    const val MAX_BYTES = 480 * 1024

    fun encode(value: PdfEditorSession): String {
        val base =
            JSONObject()
                .apply {
                    put("version", 1)
                    put("page", value.pageId)
                    put("image", value.imageId)
                    put("selected", JSONArray(value.selectedPages.sorted()))
                    put("zoom", value.zoom.toDouble())
                    put("panX", value.panX.toDouble())
                    put("panY", value.panY.toDouble())
                    put(
                        "viewports",
                        JSONObject().apply {
                            value.viewports.forEach { (id, v) ->
                                put(
                                    id,
                                    JSONObject()
                                        .put("zoom", v.zoom.toDouble())
                                        .put("panX", v.panX.toDouble())
                                        .put("panY", v.panY.toDouble()),
                                )
                            }
                        },
                    )
                }
                .toString()
                .dropLast(1)
        fun encoded(projects: List<PdfProject>) =
            ArrayDeque(
                projects.takeLast(40).map {
                    val raw = PdfCodec.encode(it)
                    raw to raw.toByteArray().size
                }
            )
        val undo = encoded(value.undo)
        val redo = encoded(value.redo)
        var bytes =
            base.toByteArray().size +
                32 +
                undo.sumOf { it.second + 1 } +
                redo.sumOf { it.second + 1 }
        // Serialize each snapshot once; trimming never creates quadratic JSON allocations.
        while (bytes > MAX_BYTES && (undo.isNotEmpty() || redo.isNotEmpty())) {
            val removed = if (undo.size >= redo.size) undo.removeFirst() else redo.removeFirst()
            bytes -= removed.second + 1
        }
        val raw =
            base +
                ",\"undo\":[" +
                undo.joinToString(",") { it.first } +
                "],\"redo\":[" +
                redo.joinToString(",") { it.first } +
                "]}"
        require(raw.toByteArray().size <= MAX_BYTES)
        return raw
    }

    fun decode(raw: String, project: PdfProject): PdfEditorSession {
        if (raw.isBlank()) return PdfEditorSession().normalized(project)
        require(raw.toByteArray().size <= MAX_BYTES)
        val o = JSONObject(raw)
        require(o.getInt("version") == 1)
        fun history(key: String): List<PdfProject> {
            val a = o.getJSONArray(key)
            require(a.length() <= 40)
            return List(a.length()) { PdfCodec.decode(a.getJSONObject(it).toString()) }
        }
        val selected = o.getJSONArray("selected")
        require(selected.length() <= 100)
        // Optional and backward-compatible: absent in any session encoded before Phase C.
        val viewportsJson = o.optJSONObject("viewports")
        val viewports =
            viewportsJson
                ?.keys()
                ?.asSequence()
                ?.associateWith { key ->
                    val v = viewportsJson.getJSONObject(key)
                    PdfViewport(
                        zoom = v.optDouble("zoom", 1.0).toFloat(),
                        panX = v.optDouble("panX", 0.0).toFloat(),
                        panY = v.optDouble("panY", 0.0).toFloat(),
                    )
                }
                ?.also { require(it.size <= 100) }
                .orEmpty()
        return PdfEditorSession(
                pageId = o.optString("page").takeIf { it.isNotEmpty() },
                imageId = o.optString("image").takeIf { it.isNotEmpty() },
                selectedPages = List(selected.length()) { selected.getString(it) }.toSet(),
                undo = history("undo"),
                redo = history("redo"),
                zoom = o.optDouble("zoom", 1.0).toFloat(),
                panX = o.optDouble("panX", 0.0).toFloat(),
                panY = o.optDouble("panY", 0.0).toFloat(),
                viewports = viewports,
            )
            .normalized(project)
    }
}
