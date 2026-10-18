package com.librestatic.lightforge

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore
import com.librestatic.lightforge.core.model.MediaKey
import java.io.File
import java.io.InputStream

/** True when MediaStore's current row still sits at the path the index remembers (same folder, same name). */
internal fun sameStoredLocation(source: TransferSource, relativePath: String?, displayName: String?): Boolean =
    displayName == source.displayName &&
        relativePath?.trim('/') == source.relativePath.trim('/')

/** Byte-for-byte comparison of two streams. Neither stream is closed. */
internal fun sameBytes(a: InputStream, b: InputStream): Boolean {
    val bufferA = ByteArray(COMPARE_BUFFER)
    val bufferB = ByteArray(COMPARE_BUFFER)
    while (true) {
        val readA = a.readFully(bufferA)
        val readB = b.readFully(bufferB)
        if (readA != readB) return false
        if (readA <= 0) return true
        if (!bufferA.contentEquals(bufferB, readA)) return false
    }
}

private fun InputStream.readFully(buffer: ByteArray): Int {
    var total = 0
    while (total < buffer.size) {
        val n = read(buffer, total, buffer.size - total)
        if (n < 0) break
        total += n
    }
    return total
}

private fun ByteArray.contentEquals(other: ByteArray, length: Int): Boolean {
    for (i in 0 until length) if (this[i] != other[i]) return false
    return true
}

private const val COMPARE_BUFFER = 64 * 1024

private fun filesUri(key: MediaKey) =
    ContentUris.withAppendedId(MediaStore.Files.getContentUri(key.volumeName), key.mediaStoreId)

/**
 * The keys in [sources] whose MediaStore row (by _ID) is still at the remembered folder and name.
 * A stale index row must never steer a file operation at a different file.
 */
internal fun ContentResolver.keysStillAtRememberedPath(sources: List<TransferSource>): Set<MediaKey> =
    sources.filterTo(HashSet<TransferSource>()) { source ->
        runCatching {
            query(
                filesUri(source.key),
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                c.moveToFirst() && sameStoredLocation(source, c.getString(0), c.getString(1))
            } ?: false
        }.getOrDefault(false)
    }.mapTo(HashSet()) { it.key }

/** True when the file written at [targetPath] holds exactly the bytes of the item's own content URI. */
internal fun ContentResolver.copyMatchesItem(source: TransferSource, targetPath: String): Boolean = runCatching {
    val target = File(targetPath)
    if (!target.isFile) return@runCatching false
    openInputStream(filesUri(source.key))?.buffered()?.use { original ->
        target.inputStream().buffered().use { copy -> sameBytes(original, copy) }
    } ?: false
}.getOrDefault(false)
