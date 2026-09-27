package com.ugallery.feature.pdfstudio

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Which bucket of media the Media panel's filter chips are asking for. "In this project" is
 * answered entirely from the open [PdfProject]'s own assets, without going through
 * [PdfMediaSource] at all (see [PdfStudioViewModel.insertOwnAsset]), so it has no entry here. */
enum class PdfMediaScope {
    All,
    Photos,
    Documents,
}

data class PdfMediaFilter(val scope: PdfMediaScope, val query: String = "")

/** One item the Media panel can show and insert. [key] is a stable identity for LazyVerticalGrid
 * (never the [uri] string, which can repeat across app restarts on some providers). */
data class PdfMediaItem(
    val key: String,
    val uri: Uri,
    val displayName: String,
    val isDocument: Boolean,
    val width: Int,
    val height: Int,
)

enum class PdfMediaAccessState {
    Full,
    Partial,
    Denied,
}

/**
 * The gallery's own permission affordance, worded by the app module (which owns the permission
 * strings and the request flow) so pdfstudio never has to duplicate that copy. [message] and
 * [actionLabel] are null when [state] is [PdfMediaAccessState.Full] (nothing to show).
 */
data class PdfMediaAccess(
    val state: PdfMediaAccessState,
    val message: String? = null,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

/**
 * Phase F item 3: the Media panel's view of the app's gallery + Documents data, implemented in
 * the `app` module over the existing repositories so this feature module never depends on a data
 * module directly. A null [PdfMediaSource] (the default `PdfStudioScreen` parameter) hides the
 * Media tab entirely, which keeps every probe/test that doesn't pass one unchanged.
 */
interface PdfMediaSource {
    /** Reactive access state, e.g. changing live if the user grants/revokes permission while the
     * panel is open. */
    val access: StateFlow<PdfMediaAccess>

    /** Items for the given filter, most-recent first. Not paged: the Media panel is a bounded,
     * scrollable grid rather than an infinite timeline, so a simple capped list (implementations
     * typically cap around a few hundred items) keeps this interface dependency-free instead of
     * requiring pdfstudio to take on androidx.paging. */
    fun items(filter: PdfMediaFilter): Flow<List<PdfMediaItem>>

    /** Loads a thumbnail off the main thread (`ContentResolver.loadThumbnail`, API 29+, is the
     * expected implementation), or null if it could not be decoded. */
    suspend fun thumbnail(item: PdfMediaItem, sizePx: Int): Bitmap?
}

/** Shared by every Media panel drop target (the canvas page and each page-strip/pages-panel
 * thumbnail): pulls the dragged item's [Uri] back out of the [android.content.ClipData] a
 * [PdfMediaThumbnail]-style drag source attaches via `dragAndDropSource`. */
internal fun pdfMediaDropUri(event: androidx.compose.ui.draganddrop.DragAndDropEvent): Uri? =
    runCatching { event.toAndroidDragEvent().clipData }
        .getOrNull()
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.uri

