package com.librestatic.lightforge.feature.pdfstudio

import android.content.Context
import android.content.SharedPreferences

/** Pure list logic behind [PdfRecentPrintSizesStore], kept apart so it has plain JVM tests. */
internal object PdfRecentPrintSizes {
    const val MAX = 3

    /** Parses the stored string; unparseable and non-custom ids are skipped, duplicates dropped. */
    fun parse(raw: String?): List<PdfPrintSize> =
        raw.orEmpty()
            .split(SEPARATOR)
            .mapNotNull { PdfPrintSize.fromId(it) }
            .filter { it.isCustom }
            .distinctBy { it.id }
            .take(MAX)

    /** [size] moved (or added) to the front of [recents], deduplicated and capped at [MAX]; a
     * preset leaves the list unchanged. */
    fun record(recents: List<PdfPrintSize>, size: PdfPrintSize): List<PdfPrintSize> =
        if (!size.isCustom) recents else (listOf(size) + recents.filter { it.id != size.id }).take(MAX)

    fun encode(recents: List<PdfPrintSize>): String = recents.joinToString(SEPARATOR) { it.id }

    private const val SEPARATOR = ","
}

/** Remembers the last few custom print sizes across sessions (most recent first), in a small
 * prefs file like [PdfPanelPrefsStore]. */
class PdfRecentPrintSizesStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    internal fun read(): List<PdfPrintSize> = PdfRecentPrintSizes.parse(prefs.getString(KEY, null))

    /** Records [size] as the most recent and returns the updated list. */
    internal fun record(size: PdfPrintSize): List<PdfPrintSize> {
        val updated = PdfRecentPrintSizes.record(read(), size)
        prefs.edit().putString(KEY, PdfRecentPrintSizes.encode(updated)).apply()
        return updated
    }

    private companion object {
        const val FILE = "pdf_print_size_prefs"
        const val KEY = "recent_custom_ids"
    }
}
