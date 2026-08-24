package com.ugallery.feature.privatealbum

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.ugallery.core.security.PrivateAlbumCrypto
import com.ugallery.core.mediastore.MediaWriteSpec
import com.ugallery.core.mediastore.PendingMediaWriter
import com.ugallery.core.mediastore.MediaActionTarget
import com.ugallery.core.mediastore.mediaUri
import com.ugallery.core.model.MediaKind
import com.ugallery.core.model.TimelineMedia
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import javax.crypto.SecretKey

data class PrivateMediaSummary(
    val id: Long,
    val displayName: String,
    val mimeType: String,
    val mediaKind: String,
    val addedAtMillis: Long,
    val width: Int,
    val height: Int,
    val durationMillis: Long,
)

data class ImportResult(
    val success: Boolean,
    val mediaId: Long? = null,
    val error: String? = null,
)

class PrivateAlbumRepository(
    private val context: Context,
    private val database: PrivateAlbumDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val containerDir: File by lazy {
        File(context.filesDir, "private-album").apply { mkdirs() }
    }

    val allMedia: Flow<List<PrivateMediaEntity>> = database.privateMediaDao().getAll()

    suspend fun isSetup(): Boolean = withContext(ioDispatcher) {
        database.metadataDao().get()?.isSetup == true
    }

    suspend fun setup(masterKey: SecretKey) = withContext(ioDispatcher) {
        database.metadataDao().upsert(
            PrivateAlbumMetadataEntity(
                id = 0,
                isSetup = true,
                keyAlias = PrivateAlbumCrypto.MASTER_KEY_ALIAS,
                createdAtMillis = System.currentTimeMillis(),
            )
        )
    }

    suspend fun importFromUri(
        sourceUri: Uri,
        displayName: String,
        mimeType: String,
        mediaKind: String,
        width: Int = 0,
        height: Int = 0,
        durationMillis: Long = 0,
        masterKey: SecretKey,
    ): ImportResult = withContext(ioDispatcher) {
        var containerFile: File? = null
        var committed = false
        try {
            val resolver = context.contentResolver
            val dataKey = PrivateAlbumCrypto.generateDataKey()
            val encryptedDataKey = PrivateAlbumCrypto.encryptDataKey(dataKey, masterKey)

            containerFile = File(containerDir, "private_${System.nanoTime()}_${displayName.hashCode()}.ugpc")
            val input = resolver.openInputStream(sourceUri)
                ?: return@withContext ImportResult(false, error = "Cannot open source URI")

            input.use { inputStream ->
                FileOutputStream(requireNotNull(containerFile)).use { output ->
                    val metadata = PrivateAlbumCrypto.encryptStream(
                        input = inputStream,
                        output = output,
                        dataKey = dataKey,
                        originalMimeType = mimeType,
                    )

                    val entity = PrivateMediaEntity(
                        originalMediaKey = sourceUri.toString(),
                        originalMimeType = mimeType,
                        originalDisplayName = displayName,
                        containerPath = requireNotNull(containerFile).absolutePath,
                        containerSizeBytes = requireNotNull(containerFile).length(),
                        encryptedDataKey = encryptedDataKey.encryptedKey,
                        dataKeyIv = encryptedDataKey.iv,
                        chunkSize = metadata.chunkSize,
                        totalChunks = metadata.totalChunks,
                        ivBase = metadata.ivBase,
                        sha256 = metadata.sha256,
                        addedAtMillis = System.currentTimeMillis(),
                        mediaKind = mediaKind,
                        width = width,
                        height = height,
                        durationMillis = durationMillis,
                    )
                    val id = database.privateMediaDao().insert(entity)
                    committed = true
                    ImportResult(success = true, mediaId = id)
                }
            }
        } catch (e: Exception) {
            ImportResult(false, error = e.message ?: "Unknown error")
        } finally {
            if (!committed) containerFile?.delete()
        }
    }

    suspend fun importFromMedia(
        media: TimelineMedia,
        masterKey: SecretKey,
    ): ImportResult {
        val target = MediaActionTarget(media.key, media.kind)
        val uri = target.mediaUri()
        val fallbackMime = if (media.kind == MediaKind.Video) "video/*" else "image/*"
        return importFromUri(
            sourceUri = uri,
            displayName = media.displayName
                ?: "UGallery-${media.key.mediaStoreId}.${if (media.kind == MediaKind.Video) "mp4" else "jpg"}",
            mimeType = context.contentResolver.getType(uri) ?: fallbackMime,
            mediaKind = if (media.kind == MediaKind.Video) "video" else "image",
            width = media.width,
            height = media.height,
            durationMillis = media.durationMillis,
            masterKey = masterKey,
        )
    }

    suspend fun exportToMediaStore(
        mediaId: Long,
        masterKey: SecretKey,
    ): Uri? = withContext(ioDispatcher) {
        val entity = database.privateMediaDao().getById(mediaId)
            ?: return@withContext null

        val encryptedDataKey = PrivateAlbumCrypto.EncryptedDataKey(
            entity.encryptedDataKey,
            entity.dataKeyIv,
        )
        val dataKey = PrivateAlbumCrypto.decryptDataKey(encryptedDataKey, masterKey)

        val exportFile = File(context.cacheDir, "private-export-${System.nanoTime()}-${entity.originalDisplayName}")
        val containerFile = File(entity.containerPath)
        if (!containerFile.exists()) return@withContext null

        try {
            FileInputStream(containerFile).use { input ->
                FileOutputStream(exportFile).use { output ->
                    PrivateAlbumCrypto.decryptStream(input, output, dataKey)
                }
            }
            PendingMediaWriter(context.contentResolver).publishFile(
                exportFile,
                MediaWriteSpec(
                    destinationVolume = MediaStore.VOLUME_EXTERNAL_PRIMARY,
                    kind = if (entity.mediaKind == "video") MediaKind.Video else MediaKind.Image,
                    displayName = entity.originalDisplayName,
                    mimeType = entity.originalMimeType,
                    relativePath = if (entity.mediaKind == "video") "Movies/UGallery" else "Pictures/UGallery",
                ),
            ).uri
        } finally {
            exportFile.delete()
        }
    }

    suspend fun delete(mediaId: Long) = withContext(ioDispatcher) {
        val entity = database.privateMediaDao().getById(mediaId)
        if (entity != null) {
            val containerFile = File(entity.containerPath)
            containerFile.delete()
            database.privateMediaDao().deleteById(mediaId)
        }
    }

    suspend fun deleteAll() = withContext(ioDispatcher) {
        database.privateMediaDao().deleteAll()
        database.metadataDao().delete()
        containerDir.listFiles()?.forEach { it.delete() }
    }

    suspend fun count(): Int = withContext(ioDispatcher) {
        database.privateMediaDao().count()
    }

    suspend fun getContainerFile(mediaId: Long): File? {
        // Synchronous lookup for video playback
        return database.privateMediaDao().getById(mediaId)?.let { File(it.containerPath) }
    }

    suspend fun getDecryptedDataKey(mediaId: Long, masterKey: SecretKey): SecretKey? = withContext(ioDispatcher) {
        val entity = database.privateMediaDao().getById(mediaId) ?: return@withContext null
        val encryptedDataKey = PrivateAlbumCrypto.EncryptedDataKey(
            entity.encryptedDataKey,
            entity.dataKeyIv,
        )
        PrivateAlbumCrypto.decryptDataKey(encryptedDataKey, masterKey)
    }

    suspend fun getMetadata(mediaId: Long): PrivateMediaEntity? = withContext(ioDispatcher) {
        database.privateMediaDao().getById(mediaId)
    }
}
