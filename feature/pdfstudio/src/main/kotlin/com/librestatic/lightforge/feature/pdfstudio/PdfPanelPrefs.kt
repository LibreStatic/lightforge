package com.librestatic.lightforge.feature.pdfstudio

import android.content.Context
import android.content.SharedPreferences

/**
 * Remembers whether the expanded/hinge-split editor's pages rail and inspector panes are shown or
 * collapsed (User feedback item 1), across sessions — mirrors [PdfLastDestinationStore]'s own
 * small-prefs-file pattern rather than a Room column or `core/preferences` (out of scope here; see
 * this module's guardrails). Read once per composition of the editor body and written through on
 * every toggle, alongside the `rememberSaveable` state that survives process death/rotation within
 * a single session.
 */
class PdfPanelPrefsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var pagesPanelVisible: Boolean
        get() = prefs.getBoolean(KEY_PAGES, true)
        set(value) {
            prefs.edit().putBoolean(KEY_PAGES, value).apply()
        }

    var inspectorPanelVisible: Boolean
        get() = prefs.getBoolean(KEY_INSPECTOR, true)
        set(value) {
            prefs.edit().putBoolean(KEY_INSPECTOR, value).apply()
        }

    companion object {
        private const val FILE = "pdf_panel_prefs"
        private const val KEY_PAGES = "pages_panel_visible"
        private const val KEY_INSPECTOR = "inspector_panel_visible"
    }
}
