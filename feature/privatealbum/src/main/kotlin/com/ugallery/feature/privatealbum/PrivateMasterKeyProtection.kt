package com.ugallery.feature.privatealbum

import android.content.Context
import android.content.pm.PackageManager
import androidx.room.withTransaction
import com.ugallery.core.security.PrivateAlbumCrypto
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*

class PrivateDeviceCredentialRequiredException : IllegalStateException("A secure device screen lock is required")

enum class PrivateKeyProtectionStatus { Legacy, Pending, Protected }

/** Resumable encrypted-wrapper staging. No media plaintext or container files are rewritten. */
class PrivateMasterKeyProtection private constructor(
    private val context: Context,
    private val fixedDatabase: PrivateAlbumDatabase?,
    private val access: PrivateDatabaseAccess?,
    private val databaseName: String = PrivateAlbumDatabase.DatabaseName,
) {
    internal constructor(context: Context, database: PrivateAlbumDatabase) : this(context, database, null)
    internal constructor(context: Context, access: PrivateDatabaseAccess, databaseName: String = PrivateAlbumDatabase.DatabaseName) : this(context, null, access, databaseName)
    private val database get() = requireNotNull(fixedDatabase)
    private fun worker(db: PrivateAlbumDatabase) = PrivateMasterKeyProtection(context, db)

    private val dao get() = database.keyProtectionDao()

    suspend fun status(): PrivateKeyProtectionStatus {
        if (access != null) return access.withDatabase { db ->
            val media = worker(db).status()
            if (media != PrivateKeyProtectionStatus.Protected) media
            else if (PrivateIndexKey(context, databaseName).isProtected(requireNotNull(db.metadataDao().get()?.keyAlias))) media
            else PrivateKeyProtectionStatus.Pending
        }
        return withContext(Dispatchers.IO) {
        if (dao.job() != null) PrivateKeyProtectionStatus.Pending
        else {
            val alias = database.metadataDao().get()?.keyAlias
            if (alias != null && PrivateAlbumCrypto.isAuthenticationBound(alias)) PrivateKeyProtectionStatus.Protected
            else PrivateKeyProtectionStatus.Legacy
        }
    
        }
    }

    suspend fun begin(targetAlias: String = PrivateAlbumCrypto.AUTHENTICATED_MASTER_PREFIX + UUID.randomUUID()): Unit {
        if (access != null) return access.withDatabase(write = true) { db ->
            worker(db).begin(targetAlias)
        }
        return withContext(Dispatchers.IO) {
        if (!context.getSystemService(android.app.KeyguardManager::class.java).isDeviceSecure)
            throw PrivateDeviceCredentialRequiredException()
        dao.job()?.takeIf { it.cancelled }?.let { finishCancellation(it) }
        val job = database.withTransaction {
            dao.job()?.let { return@withTransaction it }
            val metadata = database.metadataDao().get()
            if (metadata != null) {
                check(metadata.isSetup) { "Incomplete private metadata must be preserved" }
                val source = requireNotNull(metadata.keyAlias)
                if (PrivateAlbumCrypto.isAuthenticationBound(source)) return@withTransaction null
            } else requireEmptyVault()
            require(targetAlias.startsWith(PrivateAlbumCrypto.AUTHENTICATED_MASTER_PREFIX) && targetAlias != metadata?.keyAlias)
            check(!java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(targetAlias))
            dao.clearStaging()
            PrivateKeyProtectionJob(migrationId = UUID.randomUUID().toString(),
                sourceAlias = metadata?.keyAlias, targetAlias = targetAlias).also { dao.insertJob(it) }
        } ?: return@withContext
        // Durable ownership precedes Keystore creation. INIT has never wrapped any media key.
        if (!job.initialized) database.withTransaction {
            check(dao.job() == job && !job.cancelled && !job.committed)
            val exists = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(job.targetAlias)
            if (!exists) PrivateAlbumCrypto.createAuthenticatedMasterKey(job.targetAlias,
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE))
            check(PrivateAlbumCrypto.isAuthenticationBound(job.targetAlias))
            check(dao.markInitialized(job.migrationId) == 1)
        }
    
        }
    }

    suspend fun advance(onProgress: (Int, Int) -> Unit = { _, _ -> }): Unit {
        if (access != null) return access.withDatabase(write = true) { db ->
            worker(db).advance(onProgress)
            val alias = requireNotNull(db.metadataDao().get()?.keyAlias)
            // Same password, explicit protection action, exclusive lease. Resumable after media commit.
            PrivateIndexStorage(context, databaseName).protectKey(alias)
        }
        return withContext(Dispatchers.IO) {
        val job = dao.job() ?: run {
            check(status() == PrivateKeyProtectionStatus.Protected) { "Explicit protection confirmation is required" }
            return@withContext
        }
        requireFullDurability()
        check(job.initialized) { "Private key initialization requires explicit continuation" }
        check(!job.cancelled) { "Private protection cancellation requires completion" }
        if (job.committed) { retire(job); return@withContext }
        val target = PrivateAlbumCrypto.requireExistingMasterKey(job.targetAlias)
        check(PrivateAlbumCrypto.isAuthenticationBound(job.targetAlias))
        val probe = PrivateAlbumCrypto.generateDataKey()
        val verification = PrivateAlbumCrypto.encryptDataKey(probe, target)
        PrivateAlbumCrypto.decryptDataKey(verification, target) // Real authorization, even for empty vaults.
        val source = job.sourceAlias?.let(PrivateAlbumCrypto::requireExistingMasterKey)
        while (true) {
            currentCoroutineContext().ensureActive()
            val pending = dao.pending(job.migrationId, 32)
            if (pending.isEmpty()) break
            check(source != null) { "New private setup acquired unexpected media" }
            for (row in pending) {
                currentCoroutineContext().ensureActive()
                val key = PrivateAlbumCrypto.decryptDataKey(
                    PrivateAlbumCrypto.EncryptedDataKey(row.encryptedDataKey, row.dataKeyIv), source)
                val wrapped = PrivateAlbumCrypto.encryptDataKey(key, target)
                val verified = PrivateAlbumCrypto.decryptDataKey(wrapped, target)
                val expected = key.encoded
                val actual = verified.encoded
                try { check(MessageDigest.isEqual(expected, actual)) { "Private rewrap verification failed" } }
                finally { expected.fill(0); actual.fill(0) }
                database.withTransaction {
                    requirePreparing(job)
                    val current = database.privateMediaDao().getById(row.id)
                    if (current != null && current.encryptedDataKey.contentEquals(row.encryptedDataKey) && current.dataKeyIv.contentEquals(row.dataKeyIv)) {
                        dao.stage(PrivateKeyRewrapEntity(row.id, job.migrationId, row.encryptedDataKey,
                            row.dataKeyIv, wrapped.encryptedKey, wrapped.iv))
                    }
                }
                onProgress(dao.preparedCount(job.migrationId), database.privateMediaDao().count())
            }
        }
        currentCoroutineContext().ensureActive()
        // Publication contains SQL only: a large album can stage over multiple auth windows.
        // A racing writer is either included in pending(), or makes this transaction retryable.
        withContext(NonCancellable) {
            database.withTransaction {
                requirePreparing(job)
                val count = database.privateMediaDao().count()
                check(dao.preparedCount(job.migrationId) == count && dao.pending(job.migrationId, 1).isEmpty()) {
                    "Private album changed; resume protection"
                }
                if (job.sourceAlias == null) requireEmptyVault()
                check(dao.publish(job.migrationId) == count)
                val metadata = database.metadataDao().get()
                    ?: PrivateAlbumMetadataEntity(isSetup = true, createdAtMillis = System.currentTimeMillis())
                database.metadataDao().upsert(metadata.copy(keyAlias = job.targetAlias))
                check(dao.markCommitted(job.migrationId) == 1)
            }
            retire(job.copy(committed = true))
        }
    
        }
    }

    /** Explicit cancellation only. A committed switch is finalized, never silently reversed. */
    suspend fun cancel(): Unit {
        if (access != null) return access.withDatabase(write = true) { db ->
            worker(db).cancel()
        }
        return withContext(Dispatchers.IO) {
        val job = dao.job() ?: return@withContext
        if (job.committed) { retire(job); return@withContext }
        requireFullDurability()
        if (!job.cancelled) database.withTransaction {
            requirePreparing(job)
            check(dao.markCancelled(job.migrationId) == 1)
        }
        finishCancellation(job.copy(cancelled = true))
    
        }
    }

    private suspend fun finishCancellation(job: PrivateKeyProtectionJob) {
        requireFullDurability()
        check(job.cancelled && !job.committed && dao.job() == job)
        check(database.metadataDao().get()?.keyAlias == job.sourceAlias)
        PrivateAlbumCrypto.retireMasterKey(job.targetAlias)
        database.withTransaction {
            check(dao.job() == job && database.metadataDao().get()?.keyAlias == job.sourceAlias)
            dao.clearStaging()
            check(dao.deleteJob(job.migrationId) == 1)
        }
    }

    private suspend fun requirePreparing(job: PrivateKeyProtectionJob) {
        check(dao.job() == job && !job.committed && !job.cancelled) { "Private protection request changed" }
        val metadata = database.metadataDao().get()
        if (job.sourceAlias == null) check(metadata == null)
        else check(metadata?.isSetup == true && metadata.keyAlias == job.sourceAlias)
    }

    private suspend fun requireEmptyVault() {
        check(database.metadataDao().get() == null && database.privateMediaDao().count() == 0)
        val directory = File(context.filesDir, "private-album").apply { check(isDirectory || mkdirs()) }
        check(requireNotNull(directory.listFiles()).isEmpty()) { "Existing private containers require recovery" }
    }

    private suspend fun requireFullDurability() = database.withTransaction {
        // Pin the primary writer: a pool's read-only connection may report a different PRAGMA.
        val db = database.openHelper.writableDatabase
        val sync = db.query("PRAGMA synchronous").use { check(it.moveToFirst()); it.getInt(0) }
        val mode = db.query("PRAGMA journal_mode").use { check(it.moveToFirst()); it.getString(0) }
        check(sync >= 3 || (sync >= 2 && mode.equals("wal", ignoreCase = true))) {
            "Private key retirement requires WAL/FULL or EXTRA durable commits (sync=$sync, mode=$mode)"
        }
    }

    private suspend fun retire(job: PrivateKeyProtectionJob) {
        requireFullDurability()
        check(job.committed && dao.job() == job)
        check(database.metadataDao().get()?.keyAlias == job.targetAlias)
        check(PrivateAlbumCrypto.isAuthenticationBound(job.targetAlias))
        val key = PrivateAlbumCrypto.requireExistingMasterKey(job.targetAlias)
        val probe = PrivateAlbumCrypto.generateDataKey()
        PrivateAlbumCrypto.decryptDataKey(PrivateAlbumCrypto.encryptDataKey(probe, key), key)
        job.sourceAlias?.let(PrivateAlbumCrypto::retireMasterKey)
        // Crash after deletion resumes here using only committed target metadata.
        database.withTransaction {
            check(dao.job() == job && database.metadataDao().get()?.keyAlias == job.targetAlias)
            dao.clearStaging()
            check(dao.deleteJob(job.migrationId) == 1)
        }
    }
}
