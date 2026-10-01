package com.librestatic.lightforge.feature.picker

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.preferences.GallerySettingsRepository
import java.io.FileNotFoundException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Read-only "Lightforge" root in the system Files picker: one folder per visible device album,
 * plus Recents and name search. Folders excluded in Settings → Library stay hidden, and the root
 * disappears while App lock is on, since a provider cannot ask for the user's credentials.
 */
class GalleryDocumentsProvider : DocumentsProvider() {
    private val store by lazy { PickerMediaStore(requireContext().contentResolver) }

    @Volatile private var cachedCatalog: Pair<Long, PickerCatalog>? = null

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val context = requireContext()
        val cursor = MatrixCursor(projection ?: DefaultRootProjection)
        cursor.setNotificationUri(context.contentResolver, DocumentsContract.buildRootsUri(authority(context)))
        if (appLocked(context)) return cursor
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID)
            add(Root.COLUMN_TITLE, context.getString(R.string.picker_documents_root))
            add(Root.COLUMN_SUMMARY, context.getString(R.string.picker_documents_summary))
            add(Root.COLUMN_ICON, context.applicationInfo.icon)
            add(Root.COLUMN_MIME_TYPES, "image/*\nvideo/*")
            add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_RECENTS or Root.FLAG_SUPPORTS_SEARCH)
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DefaultDocumentProjection)
        when (val id = DocumentId.parse(documentId)) {
            DocumentId.Root -> cursor.addRoot(requireContext())
            is DocumentId.Album -> cursor.addAlbum(findAlbum(id) ?: throw FileNotFoundException(documentId), requireContext())
            is DocumentId.Media -> cursor.addMedia(store.find(id.key) ?: throw FileNotFoundException(documentId))
        }
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DefaultDocumentProjection)
        if (appLocked(requireContext())) return cursor
        when (val id = DocumentId.parse(parentDocumentId)) {
            DocumentId.Root -> catalog(fresh = true).albums.forEach { cursor.addAlbum(it, requireContext()) }
            is DocumentId.Album -> {
                val catalog = catalog(fresh = false)
                if (catalog.albums.none { it.volumeName == id.volumeName && it.bucketId == id.bucketId }) {
                    throw FileNotFoundException(parentDocumentId)
                }
                val selection = SqlSelection.allOf(catalog.baseSelection, PickerSql.albumSelection(id.volumeName, id.bucketId))
                store.page(selection, offset = 0, limit = MAX_CHILDREN).forEach(cursor::addMedia)
            }
            is DocumentId.Media -> throw FileNotFoundException("$parentDocumentId is not a folder")
        }
        return cursor
    }

    override fun queryRecentDocuments(rootId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DefaultDocumentProjection)
        if (rootId != ROOT_ID || appLocked(requireContext())) return cursor
        store.page(catalog(fresh = true).baseSelection, offset = 0, limit = MAX_RECENTS).forEach(cursor::addMedia)
        return cursor
    }

    override fun querySearchDocuments(rootId: String, query: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DefaultDocumentProjection)
        if (rootId != ROOT_ID || query.isBlank() || appLocked(requireContext())) return cursor
        val selection = SqlSelection.allOf(catalog(fresh = false).baseSelection, PickerSql.nameSelection(query))
        store.page(selection, offset = 0, limit = MAX_SEARCH_RESULTS).forEach(cursor::addMedia)
        return cursor
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode != "r") throw UnsupportedOperationException("Lightforge documents are read-only")
        val id = DocumentId.parse(documentId) as? DocumentId.Media ?: throw FileNotFoundException(documentId)
        val media = store.find(id.key) ?: throw FileNotFoundException(documentId)
        return requireContext().contentResolver.openFileDescriptor(media.contentUri, "r", signal)
            ?: throw FileNotFoundException(documentId)
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point,
        signal: CancellationSignal?,
    ): AssetFileDescriptor {
        val uri = when (val id = DocumentId.parse(documentId)) {
            is DocumentId.Media -> store.find(id.key)?.contentUri
            is DocumentId.Album -> findAlbum(id)?.let { album -> store.find(album.cover)?.contentUri }
            DocumentId.Root -> null
        } ?: throw FileNotFoundException(documentId)
        // MediaStore serves its own cached thumbnail for a typed open with EXTRA_SIZE.
        val options = Bundle().apply { putParcelable(ContentResolver.EXTRA_SIZE, sizeHint) }
        return requireContext().contentResolver.openTypedAssetFileDescriptor(uri, "image/*", options, signal)
            ?: throw FileNotFoundException(documentId)
    }

    private fun findAlbum(id: DocumentId.Album): PickerAlbum? =
        catalog(fresh = false).albums.firstOrNull { it.volumeName == id.volumeName && it.bucketId == id.bucketId }

    /**
     * Folder listing needs a full scan of the accepted media. Opening a folder right after the
     * root listing reuses that scan for a few seconds; the root listing itself always rescans.
     */
    private fun catalog(fresh: Boolean): PickerCatalog {
        val now = SystemClock.elapsedRealtime()
        cachedCatalog?.let { (at, catalog) -> if (!fresh && now - at < CATALOG_TTL_MILLIS) return catalog }
        val rules = runBlocking { FolderRules.load(requireContext()) }
        return store.catalog(AllMedia, rules).also { cachedCatalog = now to it }
    }

    private fun appLocked(context: Context): Boolean = runBlocking {
        GallerySettingsRepository(context).settings.first().security.appLockEnabled
    }

    internal companion object {
        private const val ROOT_ID = "gallery"
        const val ROOT_DOCUMENT_ID = "root"
        private const val MAX_CHILDREN = 20_000
        private const val MAX_RECENTS = 64
        private const val MAX_SEARCH_RESULTS = 500
        private const val CATALOG_TTL_MILLIS = 10_000L
        private val AllMedia = PickRequest(KindFilter.Any, KindFilter.Any, allowMultiple = true)

        private fun authority(context: Context) = "${context.packageName}.gallerydocuments"

        private val DefaultRootProjection = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_ICON,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_FLAGS,
        )

        private val DefaultDocumentProjection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_SIZE,
            Document.COLUMN_FLAGS,
        )
    }
}

