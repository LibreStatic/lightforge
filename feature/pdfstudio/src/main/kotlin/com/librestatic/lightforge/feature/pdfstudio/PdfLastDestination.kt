package com.librestatic.lightforge.feature.pdfstudio

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
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
 * A short, human-friendly label for [uri], for the destination row and export history. Prefers the
 * folder path leading to it (e.g. "Documents › Lightforge") via `DocumentsContract
 * .findDocumentPath`, which works for the tree-backed document URIs `CreateDocument` normally
 * returns; falls back to the document's own provider-reported display name, then to [fallback]
 * ("Last used location") when neither resolves (grant revoked, non-tree provider, older API).
 * Never throws.
 */
internal suspend fun resolveDestinationLabel(context: Context, uri: Uri, fallback: String): String =
    withContext(Dispatchers.IO) {
        findParentPathLabel(context, uri) ?: displayNameOrNull(context, uri) ?: fallback
    }

private fun displayNameOrNull(context: Context, uri: Uri): String? =
    try {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (e: Exception) {
        null
    }

/** The chain of ancestor folder names above [uri], newest last (e.g. "Documents › Lightforge"). */
private fun findParentPathLabel(context: Context, uri: Uri): String? {
    if (Build.VERSION.SDK_INT < 26) return null
    return try {
        val ids = DocumentsContract.findDocumentPath(context.contentResolver, uri)?.path
        // The path includes the document itself as its last entry; everything before it is an
        // ancestor folder. A path of size <= 1 has no resolvable ancestor to show.
        if (ids == null || ids.size <= 1) return null
        ids.dropLast(1)
            .mapNotNull { id ->
                try {
                    val ancestor = DocumentsContract.buildDocumentUriUsingTree(uri, id)
                    context.contentResolver
                        .query(
                            ancestor,
                            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                            null,
                            null,
                            null,
                        )
                        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                } catch (e: Exception) {
                    null
                }
            }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" › ")
    } catch (e: Exception) {
        null
    }
}
