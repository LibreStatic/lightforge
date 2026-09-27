package com.librestatic.lightforge.core.search

import android.content.Context
import androidx.appsearch.app.AppSearchBatchResult
import androidx.appsearch.app.GenericDocument
import androidx.appsearch.app.PutDocumentsRequest
import androidx.appsearch.app.RemoveByDocumentIdRequest
import androidx.appsearch.app.SearchSpec
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.TimeUnit

data class MediaSearchDocument(
    val key: MediaKey,
    val kind: MediaKind,
    val mimeType: String?,
    val displayName: String?,
    val bucketName: String?,
    val timelineSortMillis: Long,
    val generationModified: Long,
    val favorite: Boolean,
    val width: Int = 0,
    val height: Int = 0,
    val ocrText: String? = null,
    val canonicalLabels: List<String> = emptyList(),
    val personIds: List<String> = emptyList(),
    val labelModelVersion: Long = 0,
    val ocrModelVersion: Long = 0,
    val durationMillis: Long = 0,
)

interface MediaSearchIndex : Closeable {
    suspend fun ensureSchema(forceOverride: Boolean = false)
    suspend fun put(documents: List<MediaSearchDocument>)
    suspend fun remove(keys: List<MediaKey>)
    suspend fun purgeVolume(volumeName: String)
    suspend fun clear()

    companion object {
        const val DefaultDatabase = "media"
        const val MaxBatchSize = 500
    }
}

class AppSearchMediaIndex(
    context: Context,
    private val databaseName: String = MediaSearchIndex.DefaultDatabase,
) : MediaSearchIndex {
    private val owner = LocalSearchSession(context.applicationContext)
    private val mutex = Mutex()
    private var session: androidx.appsearch.app.AppSearchSession? = null

    override suspend fun ensureSchema(forceOverride: Boolean) {
        withSession { active ->
            active.setSchemaAsync(owner.schemaRequest(forceOverride)).get(30, TimeUnit.SECONDS)
        }
    }

    override suspend fun put(documents: List<MediaSearchDocument>) {
        require(documents.size <= MediaSearchIndex.MaxBatchSize)
        if (documents.isEmpty()) return
        withSession { active ->
            val request = PutDocumentsRequest.Builder()
                .addGenericDocuments(documents.map(MediaSearchDocument::genericDocument))
                .build()
            active.putAsync(request).get(60, TimeUnit.SECONDS).requireSuccess("put")
        }
    }

    override suspend fun remove(keys: List<MediaKey>) {
        require(keys.size <= MediaSearchIndex.MaxBatchSize)
        if (keys.isEmpty()) return
        withSession { active ->
            keys.groupBy(MediaKey::volumeName).forEach { (volume, volumeKeys) ->
                val request = RemoveByDocumentIdRequest.Builder(namespace(volume))
                    .addIds(volumeKeys.map(MediaKey::documentId))
                    .build()
                active.removeAsync(request).get(30, TimeUnit.SECONDS).requireSuccess("remove")
            }
        }
    }

    override suspend fun purgeVolume(volumeName: String) {
        withSession { active ->
            active.removeAsync(
                "",
                SearchSpec.Builder().addFilterNamespaces(namespace(volumeName)).build(),
            ).get(30, TimeUnit.SECONDS)
        }
    }

    override suspend fun clear() {
        withSession { active ->
            active.removeAsync("", SearchSpec.Builder().build()).get(30, TimeUnit.SECONDS)
        }
    }

    override fun close() {
        session?.close()
        session = null
    }

    private suspend fun <T> withSession(block: (androidx.appsearch.app.AppSearchSession) -> T): T =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val active = session ?: owner.open(databaseName).get(30, TimeUnit.SECONDS).also {
                    session = it
                }
                block(active)
            }
        }

    private fun AppSearchBatchResult<String, Void>.requireSuccess(operation: String) {
        check(isSuccess) { "$operation failed for ${failures.keys.take(5)}" }
    }
}

internal fun MediaSearchDocument.genericDocument(): GenericDocument =
    GenericDocument.Builder<GenericDocument.Builder<*>>(
        namespace(key.volumeName),
        key.documentId(),
        MediaSearchSchema.Type,
    )
        .setCreationTimestampMillis(timelineSortMillis.coerceAtLeast(0))
        .setPropertyString(MediaSearchSchema.Property.MediaKey, "${key.volumeName}:${key.mediaStoreId}")
        .setPropertyString(MediaSearchSchema.Property.VolumeName, key.volumeName)
        .setPropertyLong(MediaSearchSchema.Property.MediaStoreId, key.mediaStoreId)
        .setPropertyString(MediaSearchSchema.Property.Kind, kind.name.lowercase())
        .setPropertyStringIfPresent(MediaSearchSchema.Property.MimeType, mimeType)
        .setPropertyStringIfPresent(MediaSearchSchema.Property.DisplayName, displayName)
        .setPropertyStringIfPresent(MediaSearchSchema.Property.BucketName, bucketName)
        .setPropertyStringIfPresent(MediaSearchSchema.Property.OcrText, ocrText)
        .setPropertyString(
            MediaSearchSchema.Property.NormalizedText,
            SearchTextNormalizer.normalizeForIndex(
                listOfNotNull(displayName, bucketName, ocrText) + canonicalLabels,
            ),
        )
        .setPropertyStringsIfPresent(MediaSearchSchema.Property.CanonicalLabels, canonicalLabels)
        .setPropertyStringsIfPresent(MediaSearchSchema.Property.PersonIds, personIds)
        .setPropertyLong(MediaSearchSchema.Property.TimelineSortMillis, timelineSortMillis)
        .setPropertyLong(MediaSearchSchema.Property.GenerationModified, generationModified)
        .setPropertyLong(MediaSearchSchema.Property.DurationMillis, durationMillis.coerceAtLeast(0L))
        .setPropertyLong(MediaSearchSchema.Property.Width, width.coerceAtLeast(0).toLong())
        .setPropertyLong(MediaSearchSchema.Property.Height, height.coerceAtLeast(0).toLong())
        .setPropertyBoolean(MediaSearchSchema.Property.Favorite, favorite)
        .setPropertyString(MediaSearchSchema.Property.FavoriteToken, if (favorite) "favorite" else "normal")
        .setPropertyLong(MediaSearchSchema.Property.SchemaVersion, MediaSearchSchema.Version)
        .setPropertyLong(MediaSearchSchema.Property.LabelModelVersion, labelModelVersion)
        .setPropertyLong(MediaSearchSchema.Property.OcrModelVersion, ocrModelVersion)
        .build()

private fun GenericDocument.Builder<*>.setPropertyStringIfPresent(name: String, value: String?) = apply {
    value?.takeIf(String::isNotBlank)?.let { setPropertyString(name, it) }
}

private fun GenericDocument.Builder<*>.setPropertyStringsIfPresent(name: String, values: List<String>) = apply {
    values.filter(String::isNotBlank).takeIf(List<String>::isNotEmpty)?.let {
        setPropertyString(name, *it.toTypedArray())
    }
}

private fun namespace(volumeName: String) = "media:$volumeName"
private fun MediaKey.documentId() = "$volumeName:$mediaStoreId"
