package com.ugallery.feature.privatealbum

import androidx.room.withTransaction
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.ugallery.core.security.PrivateAlbumCrypto
import com.ugallery.core.mediastore.MediaActionTarget
import com.ugallery.core.mediastore.mediaUri
import com.ugallery.core.model.MediaKind
import com.ugallery.core.model.TimelineMedia
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
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

class PrivateAlbumRepository private constructor(
    private val context: Context,
    private val fixedDatabase: PrivateAlbumDatabase?,
    private val session: PrivateIndexSession?,
    private val ioDispatcher: CoroutineDispatcher,
) {
    constructor(context: Context, database: PrivateAlbumDatabase, ioDispatcher: CoroutineDispatcher = Dispatchers.IO) :
        this(context, database, null, ioDispatcher)
    internal constructor(context: Context, session: PrivateIndexSession) : this(context, null, session, Dispatchers.IO)
    private val database get() = requireNotNull(fixedDatabase)
    private val viewerCleanup = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val fixedViewerLease = RevocablePrivateResource<AutoCloseable>(viewerCleanup, initiallyReady = true)
    val accessState: kotlinx.coroutines.flow.StateFlow<PrivateIndexAccessState> = session?.state
        ?: fixedViewerLease.state
    val isSessionBacked: Boolean get() = session != null
    suspend fun openSession() { if (session != null) session.open() else fixedViewerLease.open { AutoCloseable {} } }
    fun revokeSession() { if (session != null) session.revoke() else fixedViewerLease.revoke() }
    fun disposeSession() { if (session != null) session.dispose() else fixedViewerLease.dispose() }
    suspend fun awaitSessionClosed() { session?.closeRevoked() }
    private fun worker(db: PrivateAlbumDatabase) = PrivateAlbumRepository(context, db, ioDispatcher).also {
        it.importDirectorySync = importDirectorySync
        it.privateExportCheckpoint = privateExportCheckpoint
    }
    companion object {
        internal fun sessionBacked(context: Context, databaseName: String): PrivateAlbumRepository =
            PrivateAlbumRepository(context, null, PrivateIndexSession(context, databaseName), Dispatchers.IO)
        fun sessionBacked(context: Context): PrivateAlbumRepository =
            PrivateAlbumRepository(context.applicationContext, null, PrivateIndexSession(context.applicationContext), Dispatchers.IO)
    }
    /** The actual pre-commit fsync operation; replaceable only by module-local fixtures. */
    internal var importDirectorySync: (File) -> Unit = PrivatePortableJournal::syncDirectory

    /** Observational/fault-injection callback after a durable export receipt transition. */
    internal var privateExportCheckpoint: (suspend (PrivateExportReceiptEntity) -> Unit)? = null
    private fun exportPublication() = PrivateExportPublication(context, database, privateExportCheckpoint)

    private val containerDir: File by lazy {
        File(context.filesDir, "private-album").apply { mkdirs() }
    }

    fun keyProtection(): PrivateMasterKeyProtection = session?.let { PrivateMasterKeyProtection(context, it, it.databaseName) } ?: PrivateMasterKeyProtection(context, database)

    fun portableTransfers(): PrivatePortableTransfer = session?.let { PrivatePortableTransfer(context, it) } ?: PrivatePortableTransfer(context, database)

    val allMedia: Flow<List<PrivateMediaEntity>> = session?.observe { it.privateMediaDao().getAll() } ?: database.privateMediaDao().getAll()

    suspend fun isSetup(): Boolean = session?.withDatabase { worker(it).isSetup() } ?: withContext(ioDispatcher) {
        database.metadataDao().get()?.isSetup == true
    }

    /** A key and the committed alias observed before its resolution; never recapture after work. */
    class MasterKey internal constructor(val alias: String, val secretKey: SecretKey, internal val sessionEpoch: Long? = null)

    suspend fun requireMasterKey(): SecretKey = requireMasterKeyBinding().secretKey

    /** Resolve and exercise only the alias committed with this vault; never create or fall back. */
    suspend fun requireMasterKeyBinding(): MasterKey {
        if (session != null) {
            val epoch = session.epoch
            return session.withDatabase(epoch) { worker(it).requireMasterKeyBinding().let { key -> MasterKey(key.alias, key.secretKey, epoch) } }
        }
        return withContext(ioDispatcher) { database.withTransaction {
        val metadata = database.metadataDao().get()
        check(metadata?.isSetup == true) { "Private album setup is incomplete" }
        val key = PrivateAlbumCrypto.requireExistingMasterKey(requireNotNull(metadata.keyAlias))
        val sample = database.privateMediaDao().getPage(1, 0).firstOrNull()
        if (sample != null) {
            PrivateAlbumCrypto.decryptDataKey(PrivateAlbumCrypto.EncryptedDataKey(sample.encryptedDataKey, sample.dataKeyIv), key)
        } else {
            // Key existence alone does not demonstrate that a Keystore operation is authorized.
            val probe = PrivateAlbumCrypto.generateDataKey()
            val wrapped = PrivateAlbumCrypto.encryptDataKey(probe, key)
            PrivateAlbumCrypto.decryptDataKey(wrapped, key)
        }
        MasterKey(metadata.keyAlias, key)
    } }
    }

    /** Explicit creation boundary. Populated or orphaned vaults never become a new empty setup. */
    suspend fun setupNewAlbum(keyAlias: String = PrivateAlbumCrypto.MASTER_KEY_ALIAS) = withContext(ioDispatcher) {
        database.withTransaction {
            check(database.metadataDao().get() == null && database.privateMediaDao().count() == 0) {
                "Existing private album must retain its master key"
            }
            val existing = containerDir.listFiles() ?: error("Private container directory is unavailable")
            check(existing.isEmpty()) { "Existing private containers require recovery" }
            val key = PrivateAlbumCrypto.getOrCreateMasterKey(keyAlias)
            setup(key, keyAlias)
        }
    }

    /** Explicit-key setup retained for fixture/import adapters; never an ordinary access path. */
    internal suspend fun setup(masterKey: SecretKey, keyAlias: String = PrivateAlbumCrypto.MASTER_KEY_ALIAS) = withContext(ioDispatcher) { database.withTransaction {
        check(database.metadataDao().get() == null && database.privateMediaDao().count() == 0) {
            "Existing private metadata must be preserved"
        }
        val storedKey = PrivateAlbumCrypto.requireExistingMasterKey(keyAlias)
        val probe = PrivateAlbumCrypto.generateDataKey()
        PrivateAlbumCrypto.decryptDataKey(PrivateAlbumCrypto.encryptDataKey(probe, masterKey), storedKey)
        database.metadataDao().upsert(
            PrivateAlbumMetadataEntity(
                id = 0,
                isSetup = true,
                keyAlias = keyAlias,
                createdAtMillis = System.currentTimeMillis(),
            )
        )
    } }

    suspend fun importFromUri(
        sourceUri: Uri, displayName: String, mimeType: String, mediaKind: String,
        width: Int = 0, height: Int = 0, durationMillis: Long = 0, masterKey: MasterKey,
    ): ImportResult = if (session != null) session.withDatabase(requireNotNull(masterKey.sessionEpoch), write = true) {
        worker(it).importFromUri(sourceUri, displayName, mimeType, mediaKind, width, height, durationMillis, masterKey)
    } else importFromUri(sourceUri, displayName, mimeType, mediaKind,
        width, height, durationMillis, masterKey.secretKey, masterKey.alias)

    /** Explicit-key fixture adapter; production writers use the bound-key overload. */
    internal suspend fun importFromUri(
        sourceUri: Uri,
        displayName: String,
        mimeType: String,
        mediaKind: String,
        width: Int = 0,
        height: Int = 0,
        durationMillis: Long = 0,
        masterKey: SecretKey,
        expectedKeyAlias: String? = null,
    ): ImportResult = withContext(ioDispatcher) {
        val job = currentCoroutineContext()
        var containerFile: File? = null
        var stagingFile: File? = null
        var escapingFailure: Throwable? = null
        var committed = false
        try {
            val capturedKeyAlias = expectedKeyAlias ?: database.metadataDao().get()?.keyAlias
            val resolver = context.contentResolver
            val dataKey = PrivateAlbumCrypto.generateDataKey()
            val encryptedDataKey = PrivateAlbumCrypto.encryptDataKey(dataKey, masterKey)

            // A random name: nothing about the original (not even a hash of its name) leaks into the listing.
            containerFile = File(containerDir, "private_${java.util.UUID.randomUUID()}.ugpc")
                .also { check(it.createNewFile()) { "Private container already exists" } }
            val input = resolver.openInputStream(sourceUri)
                ?: return@withContext ImportResult(false, error = "Cannot open source URI")

            input.use { inputStream ->
                // Read the provider exactly once, including unknown-size streams. The temporary
                // representation is encrypted; its measured count supplies the final v3 header.
                val staging = File(requireNotNull(containerFile).path + ".import-staging")
                    .also { check(it.createNewFile()) { "Private staging already exists" }; stagingFile = it }
                val staged = FileOutputStream(staging).use { encrypted ->
                    PrivateAlbumCrypto.encryptStream(inputStream, encrypted, dataKey, mimeType,
                        checkActive = { job.ensureActive() }).also { encrypted.fd.sync() }
                }
                job.ensureActive()
                FileOutputStream(requireNotNull(containerFile)).use { output ->
                    val metadata = FileInputStream(staging).use { encrypted ->
                        PrivateAlbumCrypto.reencryptSizedStream(encrypted, output, dataKey, dataKey,
                            mimeType, staged.sha256, staged.totalEncryptedSize) { job.ensureActive() }
                    }
                    output.fd.sync()
                    FileInputStream(requireNotNull(containerFile)).use { verified ->
                        PrivateAlbumCrypto.decryptStream(verified, object : java.io.OutputStream() {
                            override fun write(b: Int) = Unit
                            override fun write(b: ByteArray, off: Int, len: Int) = Unit
                        }, dataKey, metadata.sha256) { job.ensureActive() }
                    }

                    check(staging.delete()) { "Encrypted staging cleanup requires retry" }
                    stagingFile = null
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
                    job.ensureActive()
                    // fsync(file) does not make its directory entry durable. Persist the
                    // container name, then the parent entry if this is the first import,
                    // before a durable index row can reference it.
                    importDirectorySync(containerDir)
                    importDirectorySync(requireNotNull(containerDir.parentFile))
                    job.ensureActive()
                    withContext(NonCancellable) {
                        val id = database.withTransaction {
                            val current = database.metadataDao().get()
                            check(current?.keyAlias == capturedKeyAlias && (expectedKeyAlias == null || current?.isSetup == true)) {
                                "Private master key changed during import"
                            }
                            database.privateMediaDao().insert(entity)
                        }
                        committed = true
                        ImportResult(success = true, mediaId = id)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            escapingFailure = cancelled
            throw cancelled
        } catch (e: Exception) {
            if (PrivateAlbumCrypto.requiresAuthentication(e)) { escapingFailure = e; throw e }
            ImportResult(false, error = e.message ?: "Unknown error")
        } catch (failure: Throwable) {
            escapingFailure = failure
            throw failure
        } finally {
            val cleanup = cleanupPrivateImportFiles(listOfNotNull(stagingFile, containerFile.takeUnless { committed }))
            if (cleanup != null) {
                if (escapingFailure != null) escapingFailure.addSuppressed(cleanup)
                else return@withContext ImportResult(false, error = "Encrypted import cleanup requires retry")
            }
        }
    }

    suspend fun importFromMedia(media: TimelineMedia, masterKey: MasterKey): ImportResult =
        if (session != null) session.withDatabase(requireNotNull(masterKey.sessionEpoch), write = true) { worker(it).importFromMedia(media, masterKey) }
        else importFromMedia(media, masterKey.secretKey, masterKey.alias)

    internal suspend fun importFromMedia(
        media: TimelineMedia,
        masterKey: SecretKey,
        expectedKeyAlias: String? = null,
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
            expectedKeyAlias = expectedKeyAlias,
        )
    }

    suspend fun exportToMediaStore(mediaId: Long, masterKey: MasterKey): Uri? =
        if (session != null) session.withDatabase(requireNotNull(masterKey.sessionEpoch), write = true) {
            worker(it).exportToMediaStore(mediaId, masterKey.secretKey)
        } else exportToMediaStore(mediaId, masterKey.secretKey)

    /** Explicit-key adapter for fixed-DB fixtures; managed callers must carry a session binding. */
    suspend fun exportToMediaStore(mediaId: Long, masterKey: SecretKey): Uri? = withContext(ioDispatcher) {
        check(session == null) { "Session-backed private export requires a bound master key" }
        exportPublication().export(mediaId, masterKey)
    }

    suspend fun exportRecoveries(): List<PrivateExportRecovery> =
        session?.withDatabase { worker(it).exportRecoveries() }
            ?: withContext(ioDispatcher) { exportPublication().list() }

    suspend fun completeExport(id: String, expectedProof: String): Uri =
        session?.withDatabase(write = true) { worker(it).completeExport(id, expectedProof) }
            ?: withContext(ioDispatcher) { exportPublication().complete(id, expectedProof) }

    suspend fun discardExport(id: String, expectedProof: String): Unit =
        session?.withDatabase(write = true) { worker(it).discardExport(id, expectedProof) }
            ?: withContext(ioDispatcher) { exportPublication().discard(id, expectedProof) }

    suspend fun forgetExport(id: String, expectedProof: String): Unit =
        session?.withDatabase(write = true) { worker(it).forgetExport(id, expectedProof) }
            ?: withContext(ioDispatcher) { exportPublication().forget(id, expectedProof) }

    internal suspend fun openViewerSource(mediaId: Long): PrivateViewerSource {
        require(mediaId > 0)
        val expected = session?.epoch ?: fixedViewerLease.epoch
        val source = SessionPrivateViewerSource(viewerCleanup)
        try {
            return withContext(ioDispatcher) {
                suspend fun snapshot(db: PrivateAlbumDatabase): Pair<PrivateMediaEntity, SecretKey> = db.withTransaction {
                    val entity = requireNotNull(db.privateMediaDao().getById(mediaId)) { "Private source is missing" }
                    require(entity.mediaKind == "image" || entity.mediaKind == "video")
                    val master = worker(db).requireMasterKeyBinding()
                    val key = PrivateAlbumCrypto.decryptDataKey(
                        PrivateAlbumCrypto.EncryptedDataKey(entity.encryptedDataKey, entity.dataKeyIv), master.secretKey,
                    )
                    source.bind(session?.registerReader(expected, source::close)
                        ?: fixedViewerLease.registerReader(expected, source::close))
                    entity to key
                }
                // End the database lease before full authentication/decoding/playback I/O.
                val (entity, key) = session?.withDatabase(expected) { snapshot(it) } ?: snapshot(database)
                val context = currentCoroutineContext()
                val opening = java.util.concurrent.atomic.AtomicBoolean(true)
                val reader = com.ugallery.core.security.PrivateSeekableReader.open(
                    File(entity.containerPath), key, entity.sha256,
                ) { source.checkValid(); if (opening.get()) context.ensureActive() }
                try {
                    context.ensureActive()
                    source.attach(reader, PrivateViewerMetadata(entity.id, entity.originalDisplayName,
                        entity.originalMimeType, entity.mediaKind, entity.width, entity.height,
                        entity.durationMillis, reader.plaintextBytes))
                    opening.set(false)
                    source.checkValid()
                    source
                } catch (failure: Throwable) { reader.close(); throw failure }
            }
        } catch (failure: Throwable) { source.close(); throw failure }
    }

    suspend fun delete(mediaId: Long): Unit {
        if (session != null) return session.withDatabase(write = true) { session.invalidateReaders(); worker(it).delete(mediaId) }
        return withContext(ioDispatcher) {
        withContext(NonCancellable) {
            val containerFile = database.withTransaction {
                fixedViewerLease.invalidateReaders()
                database.privateMediaDao().getById(mediaId)?.let { entity ->
                    database.privateMediaDao().deleteById(mediaId)
                    File(entity.containerPath)
                }
            }
            if (containerFile != null)
                check(!containerFile.exists() || containerFile.delete()) { "Encrypted orphan cleanup requires retry" }
        }
    }

    }

    suspend fun deleteAll(): Unit {
        if (session != null) return session.withDatabase(write = true) { session.invalidateReaders(); worker(it).deleteAll() }
        return withContext(ioDispatcher) {
        withContext(NonCancellable) {
            database.withTransaction {
                fixedViewerLease.invalidateReaders()
                database.privateMediaDao().deleteAll()
                database.metadataDao().delete()
            }
            containerDir.listFiles()?.forEach { check(it.delete()) { "Encrypted orphan cleanup requires retry" } }
        }
    }

    }

    suspend fun count(): Int = session?.withDatabase { worker(it).count() } ?: withContext(ioDispatcher) {
        database.privateMediaDao().count()
    }

    suspend fun getContainerFile(mediaId: Long): File? {
        if (session != null) return session.withDatabase { worker(it).getContainerFile(mediaId) }
        // Lookup only within a current private session.
        return database.privateMediaDao().getById(mediaId)?.let { File(it.containerPath) }
    }

    suspend fun getDecryptedDataKey(mediaId: Long, masterKey: MasterKey): SecretKey? =
        if (session != null) session.withDatabase(requireNotNull(masterKey.sessionEpoch)) { worker(it).getDecryptedDataKey(mediaId, masterKey.secretKey) }
        else getDecryptedDataKey(mediaId, masterKey.secretKey)

    suspend fun getDecryptedDataKey(mediaId: Long, masterKey: SecretKey): SecretKey? = withContext(ioDispatcher) {
        check(session == null) { "Session-backed private keys require a current binding" }
        val entity = database.privateMediaDao().getById(mediaId) ?: return@withContext null
        val encryptedDataKey = PrivateAlbumCrypto.EncryptedDataKey(
            entity.encryptedDataKey,
            entity.dataKeyIv,
        )
        PrivateAlbumCrypto.decryptDataKey(encryptedDataKey, masterKey)
    }

    suspend fun getMetadata(mediaId: Long): PrivateMediaEntity? {
        if (session != null) return session.withDatabase { worker(it).getMetadata(mediaId) }
        return withContext(ioDispatcher) { database.privateMediaDao().getById(mediaId) }
    }
}

/** Only exact paths created by this import. Try every path and distinguish missing from failed unlink. */
internal fun cleanupPrivateImportFiles(files: List<File>): java.io.IOException? {
    var failure: java.io.IOException? = null
    files.forEach { file ->
        try { java.nio.file.Files.deleteIfExists(file.toPath()) }
        catch (error: Exception) {
            val detail = java.io.IOException("Encrypted import cleanup requires retry", error)
            if (failure == null) failure = detail else failure.addSuppressed(detail)
        }
    }
    return failure
}
