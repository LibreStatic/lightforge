package com.ugallery.feature.privatealbum

import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.AtomicFile
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.security.PrivateAlbumCrypto
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.SecretKeyFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * One real credential/expiry/migration case plus an explicit host recovery method, never a suite.
 */
class PrivateAuthenticatedIndexSessionDeviceTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val base
        get() = instrumentation.targetContext

    private val fixtureId: String
        get() =
            requireNotNull(InstrumentationRegistry.getArguments().getString("fixtureUuid")).also {
                require(UUID.fromString(it).toString() == it)
            }

    private fun aliases(id: String) =
        listOf("ugallery.privatealbum.fixture.$id", "ugallery.privatealbum.auth.v1.$id",
            "ugallery.privatealbum.index.v1." + MessageDigest.getInstance("SHA-256")
                .digest(databaseName(id).toByteArray()).joinToString("") { "%02x".format(it) })

    private fun databaseName(id: String) = "private-auth-index-$id.db"

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun manifest(id: String) = AtomicFile(File(base.cacheDir, "private-auth-$id.json"))

    private fun receipt(id: String) =
        AtomicFile(File(base.cacheDir, "private-auth-$id.cleaned.json"))

    private fun write(file: AtomicFile, json: JSONObject) {
        val output = file.startWrite()
        try {
            output.write(json.toString().toByteArray())
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }

    private fun marker(value: String) {
        instrumentation.sendStatus(
            2,
            Bundle().apply { putString("stream", "AUTH_FIXTURE $fixtureId $value\n") },
        )
    }

    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())

    private inner class Fixture : AutoCloseable {
        val id = fixtureId
        val legacyAlias = aliases(id)[0]
        val authAlias = aliases(id)[1]
        val root = File(base.cacheDir, "private-auth-files-$id")
        val context =
            object : ContextWrapper(base) {
                override fun getApplicationContext() = this
                override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
                override fun getDatabasePath(name: String) = File(root, "databases/$name").apply { parentFile!!.mkdirs() }
                override fun getFilesDir() = File(root, "files").apply { mkdirs() }

                override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            }
        val database: PrivateAlbumDatabase

        init {
            check(!root.exists())
            check(
                !manifest(id).baseFile.exists() &&
                    !File(manifest(id).baseFile.path + ".bak").exists()
            )
            check(!receipt(id).baseFile.exists())
            aliases(id).forEach { check(!keyStore().containsAlias(it)) }
            // Write-ahead ownership: both exact UUID aliases are recorded BEFORE any key creation.
            write(
                manifest(id),
                JSONObject()
                    .put("version", 1)
                    .put("uuid", id)
                    .put("legacyAlias", legacyAlias)
                    .put("authAlias", authAlias),
            )
            check(root.mkdir())
            database = PrivateAlbumDatabase.open(context, databaseName(id))
        }

        fun repository() = PrivateAlbumRepository(context, database)

        override fun close() {
            try {
                database.close()
            } finally {
                cleanupOwned(id)
            }
        }
    }

    private fun cleanupOwned(id: String) {
        val owner = manifest(id)
        val done = receipt(id)
        val hasOwner = owner.baseFile.exists() || File(owner.baseFile.path + ".bak").exists()
        val json =
            JSONObject(
                (if (hasOwner) owner else done).openRead().use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
            )
        require(json.getInt("version") == 1 && json.getString("uuid") == id)
        require(json.getString("legacyAlias") == aliases(id)[0])
        require(json.getString("authAlias") == aliases(id)[1])
        val store = keyStore()
        aliases(id).forEach { store.deleteEntry(it) }
        aliases(id).forEach { check(!store.containsAlias(it)) }
        val root = File(base.cacheDir, "private-auth-files-$id")
        require(root.canonicalFile.parentFile == base.cacheDir.canonicalFile)
        check(!root.exists() || root.deleteRecursively())
        write(done, json.put("aliasesAbsent", true))
        owner.delete()
        marker("CLEANUP_CONFIRMED")
    }

    /**
     * Host fallback after a crashed run; same fixtureUuid, same test UID, no prefix-wide deletion.
     */
    @Test
    fun cleanupUuidAliasesAfterInterruptedFixture() {
        cleanupOwned(fixtureId)
    }

    private suspend fun authenticate(
        scenario: ActivityScenario<PrivateKeyAuthenticationTestActivity>,
        phase: String,
    ): Long {
        lateinit var result: CompletableDeferred<Unit>
        scenario.onActivity { result = it.prepareAuthentication() }
        marker("AUTH_REQUIRED_$phase")
        // Host clicks ready Button and enters PIN only into Android Settings/SystemUI prompt.
        withTimeout(90_000) { result.await() }
        val authenticated = SystemClock.elapsedRealtime()
        marker("AUTH_SUCCEEDED_$phase")
        return authenticated
    }

    private suspend fun requireExpired(block: suspend () -> Unit) {
        try {
            block()
        } catch (expected: UserNotAuthenticatedException) {
            return
        }
        fail("A new cryptographic operation must require real authentication after expiry")
    }

    private fun assertNonWrappingFields(before: PrivateMediaEntity, after: PrivateMediaEntity) {
        assertEquals(before.id, after.id)
        assertEquals(before.originalMediaKey, after.originalMediaKey)
        assertEquals(before.originalMimeType, after.originalMimeType)
        assertEquals(before.originalDisplayName, after.originalDisplayName)
        assertEquals(before.containerPath, after.containerPath)
        assertEquals(before.containerSizeBytes, after.containerSizeBytes)
        assertEquals(before.chunkSize, after.chunkSize)
        assertEquals(before.totalChunks, after.totalChunks)
        assertArrayEquals(before.ivBase, after.ivBase)
        assertArrayEquals(before.sha256, after.sha256)
        assertEquals(before.addedAtMillis, after.addedAtMillis)
        assertEquals(before.mediaKind, after.mediaKind)
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        assertEquals(before.durationMillis, after.durationMillis)
    }

    @Test
    fun authenticatedIndexClosesReopensAndRetainsPreparedCiphertext(): Unit = runBlocking {
        Fixture().use { fixture ->
            check(base.getSystemService(android.app.KeyguardManager::class.java).isDeviceSecure)
            val seed = fixture.repository()
            seed.setupNewAlbum(fixture.legacyAlias)
            val source = File(fixture.context.cacheDir, "source.jpg").apply {
                val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(android.graphics.Color.BLUE)
                    outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it)) }
                } finally { bitmap.recycle() }
            }
            val originalHash = sha(source)
            val result = seed.importFromUri(Uri.fromFile(source), "source.jpg", "image/jpeg", "image", masterKey = seed.requireMasterKeyBinding())
            assertTrue(result.success)
            val id = requireNotNull(result.mediaId)
            val before = requireNotNull(seed.getMetadata(id))
            val containerHash = sha(File(before.containerPath))
            val index = PrivateIndexKey(fixture.context, databaseName(fixture.id))
            val passwordBefore = index.loadOrCreate(false).let { password ->
                try { MessageDigest.getInstance("SHA-256").digest(password) } finally { password.fill(0) }
            }
            fixture.database.close()
            val session = PrivateIndexSession(fixture.context, databaseName(fixture.id))
            val repository = PrivateAlbumRepository(fixture.context, session)
            val transfer = repository.portableTransfers()
            try {
                assertEquals(PrivateIndexAccessState.Locked, repository.accessState.value)
                assertTrue(runCatching { repository.count() }.exceptionOrNull() is PrivateIndexLockedException)
                repository.openSession() // Legacy index: explicit test setup, before its authenticated migration.
                val oldDatabase = session.withDatabase { it }
                repository.keyProtection().begin(fixture.authAlias)
                ActivityScenario.launch<PrivateKeyAuthenticationTestActivity>(
                    Intent(base, PrivateKeyAuthenticationTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ).use { scenario ->
                    authenticate(scenario, "MIGRATION")
                    repository.openSession()
                    assertFalse(oldDatabase.isOpen)
                    repository.keyProtection().advance()
                    assertEquals(PrivateKeyProtectionStatus.Protected, repository.keyProtection().status())
                    assertTrue(index.isProtected(fixture.authAlias))
                    assertFalse(keyStore().containsAlias(fixture.legacyAlias))
                    assertFalse(keyStore().containsAlias(index.alias))
                    val passwordAfter = index.loadOrCreate(false)
                    try { assertArrayEquals(passwordBefore, MessageDigest.getInstance("SHA-256").digest(passwordAfter)) }
                    finally { passwordAfter.fill(0) }
                    val after = requireNotNull(repository.getMetadata(id))
                    assertNonWrappingFields(before, after)
                    assertArrayEquals(containerHash, sha(File(after.containerPath)))
                    val oldBinding = repository.requireMasterKeyBinding()
                    val export = transfer.prepareExport(oldBinding, "index fixture passphrase".toCharArray())
                    val exportHash = sha(export.file)
                    val protectedDatabase = session.withDatabase { it }
                    repository.revokeSession()
                    assertEquals(PrivateIndexAccessState.Locked, repository.accessState.value)
                    repository.awaitSessionClosed()
                    assertFalse(protectedDatabase.isOpen)
                    assertTrue(runCatching { repository.count() }.exceptionOrNull() is PrivateIndexLockedException)
                    assertTrue(export.file.exists()); assertArrayEquals(exportHash, sha(export.file))
                    marker("WAIT_INDEX_EXPIRY")
                    delay(32_000)
                    try { repository.openSession(); fail("Expired authenticated index opened") }
                    catch (expected: Exception) { assertTrue(PrivateAlbumCrypto.requiresAuthentication(expected)) }
                    assertEquals(PrivateIndexAccessState.Locked, repository.accessState.value)
                    assertTrue(index.file.exists())
                    assertArrayEquals(containerHash, sha(File(before.containerPath)))
                    authenticate(scenario, "REOPEN")
                    repository.openSession()
                    assertEquals(1, repository.count())
                    assertTrue(runCatching { transfer.prepareExport(oldBinding, "old binding must fail".toCharArray()) }.exceptionOrNull() is PrivateIndexLockedException)
                    val fresh = repository.requireMasterKeyBinding()
                    val current = requireNotNull(repository.getMetadata(id))
                    val key = PrivateAlbumCrypto.decryptDataKey(PrivateAlbumCrypto.EncryptedDataKey(current.encryptedDataKey, current.dataKeyIv), fresh.secretKey)
                    val decoded = ByteArrayOutputStream()
                    File(current.containerPath).inputStream().use { PrivateAlbumCrypto.decryptStream(it, decoded, key, current.sha256) }
                    assertArrayEquals(source.readBytes(), decoded.toByteArray())
                    assertArrayEquals(exportHash, sha(export.file))
                    transfer.discard(export) // The original shared journal remains owned after reopen.
                    assertFalse(export.file.exists())
                    assertArrayEquals(originalHash, sha(source))
                    marker("INDEX_SESSION_PASS")
                }
            } finally {
                repository.disposeSession()
                repository.awaitSessionClosed()
                transfer.clearOwnedStaging()
            }
        }
    }
}
