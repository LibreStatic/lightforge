package com.ugallery.feature.pdfstudio

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Remembers the last export destination the user picked, so the next export sheet can prefill
 * `EXTRA_INITIAL_URI` with its parent folder and show a friendly "Save to: <location>" row instead
 * of always starting from the document picker's default root.
 *
 * Backed by a small prefs file rather than a Room column: the plan calls for avoiding a schema
 * migration in this phase when a value this small does not need one.
 */
class PdfLastDestinationStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var lastDestination: Uri?
        get() = prefs.getString(KEY_URI, null)?.let(Uri::parse)
        set(value) {
            prefs.edit().apply { if (value == null) remove(KEY_URI) else putString(KEY_URI, value.toString()) }.apply()
        }

    companion object {
        private const val FILE = "pdf_export_prefs"
        private const val KEY_URI = "last_destination_uri"
    }
}

/**
 * A resolvable hint URI to seed `DocumentsContract.EXTRA_INITIAL_URI` with, so the next export
 * picker opens near the last destination instead of the provider's default root. `CreateDocument`
 * only accepts a URI here (there is no dedicated "parent" API without pulling in the DocumentFile
 * artifact), so this passes the last document itself: providers that honor the extra at all
 * generally resolve it to its containing folder.
 */
internal fun lastDestinationParent(uri: Uri): Uri = uri

/**
 * A short, human-friendly label for [uri], for the destination row and export history: the
 * document's provider-reported display name, falling back to [fallback] ("Last used location")
 * when the grant was revoked or the provider does not support the query. Never throws.
 */
internal suspend fun resolveDestinationLabel(context: Context, uri: Uri, fallback: String): String =
    withContext(Dispatchers.IO) {
        try {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: fallback
        } catch (e: Exception) {
            fallback
        }
    }
