package com.librestatic.lightforge.core.mediastore

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.librestatic.lightforge.core.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.io.FileOutputStream
import kotlin.math.max

object ScopedMediaOperations {
    fun viewIntent(uri: Uri, mimeType: String): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun viewIntent(target: MediaActionTarget, mimeType: String): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(target.mediaUri(), mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun setAsIntent(target: MediaActionTarget, mimeType: String): Intent = Intent(Intent.ACTION_ATTACH_DATA).apply {
        setDataAndType(target.mediaUri(), mimeType)
        putExtra("mimeType", mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun setAsIntent(uri: Uri, mimeType: String): Intent = Intent(Intent.ACTION_ATTACH_DATA).apply {
        setDataAndType(uri, mimeType)
        putExtra("mimeType", mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    suspend fun copyToTree(
        resolver: ContentResolver,
        target: MediaActionTarget,
        treeUri: Uri,
        displayName: String,
        mimeType: String,
        lastModifiedMillis: Long? = null,
    ): Uri = withContext(Dispatchers.IO) {
        copyUriToTree(resolver, target.mediaUri(), treeUri, displayName, mimeType, lastModifiedMillis).uri
    }

    suspend fun copyToTree(
        resolver: ContentResolver,
        sourceUri: Uri,
        treeUri: Uri,
        displayName: String,
        mimeType: String,
        lastModifiedMillis: Long? = null,
    ): Uri = withContext(Dispatchers.IO) {
        copyUriToTree(resolver, sourceUri, treeUri, displayName, mimeType, lastModifiedMillis).uri
    }

    suspend fun copyToTreeVerified(
        resolver: ContentResolver, target: MediaActionTarget, treeUri: Uri,
        displayName: String, mimeType: String, lastModifiedMillis: Long? = null,
    ): VerifiedTreeCopy = withContext(Dispatchers.IO) {
        copyUriToTree(resolver, target.mediaUri(), treeUri, displayName, mimeType, lastModifiedMillis)
    }

    private suspend fun copyUriToTree(
        resolver: ContentResolver,
        sourceUri: Uri,
        treeUri: Uri,
        displayName: String,
        mimeType: String,
        lastModifiedMillis: Long?,
    ): VerifiedTreeCopy {
        val safeName = validateDisplayName(displayName)
        val directory = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val resolvedMimeType = resolver.getType(sourceUri)
            ?: mimeType.takeUnless { it.endsWith("/*") }
            ?: "application/octet-stream"
        val destination = DocumentsContract.createDocument(resolver, directory, resolvedMimeType, safeName)
            ?: error("The selected folder did not create the copy")
        // A provider must never make the newly created destination alias the original.
        check(destination != sourceUri && !(destination.authority == sourceUri.authority &&
            runCatching { DocumentsContract.getDocumentId(destination) == DocumentsContract.getDocumentId(sourceUri) }.getOrDefault(false))) {
            "The selected folder returned the original document"
        }
        val context = coroutineContext
        return try {
            context.ensureActive()
            val fingerprint = resolver.openInputStream(sourceUri).use { input ->
                requireNotNull(input) { "Could not open the source media" }
                resolver.openOutputStream(destination, "w").use { output ->
                    requireNotNull(output) { "Could not open the destination folder" }
                    VerifiedStreamCopy.copy(input, output) { context.ensureActive() }
                }
            }
            lastModifiedMillis?.takeIf { it > 0L }?.let { timestamp ->
                runCatching {
                    resolver.update(
                        destination,
                        ContentValues().apply { put(DocumentsContract.Document.COLUMN_LAST_MODIFIED, timestamp) },
                        null,
                        null,
                    )
                }
            }
            // Verify after close and metadata changes; never trust provider-reported size.
            resolver.openInputStream(destination).use { input ->
                VerifiedStreamCopy.verify(requireNotNull(input) { "Could not reopen the copy" }, fingerprint) { context.ensureActive() }
            }
            resolver.openInputStream(sourceUri).use { input ->
                VerifiedStreamCopy.verify(requireNotNull(input) { "Could not recheck the original" }, fingerprint) { context.ensureActive() }
            }
            coroutineContext.ensureActive()
            VerifiedTreeCopy(destination, fingerprint.bytes, fingerprint.sha256)
        } catch (failure: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, destination) }
            throw failure
        }
    }

    fun rename(resolver: ContentResolver, target: MediaActionTarget, displayName: String) {
        val updated = resolver.update(
            target.mediaUri(),
            ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, validateDisplayName(displayName)) },
            null,
            null,
        )
        check(updated == 1) { "The media could not be renamed" }
    }

    fun repairDateTaken(resolver: ContentResolver, target: MediaActionTarget, dateTakenMillis: Long) {
        require(dateTakenMillis > 0L) { "The capture date must be valid" }
        val updated = resolver.update(
            target.mediaUri(),
            ContentValues().apply { put(MediaStore.MediaColumns.DATE_TAKEN, dateTakenMillis) },
            null,
            null,
        )
        check(updated == 1) { "The capture date could not be updated" }
    }

    fun printImage(context: Context, target: MediaActionTarget, title: String) {
        require(target.kind == MediaKind.Image) { "Only images can be printed" }
        val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        manager.print(title, ImagePrintAdapter(context.contentResolver, target.mediaUri(), title), null)
    }

    fun printImage(context: Context, uri: Uri, title: String) {
        val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        manager.print(title, ImagePrintAdapter(context.contentResolver, uri, title), null)
    }

    fun validateDisplayName(value: String): String {
        val name = value.trim()
        require(name.isNotEmpty()) { "The file name cannot be empty" }
        require(name != "." && name != ".." && '/' !in name && '\\' !in name && '\u0000' !in name) {
            "The file name contains unsupported characters"
        }
        return name.take(255)
    }
}

fun MediaActionTarget.mediaUri(): Uri = ContentUris.withAppendedId(
    when (kind) {
        MediaKind.Image -> MediaStore.Images.Media.getContentUri(key.volumeName)
        MediaKind.Video -> MediaStore.Video.Media.getContentUri(key.volumeName)
    },
    key.mediaStoreId,
)

private class ImagePrintAdapter(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val title: String,
) : PrintDocumentAdapter() {
    private var attributes = PrintAttributes.Builder().build()

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal.isCanceled) return callback.onLayoutCancelled()
        attributes = newAttributes
        callback.onLayoutFinished(
            PrintDocumentInfo.Builder("$title.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_PHOTO)
                .setPageCount(1)
                .build(),
            oldAttributes != newAttributes,
        )
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback,
    ) {
        if (cancellationSignal.isCanceled) return callback.onWriteCancelled()
        runCatching {
            val bitmap = decodeSampledBitmap(resolver, uri)
            try {
                val document = PdfDocument()
                try {
                    val width = attributes.mediaSize?.widthMils?.let { it * 72 / 1000 } ?: 612
                    val height = attributes.mediaSize?.heightMils?.let { it * 72 / 1000 } ?: 792
                    val page = document.startPage(PdfDocument.PageInfo.Builder(width, height, 1).create())
                    val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
                    val left = (width - bitmap.width * scale) / 2f
                    val top = (height - bitmap.height * scale) / 2f
                    page.canvas.save()
                    page.canvas.translate(left, top)
                    page.canvas.scale(scale, scale)
                    page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                    page.canvas.restore()
                    document.finishPage(page)
                    FileOutputStream(destination.fileDescriptor).use(document::writeTo)
                } finally {
                    document.close()
                }
            } finally {
                bitmap.recycle()
            }
        }.onSuccess {
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        }.onFailure { callback.onWriteFailed(it.message ?: "Could not print image") }
    }
}

private fun decodeSampledBitmap(resolver: ContentResolver, uri: Uri): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openFileDescriptor(uri, "r")?.use { BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, bounds) }
        ?: error("Could not open image")
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
    return resolver.openFileDescriptor(uri, "r")?.use {
        BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: error("Could not decode image")
}
