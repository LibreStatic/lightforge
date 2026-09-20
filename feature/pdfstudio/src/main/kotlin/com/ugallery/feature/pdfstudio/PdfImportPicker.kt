package com.ugallery.feature.pdfstudio

import androidx.lifecycle.SavedStateHandle

internal data class PdfImportRequest(
    val id: String,
    val portable: Boolean,
    val projectId: String?,
    val pageId: String?,
    val uris: List<String> = emptyList(),
)

/** ActivityResult ownership contains stable IDs, never a mutable page index or project snapshot. */
internal class PdfImportPicker(private val saved: SavedStateHandle) {
    val pending = saved.getStateFlow<ArrayList<String>?>(KEY, null)
    val request: PdfImportRequest?
        get() =
            pending.value?.let {
                PdfImportRequest(
                    it[0],
                    it[1] == "portable",
                    it[2].ifEmpty { null },
                    it[3].ifEmpty { null },
                    it.drop(4),
                )
            }

    fun begin(portable: Boolean, projectId: String?, pageId: String?): Boolean {
        if (request != null || (!portable && (projectId == null || pageId == null))) return false
        saved[KEY] =
            arrayListOf(
                newId(),
                if (portable) "portable" else "sources",
                projectId.orEmpty(),
                pageId.orEmpty(),
            )
        return true
    }

    fun result(portable: Boolean, uris: List<String>): Boolean {
        val current = request ?: return false
        if (current.portable != portable || current.uris.isNotEmpty()) return false
        require(uris.size <= if (portable) 1 else 100)
        require(uris.sumOf { it.toByteArray().size } <= 128 * 1024)
        saved[KEY] = if (uris.isEmpty()) null else ArrayList(pending.value!!.take(4) + uris)
        return uris.isNotEmpty()
    }

    fun restore(request: PdfImportRequest): Boolean {
        val current = this.request
        if (current != null && (current.id != request.id || current.uris.isNotEmpty())) return false
        saved[KEY] =
            ArrayList(
                listOf(
                    request.id,
                    if (request.portable) "portable" else "sources",
                    request.projectId.orEmpty(),
                    request.pageId.orEmpty(),
                ) + request.uris
            )
        return true
    }

    fun acknowledge(request: PdfImportRequest) {
        if (this.request == request) saved[KEY] = null
    }

    companion object {
        const val KEY = "pdfImportPicker"
    }
}
