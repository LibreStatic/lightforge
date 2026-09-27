package com.ugallery.app

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.provider.MediaStore
import android.util.Size
import com.ugallery.core.data.GalleryDocumentRepository
import com.ugallery.core.model.LibraryAccess
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
import kotlinx.coroutines.withContext

/** The gallery's own permission wording (feature/photos's strings), threaded in by the composition
 * so this plain class never has to resolve string resources itself. */
internal data class PdfMediaAccessWording(
    val limitedBody: String,
    val manageActionLabel: String,
    val deniedBody: String,
    val deniedActionLabel: String,
)

/**
 * PDF Studio's Media panel (Phase F item 3), implemented here over the app's own gallery media
 * (MediaStore, directly - the Photos timeline's Room index is a curated/derived view and would
 * exclude items PDF Studio should still be able to insert) and [GalleryDocumentRepository]'s
 * Documents collection, so feature/pdfstudio never depends on a data module directly.
 *
 * [access]/[onRequestAccess] mirror exactly what [com.ugallery.feature.photos.LibraryPhotosRoute]
 * already shows for the Photos timeline (`limited_access_body`/`manage_access_action` in
 * feature/photos's strings, and `ProductionGalleryApp.requestAccess()`), so the Media panel's
 * permission affordance is the gallery's own wording, not a new copy.
 */
internal class AppPdfMediaSource(
    private val context: Context,
    private val documentRepository: StateFlow<GalleryDocumentRepository?>,
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
                PdfMediaScope.All -> photos(200) + documents(100)
                PdfMediaScope.Photos -> photos(200)
                PdfMediaScope.Documents -> documents(200)
            }
        )
    }

    private suspend fun photos(limit: Int): List<PdfMediaItem> =
        withContext(Dispatchers.IO) {
            val items = mutableListOf<PdfMediaItem>()
            val projection =
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.WIDTH,
                    MediaStore.Images.Media.HEIGHT,
                )
            runCatching {
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${MediaStore.Images.Media.DATE_MODIFIED} DESC LIMIT $limit",
                )
            }
                .getOrNull()
                ?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    val wCol = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
                    val hCol = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        items +=
                            PdfMediaItem(
                                key = "photo:$id",
                                uri = uri,
                                displayName = if (nameCol >= 0) cursor.getString(nameCol) ?: "" else "",
                                isDocument = false,
                                width = if (wCol >= 0) cursor.getInt(wCol) else 0,
                                height = if (hCol >= 0) cursor.getInt(hCol) else 0,
                            )
                    }
                }
            items
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
            runCatching {
                    if (android.os.Build.VERSION.SDK_INT >= 29)
                        context.contentResolver.loadThumbnail(
                            item.uri,
                            Size(sizePx, sizePx),
                            CancellationSignal(),
                        )
                    else null
                }
                .getOrNull()
        }
}
