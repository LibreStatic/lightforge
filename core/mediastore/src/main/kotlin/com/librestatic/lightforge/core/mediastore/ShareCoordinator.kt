package com.librestatic.lightforge.core.mediastore

import android.content.ClipData
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import com.librestatic.lightforge.core.model.MediaKind
import java.io.File

data class ShareCandidate(
    val target: MediaActionTarget,
    val mimeType: String?,
    val isPrivate: Boolean = false,
)

data class PreparedShareAsset(val uri: Uri, val mimeType: String)

fun interface ShareCopySanitizer {
    /** Must create a new local temporary copy and leave the original untouched. */
    suspend fun prepare(candidate: ShareCandidate): PreparedShareAsset
}

data class SharePlan(
    val intent: Intent,
    val sharedCount: Int,
    val excludedPrivateCount: Int,
)

class ShareCoordinator(private val resolver: ContentResolver) {
    fun original(candidates: List<ShareCandidate>): SharePlan {
        require(candidates.size <= MaxShareItems) { "Share batches must remain bounded" }
        val excluded = candidates.count(ShareCandidate::isPrivate)
        val assets = candidates.filterNot(ShareCandidate::isPrivate)
            .distinctBy { it.target.key }
            .map { PreparedShareAsset(it.target.uri(), it.mimeType ?: it.target.defaultMime()) }
        return build(assets, excluded)
    }

    fun sanitized(assets: List<PreparedShareAsset>, excludedPrivateCount: Int): SharePlan {
        require(assets.size <= MaxShareItems) { "Share batches must remain bounded" }
        require(excludedPrivateCount >= 0)
        return build(assets.distinctBy(PreparedShareAsset::uri), excludedPrivateCount)
    }

    private fun build(assets: List<PreparedShareAsset>, excluded: Int): SharePlan {
        require(assets.isNotEmpty()) { "No shareable media remains" }
        val uris = ArrayList(assets.map(PreparedShareAsset::uri))
        val action = if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
        val intent = Intent(action).apply {
            type = commonMime(assets.map(PreparedShareAsset::mimeType))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.single())
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newUri(resolver, "Lightforge media", uris.first()).also { clip ->
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
        }
        return SharePlan(intent, uris.size, excluded)
    }

    private fun commonMime(mimes: List<String>): String {
        val normalized = mimes.map(String::lowercase).distinct()
        if (normalized.size == 1) return normalized.single()
        return when {
            normalized.all { it.startsWith("image/") } -> "image/*"
            normalized.all { it.startsWith("video/") } -> "video/*"
            else -> "*/*"
        }
    }

    private fun MediaActionTarget.uri(): Uri = ContentUris.withAppendedId(
        when (kind) {
            MediaKind.Image -> MediaStore.Images.Media.getContentUri(key.volumeName)
            MediaKind.Video -> MediaStore.Video.Media.getContentUri(key.volumeName)
        },
        key.mediaStoreId,
    )

    private fun MediaActionTarget.defaultMime() = if (kind == MediaKind.Image) "image/*" else "video/*"

    companion object { const val MaxShareItems = 500 }
}

/** Owns only cacheDir files; cleanup never touches MediaStore or user content. */
class ShareTempRegistry(
    cacheDir: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = DefaultTtlMillis,
) {
    private val directory = File(cacheDir, "share").apply { mkdirs() }

    init { require(ttlMillis > 0) }

    fun allocate(extension: String): File {
        require(extension.matches(ExtensionPattern))
        return File(directory, "${nowMillis()}-${java.util.UUID.randomUUID()}.$extension")
    }

    fun cleanupExpired(): Int {
        val cutoff = nowMillis() - ttlMillis
        return directory.listFiles().orEmpty().count { file ->
            file.isFile && file.lastModified() < cutoff && file.delete()
        }
    }

    companion object {
        const val DefaultTtlMillis = 24L * 60 * 60 * 1_000
        private val ExtensionPattern = Regex("[A-Za-z0-9]{1,10}")
    }
}
