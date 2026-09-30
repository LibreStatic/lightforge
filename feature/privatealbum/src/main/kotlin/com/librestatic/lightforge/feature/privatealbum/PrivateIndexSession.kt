package com.librestatic.lightforge.feature.privatealbum

import android.content.Context
import com.librestatic.lightforge.core.security.PrivateAlbumCrypto
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal interface PrivateDatabaseAccess {
    val epoch: Long
    suspend fun <T> withDatabase(expectedEpoch: Long? = null, write: Boolean = false, block: suspend (PrivateAlbumDatabase) -> T): T
    fun <T> observe(block: (PrivateAlbumDatabase) -> Flow<T>): Flow<T>
}

internal class PrivateIndexSession(private val context: Context, val databaseName: String = PrivateAlbumDatabase.DatabaseName) : PrivateDatabaseAccess {
    // Independent from composition scope: disposal still completes close and password zeroing.
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private class OpenIndex(val database: PrivateAlbumDatabase) : AutoCloseable { override fun close() = database.close() }
    private val lease = RevocablePrivateResource<OpenIndex>(cleanupScope, authenticationFailure = PrivateAlbumCrypto::requiresAuthentication)
    val state = lease.state
    override val epoch get() = lease.epoch
    suspend fun open() {
        val expected = lease.epoch
        withContext(Dispatchers.IO) { lease.open(expected) {
            val db = PrivateAlbumDatabase.open(context, databaseName)
            try {
                db.openHelper.writableDatabase
                val repository = PrivateAlbumRepository(context, db)
                if (repository.isSetup()) repository.requireMasterKey()
                OpenIndex(db)
            }
            catch (failure: Throwable) { db.close(); throw failure }
        }
    }
    }
    fun registerReader(expected: Long, invalidated: () -> Unit) = lease.registerReader(expected, invalidated)
    fun invalidateReaders() = lease.invalidateReaders()
    fun revoke() = lease.revoke()
    fun dispose() = lease.dispose()
    suspend fun closeRevoked() = lease.closeRevoked()
    override suspend fun <T> withDatabase(expectedEpoch: Long?, write: Boolean, block: suspend (PrivateAlbumDatabase) -> T): T {
        // Capture admission before dispatch: queued calls cannot adopt a later authentication.
        val expected = expectedEpoch ?: lease.epoch
        return withContext(Dispatchers.IO) { lease.use(expected, write) { block(it.database) } }
    }
    override fun <T> observe(block: (PrivateAlbumDatabase) -> Flow<T>): Flow<T> = lease.observe { block(it.database).first() }
}
