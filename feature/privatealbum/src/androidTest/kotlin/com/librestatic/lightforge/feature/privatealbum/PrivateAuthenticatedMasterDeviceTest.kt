package com.librestatic.lightforge.feature.privatealbum

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
import com.librestatic.lightforge.core.security.PrivateAlbumCrypto
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
class PrivateAuthenticatedMasterDeviceTest {
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
        listOf("lightforge.privatealbum.fixture.$id", "lightforge.privatealbum.auth.v1.$id")

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
            database =
                Room.inMemoryDatabaseBuilder(context, PrivateAlbumDatabase::class.java)
                    .addCallback(
                        object : androidx.room.RoomDatabase.Callback() {
                            override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                                db.execSQL("PRAGMA synchronous=EXTRA")
                            }
                        }
                    )
                    .build()
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
    fun realCredentialProtectsResumableTwoRowMigrationAndExpiresAfterThirtySeconds(): Unit =
        runBlocking {
            Fixture().use { fixture ->
                check(base.getSystemService(android.app.KeyguardManager::class.java).isDeviceSecure)
                val repository = fixture.repository()
                repository.setupNewAlbum(fixture.legacyAlias)
                val legacy = repository.requireMasterKeyBinding()
                val originals =
                    (1..2).map { index ->
                        File(fixture.context.cacheDir, "owned-$index.jpg").apply {
                            val bitmap =
                                android.graphics.Bitmap.createBitmap(
                                    2,
                                    2,
                                    android.graphics.Bitmap.Config.ARGB_8888,
                                )
                            try {
                                bitmap.eraseColor(
                                    if (index == 1) android.graphics.Color.RED
                                    else android.graphics.Color.BLUE
                                )
                                outputStream().use {
                                    check(
                                        bitmap.compress(
                                            android.graphics.Bitmap.CompressFormat.JPEG,
                                            95,
                                            it,
                                        )
                                    )
                                }
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                val originalHashes = originals.map(::sha)
                val ids =
                    originals.map { file ->
                        val imported =
                            repository.importFromUri(
                                Uri.fromFile(file),
                                file.name,
                                "image/jpeg",
                                "image",
                                2,
                                2,
                                masterKey = legacy,
                            )
                        assertTrue(imported.success)
                        requireNotNull(imported.mediaId)
                    }
                val before =
                    ids.map { requireNotNull(fixture.database.privateMediaDao().getById(it)) }
                val metadata = requireNotNull(fixture.database.metadataDao().get())
                val ciphertextHashes = before.map { sha(File(it.containerPath)) }
                val protection = repository.keyProtection()
                assertEquals(PrivateKeyProtectionStatus.Legacy, protection.status())
                protection.begin(fixture.authAlias)
                val job = requireNotNull(fixture.database.keyProtectionDao().job())
                assertEquals(fixture.legacyAlias, job.sourceAlias)
                assertEquals(fixture.authAlias, job.targetAlias)
                assertFalse(job.committed)
                assertTrue(PrivateAlbumCrypto.isAuthenticationBound(fixture.authAlias))
                val target = PrivateAlbumCrypto.requireExistingMasterKey(fixture.authAlias)
                val info =
                    SecretKeyFactory.getInstance(target.algorithm, "AndroidKeyStore")
                        .getKeySpec(target, KeyInfo::class.java) as KeyInfo
                assertTrue(info.isUserAuthenticationRequired)
                assertEquals(30, info.userAuthenticationValidityDurationSeconds)
                assertEquals(
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                    info.userAuthenticationType,
                )
                marker("WAIT_INITIAL_EXPIRY")
                delay(32_000)
                requireExpired { protection.advance() }
                assertEquals(PrivateKeyProtectionStatus.Pending, protection.status())

                ActivityScenario.launch<PrivateKeyAuthenticationTestActivity>(
                        Intent(base, PrivateKeyAuthenticationTestActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    .use { scenario ->
                        authenticate(scenario, "MIGRATION")
                        val interrupt =
                            CancellationException("Fixture interruption after one staged row")
                        try {
                            protection.advance { completed, total ->
                                assertEquals(2, total)
                                if (completed == 1) throw interrupt
                            }
                            fail("Fixture must interrupt after the first staged row")
                        } catch (actual: CancellationException) {
                            assertSame(interrupt, actual)
                        }
                        assertEquals(PrivateKeyProtectionStatus.Pending, protection.status())
                        assertEquals(metadata, fixture.database.metadataDao().get())
                        before.forEach { old ->
                            val current =
                                requireNotNull(fixture.database.privateMediaDao().getById(old.id))
                            assertNonWrappingFields(old, current)
                            assertArrayEquals(old.encryptedDataKey, current.encryptedDataKey)
                            assertArrayEquals(old.dataKeyIv, current.dataKeyIv)
                        }
                        // New coordinator instance, same durable job; not a claim of process-death
                        // recovery.
                        val resumed = fixture.repository().keyProtection()
                        resumed.advance()
                        assertEquals(PrivateKeyProtectionStatus.Protected, resumed.status())
                        assertFalse(keyStore().containsAlias(fixture.legacyAlias))
                        assertTrue(keyStore().containsAlias(fixture.authAlias))
                        val updatedMetadata = requireNotNull(fixture.database.metadataDao().get())
                        assertEquals(metadata.copy(keyAlias = fixture.authAlias), updatedMetadata)
                        assertEquals(2, fixture.database.privateMediaDao().count())
                        before.forEachIndexed { index, old ->
                            val current =
                                requireNotNull(fixture.database.privateMediaDao().getById(old.id))
                            assertNonWrappingFields(old, current)
                            assertFalse(
                                old.encryptedDataKey.contentEquals(current.encryptedDataKey)
                            )
                            assertArrayEquals(
                                ciphertextHashes[index],
                                sha(File(current.containerPath)),
                            )
                        }
                        marker("WAIT_PROTECTED_EXPIRY")
                        val expiryStart = SystemClock.elapsedRealtime()
                        delay(32_000)
                        check(SystemClock.elapsedRealtime() - expiryStart >= 32_000)
                        requireExpired { fixture.repository().requireMasterKey() }
                        authenticate(scenario, "READBACK")
                        val authMaster = fixture.repository().requireMasterKey()
                        before.forEachIndexed { index, old ->
                            val current =
                                requireNotNull(fixture.database.privateMediaDao().getById(old.id))
                            val key =
                                PrivateAlbumCrypto.decryptDataKey(
                                    PrivateAlbumCrypto.EncryptedDataKey(
                                        current.encryptedDataKey,
                                        current.dataKeyIv,
                                    ),
                                    authMaster,
                                )
                            val plaintext = ByteArrayOutputStream()
                            File(current.containerPath).inputStream().use {
                                PrivateAlbumCrypto.decryptStream(it, plaintext, key, current.sha256)
                            }
                            assertArrayEquals(originals[index].readBytes(), plaintext.toByteArray())
                            assertArrayEquals(originalHashes[index], sha(originals[index]))
                            assertArrayEquals(
                                ciphertextHashes[index],
                                sha(File(current.containerPath)),
                            )
                        }
                        marker("MIGRATION_EXPIRY_READBACK_VERIFIED")
                    }
            }
        }
}
