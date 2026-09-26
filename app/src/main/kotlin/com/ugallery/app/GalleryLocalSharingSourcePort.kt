package com.ugallery.app

import android.content.Context
import android.content.Intent
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.ugallery.core.mediastore.LocalShareSanitizer
import com.ugallery.core.model.MediaKind
import com.ugallery.feature.localsharing.*
import com.ugallery.feature.settings.PersistableUriGrants
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Selected originals become immutable private snapshots before any offer or network activity. */
internal class GalleryLocalSharingSourcePort(context: Context) : LocalSharingSourcePort {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    // Grants live only until the transfer has its private snapshots or is cancelled.
    private val grants = PersistableUriGrants(this.context, "local-sharing-grants.json")

    override suspend fun retain(transfer: String, selection: List<String>) = withContext(Dispatchers.IO) {
        require(selection.size in 1..LOCAL_SHARING_MAX_FILES && selection.distinct().size == selection.size)
        selection.forEach { raw ->
            val uri = Uri.parse(raw)
            require(uri.scheme == "content")
            if (uri.authority != MediaStore.AUTHORITY && uri.authority != "${context.packageName}.fileprovider") {
                grants.retain(transfer, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION, required = true)
                check(resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission })
            }
            // Existing MediaStore/library grants need no synthetic SAF grant or privilege elevation.
            read(uri) { }
        }
    }

    override suspend fun release(transfer: String) = withContext(Dispatchers.IO) { grants.release(transfer) }

    override suspend fun prepare(
        selection: List<String>, stripLocation: Boolean, destination: File, check: () -> Unit,
    ): List<LocalSharingPreparedSource> = withContext(Dispatchers.IO) {
        require(selection.size in 1..LOCAL_SHARING_MAX_FILES && selection.distinct().size == selection.size)
        require(destination.isDirectory && destination.listFiles().orEmpty().isEmpty())
        val coroutine = currentCoroutineContext()
        val checkCurrent = { coroutine.ensureActive(); check() }
        var total = 0L
        selection.mapIndexed { index, raw ->
            checkCurrent()
            val uri = Uri.parse(raw)
            require(uri.scheme == "content")
            val mime = requireNotNull(resolver.getType(uri))
            val kind = when {
                mime.startsWith("image/") -> MediaKind.Image
                mime.startsWith("video/") -> MediaKind.Video
                else -> error("Only photo and video originals are supported")
            }
            val before = metadata(uri)
            val original = File(destination, "$index.original")
            val first = read(uri) { copy(it, original, checkCurrent) }
            val second = read(uri) { digest(it, checkCurrent) }
            check(first == second && before == metadata(uri)) { "Selected original changed during preparation" }
            val payload = File(destination, "$index.payload")
            val resultMime: String
            val preparedHash: Pair<Long, String>
            if (stripLocation) {
                // Render the frozen copy, never a second potentially changed source or a fallback original.
                val asset = LocalShareSanitizer(context).prepare(Uri.fromFile(original), kind)
                resultMime = asset.mimeType
                preparedHash = read(asset.uri) { copy(it, payload, checkCurrent) }
                requireLocationFree(payload, kind)
                check(original.delete()) { "Private original cleanup failed" }
            } else {
                check(original.renameTo(payload)) { "Private source publication failed" }
                resultMime = mime
                preparedHash = first
            }
            checkCurrent()
            total += preparedHash.first
            require(total <= LOCAL_SHARING_MAX_TOTAL_BYTES)
            val sourceId = hash((raw + "|generation-added=" + before.generationAdded).toByteArray())
            val revision = hash((preparedHash.second + "|sanitized=" + stripLocation).toByteArray())
            val name = if (stripLocation) {
                before.name.substringBeforeLast('.', before.name).take(220) + when (resultMime) {
                    "image/png" -> ".png"
                    "image/webp" -> ".webp"
                    "video/mp4" -> ".mp4"
                    else -> ".jpg"
                }
            } else before.name
            LocalSharingPreparedSource(
                LocalSharingEntry(sourceId, revision, name, resultMime, preparedHash.first,
                    preparedHash.second, before.modifiedMillis, stripLocation).also { it.validate() },
                payload.name,
            )
        }
    }

    private data class SourceMetadata(
        val name: String, val bytes: Long?, val modifiedMillis: Long,
        val generationAdded: Long?, val generationModified: Long?,
    )

    private fun metadata(uri: Uri): SourceMetadata = resolver.query(uri, null, null, null, null).use { cursor ->
        check(cursor != null && cursor.moveToFirst()) { "Selected source metadata is unavailable" }
        fun text(key: String) = cursor.getColumnIndex(key).takeIf { it >= 0 }?.let { cursor.getString(it) }
        fun number(key: String) = cursor.getColumnIndex(key).takeIf { it >= 0 && !cursor.isNull(it) }?.let { cursor.getLong(it) }
        val rawName = text(OpenableColumns.DISPLAY_NAME) ?: "Photo"
        val name = rawName.take(255).map { if (it == '/' || it == '\\' || it.code < 32 || it.code == 127) '_' else it }.joinToString("")
            .takeUnless { it.isBlank() || it in setOf(".", "..") } ?: "Photo"
        val modified = number(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            ?: number(MediaStore.MediaColumns.DATE_MODIFIED)?.let { Math.multiplyExact(it, 1000L) } ?: 0L
        SourceMetadata(name, number(OpenableColumns.SIZE), modified.coerceAtLeast(0),
            number(MediaStore.MediaColumns.GENERATION_ADDED), number(MediaStore.MediaColumns.GENERATION_MODIFIED))
    }

    private fun <T> read(uri: Uri, action: (InputStream) -> T): T =
        requireNotNull(resolver.openInputStream(uri)) { "Selected source is unavailable" }.use(action)

    private fun digest(input: InputStream, check: () -> Unit, output: FileOutputStream? = null): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var bytes = 0L
        while (true) {
            check()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            bytes += count
            require(bytes <= LOCAL_SHARING_MAX_FILE_BYTES)
            digest.update(buffer, 0, count)
            output?.write(buffer, 0, count)
        }
        return bytes to digest.digest().hex()
    }

    private fun copy(input: InputStream, output: File, check: () -> Unit): Pair<Long, String> {
        check(output.createNewFile()) { "Snapshot destination already exists" }
        return FileOutputStream(output).use { stream -> digest(input, check, stream).also { stream.fd.sync() } }
    }

    @Suppress("DEPRECATION")
    internal fun requireLocationFree(file: File, kind: MediaKind) {
        when (kind) {
            MediaKind.Image -> {
                val exif = ExifInterface(file.path)
                val tags = listOf("GPSLatitude", "GPSLongitude", "GPSLatitudeRef", "GPSLongitudeRef",
                    "GPSAltitude", "GPSAltitudeRef", "GPSProcessingMethod", "GPSAreaInformation",
                    "GPSDestLatitude", "GPSDestLongitude", "GPSDateStamp", "GPSTimeStamp")
                check(tags.all { exif.getAttribute(it) == null }) { "Location metadata remains in prepared copy" }
            }
            MediaKind.Video -> MediaMetadataRetriever().use { metadata ->
                metadata.setDataSource(file.path)
                check(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION).isNullOrBlank()) {
                    "Location metadata remains in prepared video"
                }
            }
        }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
