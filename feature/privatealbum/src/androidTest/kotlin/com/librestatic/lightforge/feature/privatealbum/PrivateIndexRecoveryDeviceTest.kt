package com.librestatic.lightforge.feature.privatealbum

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PrivateIndexRecoveryDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private class Interrupted : RuntimeException()
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    private fun cleanup(name: String) {
        context.deleteDatabase(name)
        File(context.getDatabasePath(name).path + ".encrypting").delete()
        val key = PrivateIndexKey(context, name)
        key.file.delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(key.alias) }
        File(context.noBackupFilesDir, "$name.index-migration.lock").delete()
        File(context.noBackupFilesDir, "$name.index-creating").delete()
    }
    private suspend fun createPlain(name: String) {
        val db = Room.databaseBuilder(context, PrivateAlbumDatabase::class.java, name).build()
        try { db.metadataDao().upsert(PrivateAlbumMetadataEntity(isSetup = true, keyAlias = "preserved-alias", createdAtMillis = 8123)) }
        finally { db.close() }
    }
    private suspend fun verify(name: String) {
        val db = PrivateAlbumDatabase.open(context, name)
        try {
            assertEquals("preserved-alias", db.metadataDao().get()!!.keyAlias)
            assertEquals(8123L, db.metadataDao().get()!!.createdAtMillis)
        } finally { db.close() }
    }

    @Test fun everyPublicationBoundaryRecoversWithoutAnEmptyIndex(): Unit = runBlocking {
        for (stage in PrivateIndexStorage.Stage.entries) {
            val name = "private-index-interrupted-${UUID.randomUUID()}.db"
            try {
                createPlain(name)
                try {
                    PrivateIndexStorage(context, name) { if (it == stage) throw Interrupted() }.prepare().fill(0)
                    fail("Expected injected interruption at $stage")
                } catch (_: Interrupted) { }
                assertEquals(stage != PrivateIndexStorage.Stage.Published,
                    PrivateIndexStorage.isPlaintext(context.getDatabasePath(name)))
                verify(name)
                assertFalse(PrivateIndexStorage.isPlaintext(context.getDatabasePath(name)))
                assertFalse(File(context.getDatabasePath(name).path + ".encrypting").exists())
                // A later write must never be replaced by an old pre-conversion snapshot.
                val db = PrivateAlbumDatabase.open(context, name)
                try { db.metadataDao().upsert(PrivateAlbumMetadataEntity(isSetup = true, keyAlias = "new-authoritative-write")) }
                finally { db.close() }
                val reopened = PrivateAlbumDatabase.open(context, name)
                try { assertEquals("new-authoritative-write", reopened.metadataDao().get()!!.keyAlias) }
                finally { reopened.close() }
            } finally { cleanup(name) }
        }
    }

    @Test fun missingOrAlteredWrappedKeyNeverRegeneratesAnEncryptedIndex(): Unit = runBlocking {
        val name = "private-index-key-${UUID.randomUUID()}.db"
        try {
            createPlain(name); verify(name)
            val key = PrivateIndexKey(context, name)
            val record = key.file.readBytes()
            val database = context.getDatabasePath(name)
            val original = hash(database)
            for (missing in listOf(true, false)) {
                if (missing) assertTrue(key.file.delete())
                else key.file.writeBytes(record.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
                val db = PrivateAlbumDatabase.open(context, name)
                try {
                    try { db.metadataDao().get(); fail("Lost or invalid key must fail closed") }
                    catch (_: Exception) { }
                } finally { db.close() }
                assertArrayEquals(original, hash(database))
                if (missing) assertFalse(key.file.exists())
                key.file.writeBytes(record)
                verify(name)
            }
        } finally { cleanup(name) }
    }

    @Test fun missingDeviceKeyDoesNotOverwriteWrappedKeyOrDatabase(): Unit = runBlocking {
        val name = "private-index-device-key-${UUID.randomUUID()}.db"
        try {
            createPlain(name); verify(name)
            val key = PrivateIndexKey(context, name)
            val record = key.file.readBytes()
            val original = hash(context.getDatabasePath(name))
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(key.alias) }
            val db = PrivateAlbumDatabase.open(context, name)
            try {
                try { db.metadataDao().get(); fail("Missing device key must not generate a replacement") }
                catch (_: Exception) { }
            } finally { db.close() }
            assertArrayEquals(record, key.file.readBytes())
            assertArrayEquals(original, hash(context.getDatabasePath(name)))
        } finally { cleanup(name) }
    }

    @Test fun corruptCiphertextIsRetainedInsteadOfDeletedByTheOpenHelper(): Unit = runBlocking {
        val name = "private-index-corrupt-${UUID.randomUUID()}.db"
        try {
            createPlain(name); verify(name)
            val database = context.getDatabasePath(name)
            database.writeBytes(database.readBytes().also { it[42] = (it[42].toInt() xor 1).toByte() })
            val corrupt = hash(database)
            repeat(2) {
                val db = PrivateAlbumDatabase.open(context, name)
                try {
                    try { db.metadataDao().get(); fail("Damaged index must not be replaced by an empty one") }
                    catch (_: Exception) { }
                } finally { db.close() }
                assertTrue(database.exists())
                assertArrayEquals(corrupt, hash(database))
            }
        } finally { cleanup(name) }
    }

    @Test fun missingMainWithRecoveryResiduesNeverCreatesAnEmptyDatabase(): Unit = runBlocking {
        for (suffix in listOf(".encrypting", "-wal", "-journal")) {
            val name = "private-index-missing-${UUID.randomUUID()}.db"
            val residue = File(context.getDatabasePath(name).path + suffix)
            try {
                residue.parentFile!!.mkdirs(); residue.writeBytes(byteArrayOf(1, 2, 3, 4))
                val db = PrivateAlbumDatabase.open(context, name)
                try {
                    try { db.metadataDao().get(); fail("Missing main with $suffix must retain recovery state") }
                    catch (_: Exception) { }
                } finally { db.close() }
                assertFalse(context.getDatabasePath(name).exists())
                assertArrayEquals(byteArrayOf(1, 2, 3, 4), residue.readBytes())
            } finally { residue.delete(); cleanup(name) }
        }
    }

    @Test fun interruptedFirstKeyPublicationResumesOnlyTheUninitializedIndex(): Unit = runBlocking {
        val name = "private-index-initialize-${UUID.randomUUID()}.db"
        try {
            try {
                PrivateIndexStorage(context, name) { if (it == PrivateIndexStorage.Stage.KeyDurable) throw Interrupted() }
                    .prepare().fill(0)
                fail("Expected initial key interruption")
            } catch (_: Interrupted) { }
            val db = PrivateAlbumDatabase.open(context, name)
            try { assertNull(db.metadataDao().get()) } finally { db.close() }
            assertFalse(PrivateIndexStorage.isPlaintext(context.getDatabasePath(name)))
            assertFalse(File(context.noBackupFilesDir, "$name.index-creating").exists())
            // Once initialized, a missing main must not be mistaken for a first install.
            assertTrue(context.deleteDatabase(name))
            val lost = PrivateAlbumDatabase.open(context, name)
            try {
                try { lost.metadataDao().get(); fail("An existing key without the initialized main must fail closed") }
                catch (_: Exception) { }
            } finally { lost.close() }
            assertFalse(context.getDatabasePath(name).exists())
        } finally { cleanup(name) }
    }
}
