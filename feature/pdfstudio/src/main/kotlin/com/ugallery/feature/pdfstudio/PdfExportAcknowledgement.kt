package com.ugallery.feature.pdfstudio

import android.content.Context

/**
 * Tracks which finished export jobs the user has already been told about, so the "Export finished
 * while UGallery was closed" recovery notice (Phase B item 8) never repeats itself for the same
 * job across app restarts. Separate from [PdfPublishPicker]/`dismissedResult` (SavedStateHandle),
 * which only needs to survive rotation/process-recreation of a live session: this must also
 * survive the app being fully closed and reopened later, hence prefs rather than saved state.
 */
class PdfExportAcknowledgementStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isAcknowledged(id: String): Boolean = id in (prefs.getStringSet(KEY, null) ?: emptySet())

    fun acknowledge(id: String) {
        val current = (prefs.getStringSet(KEY, null) ?: emptySet()).toMutableSet()
        if (current.add(id)) prefs.edit().putStringSet(KEY, current).apply()
    }

    /** Drop ids for jobs that no longer exist (removed from history), so this never grows unbounded. */
    fun prune(existingIds: Set<String>) {
        val current = prefs.getStringSet(KEY, null) ?: return
        val kept = current intersect existingIds
        if (kept.size != current.size) prefs.edit().putStringSet(KEY, kept).apply()
    }

    private companion object {
        const val FILE = "pdf_export_ack"
        const val KEY = "acknowledged_ids"
    }
}
