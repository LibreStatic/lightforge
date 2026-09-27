package com.librestatic.lightforge.feature.pdfstudio

import androidx.lifecycle.SavedStateHandle

/** Which pages a not-yet-rendered export draft covers. */
internal enum class PdfExportPagesChoice {
    All,
    Current,
    Selected,
}

/**
 * Everything [PdfStudioViewModel.resumeDraftExport] needs to build and enqueue a snapshot once the
 * destination picker returns a URI, recorded *before* a destination is even requested so process
 * death between "tap Export" and the picker's result can never lose it.
 */
internal data class PdfExportDraft(
    val projectId: String,
    val compact: Boolean,
    val pagesChoice: PdfExportPagesChoice,
    /** Page ids to keep when [pagesChoice] is [PdfExportPagesChoice.Current] or `.Selected`. */
    val pageIds: List<String>,
)

/**
 * One outstanding "destination first" export draft, saved the same way [PdfPublishPicker] saves a
 * legacy publish request: through `SavedStateHandle`, so it survives process death. There is at
 * most one at a time (the draft's id is always the one [PdfPublishPicker] has an outstanding
 * request for), so a single fixed key is enough.
 */
internal class PdfExportDraftStore(private val saved: SavedStateHandle) {
    fun put(draft: PdfExportDraft) {
        saved[KEY] =
            arrayListOf(
                draft.projectId,
                draft.compact.toString(),
                draft.pagesChoice.name,
                draft.pageIds.joinToString(","),
            )
    }

    fun peek(): PdfExportDraft? = decode(saved.get<ArrayList<String>>(KEY))

    /** Read and clear in one step, mirroring [PdfPublishPicker.acknowledge]. */
    fun take(): PdfExportDraft? = peek().also { saved[KEY] = null }

    fun discard() {
        saved[KEY] = null
    }

    private fun decode(raw: ArrayList<String>?): PdfExportDraft? {
        if (raw == null || raw.size < 4) return null
        val pagesChoice = runCatching { PdfExportPagesChoice.valueOf(raw[2]) }.getOrNull() ?: return null
        return PdfExportDraft(
            projectId = raw[0],
            compact = raw[1].toBoolean(),
            pagesChoice = pagesChoice,
            pageIds = raw[3].split(",").filter { it.isNotEmpty() },
        )
    }

    companion object {
        const val KEY = "pdfExportDraft"
    }
}

/** A synthetic id [PdfPublishPicker] can track a draft under, distinct from a real export job id. */
internal fun draftPickerId(): String = "draft:" + newId()

internal fun isDraftPickerId(id: String): Boolean = id.startsWith("draft:")
