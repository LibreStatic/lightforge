package com.librestatic.lightforge

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import com.librestatic.lightforge.core.mediastore.VerifiedStreamCopy
import com.librestatic.lightforge.core.model.MediaKind
import java.io.InputStream
import java.io.OutputStream

/**
 * What the in-app destination picker needs from MediaStore when All files access is off. Moving is a
 * `RELATIVE_PATH` update of the same row (so favourites, albums and archive state stay), copying writes
 * a new row. Both are limited to the folders MediaStore accepts for photos and videos.
 */
internal interface MediaStoreGateway {
    /** Points the row at [relativePath] ("A/B/"); false when the platform refuses (no write access, folder not allowed). */
    fun move(source: TransferSource, relativePath: String): Boolean

    /** Writes a verified copy of the item into [volumeName]/[relativePath]; false (and nothing left behind) on any failure. */
    suspend fun copy(source: TransferSource, volumeName: String, relativePath: String): Boolean
}

internal class MediaStorePathTransfer(private val gateway: MediaStoreGateway) {
    /**
     * A move can keep the rows only inside one volume; everything else is copied and the originals
     * are then deleted with the system's own confirmation.
     */
    fun movesInPlace(sources: List<TransferSource>, target: FolderTarget): Boolean =
        sources.isNotEmpty() && sources.all { it.volumeName == target.volumeName }

    /** The path MediaStore wants ("A/B/"), or null for a volume root, which it never accepts for media. */
    fun destinationPath(target: FolderTarget): String? =
        target.relativePath?.trim('/')?.takeIf { it.isNotEmpty() }?.let { "$it/" }

    /** Moves each source into the folder; [TransferResult.moved] counts the rows the platform accepted. */
    fun move(sources: List<TransferSource>, target: FolderTarget): TransferResult {
        val path = destinationPath(target) ?: return TransferResult(TransferPurpose.Move, sources.size, failed = sources.size)
        var moved = 0
        var skipped = 0
        var failed = 0
        sources.forEach { source ->
            when {
                source.relativePath.trim('/') == path.trim('/') -> skipped++
                gateway.move(source, path) -> moved++
                else -> failed++
            }
        }
        return TransferResult(TransferPurpose.Move, sources.size, moved = moved, skipped = skipped, failed = failed)
    }

    /**
     * Copies each source; [copiedSources] are the ones now present at the destination, so a move that
     * crosses volumes can ask the user to delete exactly those originals.
     */
    suspend fun copy(
        purpose: TransferPurpose,
        sources: List<TransferSource>,
        target: FolderTarget,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Pair<TransferResult, List<TransferSource>> {
        val path = destinationPath(target)
            ?: return TransferResult(purpose, sources.size, failed = sources.size) to emptyList()
        val copied = ArrayList<TransferSource>()
        var failed = 0
        var skipped = 0
        sources.forEachIndexed { index, source ->
            onProgress(index, sources.size)
            when {
                // Copying onto itself would only add a duplicate.
                purpose == TransferPurpose.Move && source.volumeName == target.volumeName &&
                    source.relativePath.trim('/') == path.trim('/') -> skipped++
                gateway.copy(source, target.volumeName, path) -> copied += source
                else -> failed++
            }
        }
        onProgress(sources.size, sources.size)
        val result = if (purpose == TransferPurpose.Copy) {
            TransferResult(purpose, sources.size, copied = copied.size, skipped = skipped, failed = failed)
        } else {
            // A cross-volume move has not finished: the originals are still to be deleted.
            TransferResult(purpose, sources.size, awaitingDelete = copied, skipped = skipped, failed = failed)
        }
        return result to copied
    }
}

/**
 * The result of copying a batch into a folder picked with the system picker: a copy is done, a move still
 * waits for the originals of the [copied] items to be deleted. [failed] items were not written.
 */
internal fun treeTransferResult(purpose: TransferPurpose, total: Int, copied: List<TransferSource>, failed: Int): TransferResult =
    if (purpose == TransferPurpose.Copy) {
        TransferResult(purpose, total, copied = copied.size, failed = failed)
    } else {
        TransferResult(purpose, total, awaitingDelete = copied, failed = failed)
    }

/** The platform implementation: ordinary ContentResolver calls, no file permission beyond the media access the app has. */
internal class ResolverMediaStoreGateway(private val resolver: ContentResolver) : MediaStoreGateway {
    override fun move(source: TransferSource, relativePath: String): Boolean = runCatching {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath) }
        resolver.update(rowUri(source), values, null, null) > 0
    }.getOrDefault(false)

    override suspend fun copy(source: TransferSource, volumeName: String, relativePath: String): Boolean {
        val collection = when (source.kind) {
            MediaKind.Video -> MediaStore.Video.Media.getContentUri(volumeName)
            else -> MediaStore.Images.Media.getContentUri(volumeName)
        }
        val original = rowUri(source)
        val created = runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, source.displayName)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                resolver.getType(original)?.let { put(MediaStore.MediaColumns.MIME_TYPE, it) }
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                originalDates(original)?.let { (taken, modified) ->
                    taken?.let { put(MediaStore.MediaColumns.DATE_TAKEN, it) }
                    modified?.let { put(MediaStore.MediaColumns.DATE_MODIFIED, it) }
                }
            }
            resolver.insert(collection, values)
        }.getOrNull() ?: return false
        val ok = runCatching {
            // The original is deleted afterwards, so the copy is read back and compared by SHA-256, never by size.
            copyAndVerify(
                openOriginal = { resolver.openInputStream(original) },
                openDestination = { resolver.openOutputStream(created, "w") },
                openWritten = { resolver.openInputStream(created) },
            )
            resolver.update(created, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) > 0
        }.getOrDefault(false)
        if (!ok) runCatching { resolver.delete(created, null, null) }
        return ok
    }

    private fun rowUri(source: TransferSource): Uri {
        val base = when (source.kind) {
            MediaKind.Video -> MediaStore.Video.Media.getContentUri(source.volumeName)
            else -> MediaStore.Images.Media.getContentUri(source.volumeName)
        }
        return ContentUris.withAppendedId(base, source.key.mediaStoreId)
    }

    private fun originalDates(uri: Uri): Pair<Long?, Long?>? = runCatching {
        resolver.query(
            uri, arrayOf(MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED), null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) null
            else (if (c.isNull(0)) null else c.getLong(0)) to (if (c.isNull(1)) null else c.getLong(1))
        }
    }.getOrNull()
}

/**
 * Streams the original into the destination while fingerprinting it, then re-opens the written copy and
 * the original and requires both to match the fingerprint (byte count and SHA-256). Throws on any
 * mismatch or unopenable stream, so a caller that deletes the original afterwards never loses data.
 * A missing size column proves nothing: the check never depends on it.
 */
internal fun copyAndVerify(
    openOriginal: () -> InputStream?,
    openDestination: () -> OutputStream?,
    openWritten: () -> InputStream?,
) {
    val fingerprint = (openOriginal() ?: error("cannot open the original")).use { input ->
        (openDestination() ?: error("cannot open the destination")).use { output ->
            VerifiedStreamCopy.copy(input, output) {}
        }
    }
    (openWritten() ?: error("cannot read the copy back")).use { VerifiedStreamCopy.verify(it, fingerprint) {} }
    (openOriginal() ?: error("cannot re-open the original")).use { VerifiedStreamCopy.verify(it, fingerprint) {} }
}
