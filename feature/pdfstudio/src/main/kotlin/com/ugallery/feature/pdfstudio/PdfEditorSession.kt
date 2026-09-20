package com.ugallery.feature.pdfstudio

import org.json.JSONArray
import org.json.JSONObject

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
) {
    fun normalized(project: PdfProject): PdfEditorSession {
        val page = project.pages.firstOrNull { it.id == pageId } ?: project.pages.first()
        return copy(
            pageId = page.id,
            imageId = imageId?.takeIf { id -> page.images.any { it.id == id } },
            selectedPages = selectedPages.intersect(project.pages.map { it.id }.toSet()),
            undo = undo.filter { it.id == project.id }.takeLast(40),
            redo = redo.filter { it.id == project.id }.takeLast(40),
            zoom = if (zoom.isFinite()) zoom.coerceIn(.5f, 4f) else 1f,
            panX = if (panX.isFinite()) panX.coerceIn(-10_000f, 10_000f) else 0f,
            panY = if (panY.isFinite()) panY.coerceIn(-10_000f, 10_000f) else 0f,
        )
    }

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
        return PdfEditorSession(
                pageId = o.optString("page").takeIf { it.isNotEmpty() },
                imageId = o.optString("image").takeIf { it.isNotEmpty() },
                selectedPages = List(selected.length()) { selected.getString(it) }.toSet(),
                undo = history("undo"),
                redo = history("redo"),
                zoom = o.optDouble("zoom", 1.0).toFloat(),
                panX = o.optDouble("panX", 0.0).toFloat(),
                panY = o.optDouble("panY", 0.0).toFloat(),
            )
            .normalized(project)
    }
}
