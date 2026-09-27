package com.ugallery.app

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.provider.MediaStore
import android.util.Size
import com.ugallery.core.data.GalleryDocumentRepository
import com.ugallery.core.data.GalleryQueryMediaRepository
import com.ugallery.core.model.LibraryAccess
import com.ugallery.core.preferences.LibrarySettings
import com.ugallery.feature.pdfstudio.PdfMediaAccess
import com.ugallery.feature.pdfstudio.PdfMediaAccessState
import com.ugallery.feature.pdfstudio.PdfMediaFilter
import com.ugallery.feature.pdfstudio.PdfMediaItem
import com.ugallery.feature.pdfstudio.PdfMediaScope
import com.ugallery.feature.pdfstudio.PdfMediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** The gallery's own permission wording (feature/photos's strings), threaded in by the composition
 * so this plain class never has to resolve string resources itself. */
internal data class PdfMediaAccessWording(
    val limitedBody: String,
    val manageActionLabel: String,
    val deniedBody: String,
    val deniedActionLabel: String,
)

/** Per-scope item caps for the Media panel's bounded (non-paged) list — a scrollable grid, not an
 * infinite timeline, so these are generous constants rather than page sizes. */
private object PdfMediaCaps {
    const val Photos = 200
    const val Documents = 200
    /** Split roughly evenly across both when the "All" chip is selected. */
    const val AllPhotos = 100
    const val AllDocuments = 100
}

/**
 * PDF Studio's Media panel (Phase F item 3), implemented here over the app's own gallery data so
 * feature/pdfstudio never depends on a data module directly. Photos come from
 * [GalleryQueryMediaRepository.recentImages] against the SAME Room `media_items` index (and the
 * SAME [LibrarySettings] — excluded folders, archive exclusion, isAccessible/isTrashed) the Photos
 * timeline itself queries — never raw MediaStore, which would ignore the user's exclusions
 * entirely (review fix: "hidden must mean hidden"). Documents come from
 * [GalleryDocumentRepository]'s Documents collection, which already applies the same isAccessible/
 * isTrashed/archive filtering ([com.ugallery.core.database.DocumentEntities]'s `DocumentFrom`).
 *
 * [access]/[onRequestAccess] mirror exactly what [com.ugallery.feature.photos.LibraryPhotosRoute]
 * already shows for the Photos timeline (`limited_access_body`/`manage_access_action` in
 * feature/photos's strings, and `ProductionGalleryApp.requestAccess()`), so the Media panel's
 * permission affordance is the gallery's own wording, not a new copy.
 */
internal class AppPdfMediaSource(
    private val context: Context,
    private val documentRepository: StateFlow<GalleryDocumentRepository?>,
    private val queryMediaRepository: StateFlow<GalleryQueryMediaRepository?>,
    private val librarySettings: StateFlow<LibrarySettings>,
    access: StateFlow<LibraryAccess>,
    wording: PdfMediaAccessWording,
    onRequestAccess: () -> Unit,
) : PdfMediaSource {
    private val mutableAccess =
        MutableStateFlow(access.value.toPdfMediaAccess(wording, onRequestAccess))
    override val access: StateFlow<PdfMediaAccess> = mutableAccess.asStateFlow()

    private fun LibraryAccess.toPdfMediaAccess(
        wording: PdfMediaAccessWording,
        onRequestAccess: () -> Unit,
    ): PdfMediaAccess =
        when {
            images == com.ugallery.core.model.GrantLevel.None ->
                PdfMediaAccess(
                    PdfMediaAccessState.Denied,
                    wording.deniedBody,
                    wording.deniedActionLabel,
                    onRequestAccess,
                )
            isLimited ->
                PdfMediaAccess(
                    PdfMediaAccessState.Partial,
                    wording.limitedBody,
                    wording.manageActionLabel,
                    onRequestAccess,
                )
            else -> PdfMediaAccess(PdfMediaAccessState.Full)
        }

    /** Called by the composition (see ProductionGalleryApp) whenever the live [LibraryAccess]
     * changes, since this class itself is not a long-lived singleton observer. */
    fun updateAccess(value: LibraryAccess, wording: PdfMediaAccessWording, onRequestAccess: () -> Unit) {
        mutableAccess.value = value.toPdfMediaAccess(wording, onRequestAccess)
    }

    override fun items(filter: PdfMediaFilter): Flow<List<PdfMediaItem>> = flow {
        emit(
            when (filter.scope) {
                PdfMediaScope.All -> photos(PdfMediaCaps.AllPhotos) + documents(PdfMediaCaps.AllDocuments)
                PdfMediaScope.Photos -> photos(PdfMediaCaps.Photos)
                PdfMediaScope.Documents -> documents(PdfMediaCaps.Documents)
            }
        )
    }

    private suspend fun photos(limit: Int): List<PdfMediaItem> {
        val repository = queryMediaRepository.first() ?: return emptyList()
        return withContext(Dispatchers.IO) {
            repository.recentImages(librarySettings.value, limit).map { media ->
                val key = media.key
                val uri =
                    ContentUris.withAppendedId(
                        MediaStore.Images.Media.getContentUri(key.volumeName),
                        key.mediaStoreId,
                    )
                PdfMediaItem(
                    key = "photo:${key.volumeName}:${key.mediaStoreId}",
                    uri = uri,
                    displayName = media.displayName ?: "",
                    isDocument = false,
                    width = media.width,
                    height = media.height,
                )
            }
        }
    }

    private suspend fun documents(limit: Int): List<PdfMediaItem> {
        val repository = documentRepository.first() ?: return emptyList()
        return withContext(Dispatchers.IO) {
            repository.recent(limit).map { row ->
                val media = row.media
                val uri =
                    ContentUris.withAppendedId(
                        MediaStore.Files.getContentUri(media.volumeName),
                        media.mediaStoreId,
                    )
                PdfMediaItem(
                    key = "doc:${media.volumeName}:${media.mediaStoreId}",
                    uri = uri,
                    displayName = media.displayName ?: "",
                    isDocument = true,
                    width = media.width,
                    height = media.height,
                )
            }
        }
    }

    override suspend fun thumbnail(item: PdfMediaItem, sizePx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            if (android.os.Build.VERSION.SDK_INT < 29) return@withContext null
            val signal = CancellationSignal()
            try {
                // Wires this coroutine's own cancellation (the Media panel leaving composition,
                // or a newer request for the same grid cell) through to the platform decode, so a
                // cancelled thumbnail load actually stops the underlying I/O/decode instead of
                // completing uselessly in the background.
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { signal.cancel() }
                    val bitmap =
                        runCatching {
                                context.contentResolver.loadThumbnail(item.uri, Size(sizePx, sizePx), signal)
                            }
                            .getOrNull()
                    continuation.resumeWith(Result.success(bitmap))
                }
            } catch (e: android.os.OperationCanceledException) {
                null
            }
        }
}
