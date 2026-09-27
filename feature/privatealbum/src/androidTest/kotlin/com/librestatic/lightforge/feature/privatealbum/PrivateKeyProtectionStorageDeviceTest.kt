package com.librestatic.lightforge.feature.privatealbum

import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Production encrypted opener and durable staging only; no master keys, device credential or auth. */
class PrivateKeyProtectionStorageDeviceTest {
    @Test fun stagedWrappersAndCancellationSurviveReopenAndPendingTracksCurrentBlobs(): Unit = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val uuid = UUID.randomUUID().toString()
        val root = File(base.cacheDir, "private-protection-storage-$uuid").apply { check(mkdir()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(File(root, "databases").apply { mkdirs() }, name)
        }
        val name = "protection-storage-$uuid.db"
        check(name != PrivateAlbumDatabase.DatabaseName)
        val indexAlias = PrivateIndexKey(context, name).alias
        var db = PrivateAlbumDatabase.open(context, name)
        val containers = (1..3).map { n -> File(context.filesDir, "owned-$n.ugpc").apply {
            writeBytes(ByteArray(67) { (it * 7 + n).toByte() })
        } }
        val originalBytes = containers.map { it.readBytes() }
        fun item(id: Long) = PrivateMediaEntity(
            id = id, originalMediaKey = "fixture:$uuid:$id", originalMimeType = "image/jpeg",
            originalDisplayName = "owned-$id.jpg", containerPath = containers[id.toInt() - 1].absolutePath,
            containerSizeBytes = 67, encryptedDataKey = ByteArray(48) { (it + id.toInt()).toByte() },
            dataKeyIv = ByteArray(12) { (it * 2 + id.toInt()).toByte() }, chunkSize = 1024,
            totalChunks = 1, ivBase = ByteArray(12) { 6 }, sha256 = ByteArray(32) { 9 },
            addedAtMillis = 5000 + id, mediaKind = "image", width = 9, height = 7, durationMillis = 0,
        )
        val first = item(1)
        val second = item(2)
        val third = item(3)
        val job = PrivateKeyProtectionJob(migrationId = UUID.randomUUID().toString(),
            sourceAlias = "lightforge.privatealbum.fixture.storage.$uuid.a",
            targetAlias = "lightforge.privatealbum.auth.v1.$uuid", initialized = true,
            committed = false, cancelled = false)
        val stage = PrivateKeyRewrapEntity(first.id, job.migrationId, first.encryptedDataKey,
            first.dataKeyIv, ByteArray(48) { (it + 40).toByte() }, ByteArray(12) { (it + 70).toByte() })
        fun snapshot(table: String): List<List<Any?>> =
            db.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY id").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add((0 until cursor.columnCount).map { column ->
                        when (cursor.getType(column)) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column)
                            Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).toList()
                            else -> cursor.getString(column)
                        }
                    })
                }
            }
        fun assertJob(expected: PrivateKeyProtectionJob, actual: PrivateKeyProtectionJob?) {
            val saved = requireNotNull(actual)
            assertEquals(expected.id, saved.id)
            assertEquals(expected.migrationId, saved.migrationId)
            assertEquals(expected.sourceAlias, saved.sourceAlias)
            assertEquals(expected.targetAlias, saved.targetAlias)
            assertEquals(expected.initialized, saved.initialized)
            assertEquals(expected.committed, saved.committed)
            assertEquals(expected.cancelled, saved.cancelled)
        }
        fun assertStage(expected: PrivateKeyRewrapEntity) {
            db.openHelper.readableDatabase.query("SELECT * FROM private_key_rewrap ORDER BY mediaId").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(expected.mediaId, cursor.getLong(cursor.getColumnIndexOrThrow("mediaId")))
                assertEquals(expected.migrationId, cursor.getString(cursor.getColumnIndexOrThrow("migrationId")))
                assertArrayEquals(expected.sourceKey, cursor.getBlob(cursor.getColumnIndexOrThrow("sourceKey")))
                assertArrayEquals(expected.sourceIv, cursor.getBlob(cursor.getColumnIndexOrThrow("sourceIv")))
                assertArrayEquals(expected.targetKey, cursor.getBlob(cursor.getColumnIndexOrThrow("targetKey")))
                assertArrayEquals(expected.targetIv, cursor.getBlob(cursor.getColumnIndexOrThrow("targetIv")))
                assertFalse(cursor.moveToNext())
            }
        }
        fun reopen() { db.close(); db = PrivateAlbumDatabase.open(context, name) }
        try {
            db.openHelper.writableDatabase.query("PRAGMA journal_mode").use {
                assertTrue(it.moveToFirst()); assertEquals("wal", it.getString(0).lowercase())
            }
            db.openHelper.writableDatabase.query("PRAGMA synchronous").use {
                assertTrue(it.moveToFirst()); assertTrue(it.getInt(0) >= 2)
            }
            val metadata = PrivateAlbumMetadataEntity(isSetup = true, keyAlias = job.sourceAlias, createdAtMillis = 1234)
            val historical = PrivateRestoreReceiptEntity(UUID.randomUUID().toString(), "c".repeat(64), 1, 4567)
            db.withTransaction {
                db.metadataDao().upsert(metadata)
                db.privateMediaDao().insert(first)
                db.privateMediaDao().insert(second)
                db.portableRestoreDao().insertReceipt(historical)
                db.keyProtectionDao().insertJob(job)
                db.keyProtectionDao().stage(stage)
            }
            val initialRows = snapshot("private_media")
            reopen()
            assertJob(job, db.keyProtectionDao().job())
            assertStage(stage)
            assertEquals(initialRows, snapshot("private_media"))
            assertEquals(listOf(2L), db.keyProtectionDao().pending(job.migrationId, 32).map { it.id })
            assertEquals(1, db.keyProtectionDao().preparedCount(job.migrationId))
            assertEquals(metadata, db.metadataDao().get())
            assertEquals(historical, db.portableRestoreDao().getReceipt(historical.id))

            val changed = first.copy(encryptedDataKey = first.encryptedDataKey.copyOf().apply { this[0] = 99 })
            db.privateMediaDao().insert(changed)
            assertEquals(listOf(1L, 2L), db.keyProtectionDao().pending(job.migrationId, 32).map { it.id })
            assertEquals(0, db.keyProtectionDao().preparedCount(job.migrationId))
            db.withTransaction {
                db.privateMediaDao().deleteById(second.id)
                db.privateMediaDao().insert(third)
            }
            assertEquals(listOf(1L, 3L), db.keyProtectionDao().pending(job.migrationId, 32).map { it.id })
            val refreshed = stage.copy(sourceKey = changed.encryptedDataKey,
                targetKey = ByteArray(48) { (it + 90).toByte() }, targetIv = ByteArray(12) { (it + 100).toByte() })
            db.keyProtectionDao().stage(refreshed)
            assertEquals(listOf(3L), db.keyProtectionDao().pending(job.migrationId, 32).map { it.id })
            assertEquals(1, db.keyProtectionDao().preparedCount(job.migrationId))
            db.withTransaction { assertEquals(1, db.keyProtectionDao().markCancelled(job.migrationId)) }
            val rowsBeforeReopen = snapshot("private_media")
            reopen()
            assertJob(job.copy(cancelled = true), db.keyProtectionDao().job())
            assertStage(refreshed)
            assertEquals(rowsBeforeReopen, snapshot("private_media"))
            assertEquals(listOf(3L), db.keyProtectionDao().pending(job.migrationId, 32).map { it.id })
            assertEquals(1, db.keyProtectionDao().preparedCount(job.migrationId))
            assertEquals(0, db.keyProtectionDao().markCancelled(job.migrationId))
            assertEquals(metadata, db.metadataDao().get())
            assertEquals(historical, db.portableRestoreDao().getReceipt(historical.id))
            // Finalize the durable cancellation through the production coordinator; the target
            // alias was never created in this storage fixture, so retirement is idempotent.
            PrivateAlbumRepository(context, db).keyProtection().cancel()
            PrivateAlbumRepository(context, db).keyProtection().cancel()
            assertNull(db.keyProtectionDao().job())
            assertEquals(0, db.keyProtectionDao().preparedCount(job.migrationId))
            assertEquals(rowsBeforeReopen, snapshot("private_media"))
            assertEquals(metadata, db.metadataDao().get())
            assertEquals(historical, db.portableRestoreDao().getReceipt(historical.id))
            containers.forEachIndexed { i, file -> assertArrayEquals(originalBytes[i], file.readBytes()) }
        } finally {
            try { db.close() }
            finally {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(indexAlias)
                root.deleteRecursively()
            }
        }
    }
}