private fun MatrixCursor.addRoot(context: Context) {
    newRow().apply {
        add(Document.COLUMN_DOCUMENT_ID, GalleryDocumentsProvider.ROOT_DOCUMENT_ID)
        add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
        add(Document.COLUMN_DISPLAY_NAME, context.getString(R.string.picker_documents_root))
        add(Document.COLUMN_FLAGS, Document.FLAG_DIR_PREFERS_GRID)
    }
}

private fun MatrixCursor.addAlbum(album: PickerAlbum, context: Context) {
    newRow().apply {
        add(Document.COLUMN_DOCUMENT_ID, DocumentId.Album(album.volumeName, album.bucketId).encode())
        add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
        add(Document.COLUMN_DISPLAY_NAME, album.name ?: context.getString(R.string.picker_storage_root))
        add(Document.COLUMN_LAST_MODIFIED, album.latestModifiedSeconds * 1000)
        add(
            Document.COLUMN_FLAGS,
            Document.FLAG_DIR_PREFERS_GRID or Document.FLAG_DIR_PREFERS_LAST_MODIFIED or
                Document.FLAG_SUPPORTS_THUMBNAIL,
        )
    }
}

private fun MatrixCursor.addMedia(media: PickerMedia) {
    newRow().apply {
        add(Document.COLUMN_DOCUMENT_ID, DocumentId.Media(media.key).encode())
        add(Document.COLUMN_MIME_TYPE, media.mimeType ?: if (media.isVideo) "video/*" else "image/*")
        add(Document.COLUMN_DISPLAY_NAME, media.displayName ?: media.key.mediaStoreId.toString())
        add(Document.COLUMN_LAST_MODIFIED, media.dateModifiedSeconds * 1000)
        add(Document.COLUMN_SIZE, media.sizeBytes)
        add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_THUMBNAIL)
    }
}

/** Opaque document ids: `root`, `album:<volume>:<bucketId>` and `media:<volume>:<mediaStoreId>`. */
sealed interface DocumentId {
    fun encode(): String

    data object Root : DocumentId {
        override fun encode() = "root"
    }

    data class Album(val volumeName: String, val bucketId: Long) : DocumentId {
        override fun encode() = "album:$volumeName:$bucketId"
    }

    data class Media(val key: MediaKey) : DocumentId {
        override fun encode() = "media:${key.volumeName}:${key.mediaStoreId}"
    }

    companion object {
        fun parse(value: String): DocumentId {
            if (value == "root") return Root
            val parts = value.split(':')
            val volume = parts.getOrNull(1)?.takeIf(String::isNotBlank)
            val number = parts.getOrNull(2)?.toLongOrNull()
            if (parts.size != 3 || volume == null || number == null) throw FileNotFoundException(value)
            return when (parts[0]) {
                "album" -> Album(volume, number)
                "media" -> if (number >= 0) Media(MediaKey(volume, number)) else throw FileNotFoundException(value)
                else -> throw FileNotFoundException(value)
            }
        }
    }
}
