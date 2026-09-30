package com.librestatic.lightforge.feature.privatealbum

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production encrypted opener; never opens the user's DatabaseName. */
class PrivateEncryptedIndexDeviceTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private inner class Fixture : AutoCloseable {
        val name = "private-encrypted-index-${UUID.randomUUID()}.db"
        val databaseFile = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val directory =
            File(context.cacheDir, "private-index-fixture-${UUID.randomUUID()}").apply {
                check(mkdir())
            }
        val container =
            File(directory, "original.ugpc").apply {
                writeBytes(ByteArray(8193) { (it * 37).toByte() })
            }
        val containerBytes = container.readBytes()
        val sentinel = "sensitive-index-name-${UUID.randomUUID()}.jpg"

        fun open(): PrivateAlbumDatabase {
            check(name != PrivateAlbumDatabase.DatabaseName)
            return PrivateAlbumDatabase.open(context, name)
        }

        fun assertContainerUntouched() {
            assertTrue(container.isFile)
            assertArrayEquals(containerBytes, container.readBytes())
        }

        fun assertEncryptedBytes() {
            val bytes = databaseFile.readBytes()
            assertTrue("Encrypted database must be materialized", bytes.size > 16)
            assertFalse(
                bytes.copyOfRange(0, 16).contentEquals("SQLite format 3\u0000".toByteArray())
            )
            assertFalse(
                "Display name leaked in database",
                bytes.toString(Charsets.ISO_8859_1).contains(sentinel),
            )
            for (suffix in listOf("-wal", "-journal")) {
                val sidecar = File(databaseFile.path + suffix)
                if (sidecar.isFile) {
                    assertFalse(
                        "Display name leaked in $suffix",
                        sidecar.readBytes().toString(Charsets.ISO_8859_1).contains(sentinel),
                    )
                }
            }
            assertContainerUntouched()
        }

        override fun close() {
            // No global Keystore aliases or real private-container directories are touched.
            check(name != PrivateAlbumDatabase.DatabaseName)
            context.deleteDatabase(name)
            check(container.delete())
            check(directory.delete())
        }
    }

    private inline fun <T> PrivateAlbumDatabase.withDatabase(
        block: (PrivateAlbumDatabase) -> T
    ): T =
        try {
            block(this)
        } finally {
            close()
        }

    private fun item(fixture: Fixture, id: Long = 41) =
        PrivateMediaEntity(
            id = id,
            originalMediaKey = "content://fixture-only/${UUID.randomUUID()}",
            originalMimeType = "image/jpeg",
            originalDisplayName = fixture.sentinel,
            containerPath = fixture.container.absolutePath,
            containerSizeBytes = fixture.container.length(),
            encryptedDataKey = ByteArray(48) { (it * 3 + 7).toByte() },
            dataKeyIv = ByteArray(12) { (it + 11).toByte() },
            chunkSize = 1024,
            totalChunks = 9,
            ivBase = ByteArray(12) { (it + 23).toByte() },
            sha256 = ByteArray(32) { (it * 5 + 13).toByte() },
            addedAtMillis = 1_234_567_890_123,
            mediaKind = "image",
            width = 2048,
            height = 1536,
            durationMillis = 9876,
        )

    // Entity.equals only checks the id, so every persisted field is checked explicitly.
    private fun assertItem(expected: PrivateMediaEntity, actual: PrivateMediaEntity?) {
        val value = requireNotNull(actual)
        assertEquals(expected.id, value.id)
        assertEquals(expected.originalMediaKey, value.originalMediaKey)
        assertEquals(expected.originalMimeType, value.originalMimeType)
        assertEquals(expected.originalDisplayName, value.originalDisplayName)
        assertEquals(expected.containerPath, value.containerPath)
        assertEquals(expected.containerSizeBytes, value.containerSizeBytes)
        assertArrayEquals(expected.encryptedDataKey, value.encryptedDataKey)
        assertArrayEquals(expected.dataKeyIv, value.dataKeyIv)
        assertEquals(expected.chunkSize, value.chunkSize)
        assertEquals(expected.totalChunks, value.totalChunks)
        assertArrayEquals(expected.ivBase, value.ivBase)
        assertArrayEquals(expected.sha256, value.sha256)
        assertEquals(expected.addedAtMillis, value.addedAtMillis)
        assertEquals(expected.mediaKind, value.mediaKind)
        assertEquals(expected.width, value.width)
        assertEquals(expected.height, value.height)
        assertEquals(expected.durationMillis, value.durationMillis)
    }

    private fun assertMetadata(
        expected: PrivateAlbumMetadataEntity,
        actual: PrivateAlbumMetadataEntity?,
    ) {
        val value = requireNotNull(actual)
        assertEquals(expected.id, value.id)
        assertEquals(expected.isSetup, value.isSetup)
        assertEquals(expected.keyAlias, value.keyAlias)
        assertEquals(expected.createdAtMillis, value.createdAtMillis)
    }

    @Test
    fun freshProductionDatabaseIsEncryptedAndNewWritesSurviveReopen(): Unit = runBlocking {
        Fixture().use { fixture ->
            val expected = item(fixture)
            val metadata =
                PrivateAlbumMetadataEntity(
                    isSetup = true,
                    keyAlias = "fixture-alias",
                    createdAtMillis = 321,
                )
            fixture.open().withDatabase { db ->
                assertEquals(0, db.privateMediaDao().count())
                db.privateMediaDao().insert(expected)
                db.metadataDao().upsert(metadata)
                assertItem(expected, db.privateMediaDao().getById(expected.id))
            }
            fixture.assertEncryptedBytes()
            fixture.open().withDatabase { db ->
                assertItem(expected, db.privateMediaDao().getById(expected.id))
                assertMetadata(metadata, db.metadataDao().get())
                assertEquals(3, db.openHelper.readableDatabase.version)
            }
            fixture.assertEncryptedBytes()
        }
    }

    @Test
    fun plaintextVersionOnePreservesEveryFieldNullMetadataAndDeletedHighSequence(): Unit =
        runBlocking {
            Fixture().use { fixture ->
                val expected = item(fixture)
                val metadata =
                    PrivateAlbumMetadataEntity(
                        isSetup = false,
                        keyAlias = null,
                        createdAtMillis = null,
                    )
                SQLiteDatabase.openOrCreateDatabase(fixture.databaseFile, null).use { old ->
                    // Exact v1 DDL from PrivatePortableRestoreDaoDeviceTest.
                    old.execSQL(
                        "CREATE TABLE private_media (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, originalMediaKey TEXT NOT NULL, originalMimeType TEXT NOT NULL, originalDisplayName TEXT NOT NULL, containerPath TEXT NOT NULL, containerSizeBytes INTEGER NOT NULL, encryptedDataKey BLOB NOT NULL, dataKeyIv BLOB NOT NULL, chunkSize INTEGER NOT NULL, totalChunks INTEGER NOT NULL, ivBase BLOB NOT NULL, sha256 BLOB NOT NULL, addedAtMillis INTEGER NOT NULL, mediaKind TEXT NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL, durationMillis INTEGER NOT NULL)"
                    )
                    old.execSQL(
                        "CREATE TABLE private_album_metadata (id INTEGER NOT NULL PRIMARY KEY, isSetup INTEGER NOT NULL, keyAlias TEXT, createdAtMillis INTEGER)"
                    )
                    fun insert(value: PrivateMediaEntity) {
                        old.execSQL(
                            "INSERT INTO private_media VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            arrayOf<Any?>(
                                value.id,
                                value.originalMediaKey,
                                value.originalMimeType,
                                value.originalDisplayName,
                                value.containerPath,
                                value.containerSizeBytes,
                                value.encryptedDataKey,
                                value.dataKeyIv,
                                value.chunkSize,
                                value.totalChunks,
                                value.ivBase,
                                value.sha256,
                                value.addedAtMillis,
                                value.mediaKind,
                                value.width,
                                value.height,
                                value.durationMillis,
                            ),
                        )
                    }
                    insert(expected)
                    insert(expected.copy(id = 9001))
                    old.execSQL("DELETE FROM private_media WHERE id=9001")
                    old.execSQL("INSERT INTO private_album_metadata VALUES (0,0,NULL,NULL)")
                    old.version = 1
                }
                assertTrue(
                    fixture.databaseFile
                        .readBytes()
                        .toString(Charsets.ISO_8859_1)
                        .contains(fixture.sentinel)
                )
                var nextId = 0L
                fixture.open().withDatabase { db ->
                    assertItem(expected, db.privateMediaDao().getById(41))
                    assertMetadata(metadata, db.metadataDao().get())
                    assertEquals(1, db.privateMediaDao().count())
                    assertEquals(3, db.openHelper.readableDatabase.version)
                    assertNull(db.portableRestoreDao().getReceiptByArchiveSha("a".repeat(64)))
                    nextId = db.privateMediaDao().insert(expected.copy(id = 0))
                    assertTrue("AUTOINCREMENT must preserve deleted high ids", nextId > 9001)
                }
                fixture.assertEncryptedBytes()
                fixture.open().withDatabase { db ->
                    assertEquals(2, db.privateMediaDao().count())
                    assertItem(expected, db.privateMediaDao().getById(41))
                    assertItem(expected.copy(id = nextId), db.privateMediaDao().getById(nextId))
                    assertMetadata(metadata, db.metadataDao().get())
                }
                fixture.assertEncryptedBytes()
            }
        }

    @Test
    fun plaintextVersionTwoPreservesReceiptsMetadataAndHistoricalIdempotence(): Unit = runBlocking {
        Fixture().use { fixture ->
            val expected = item(fixture)
            val metadata =
                PrivateAlbumMetadataEntity(
                    isSetup = true,
                    keyAlias = "existing-wrapped-key-alias",
                    createdAtMillis = 456789,
                )
            val receipt =
                PrivateRestoreReceiptEntity(UUID.randomUUID().toString(), "b".repeat(64), 2, 987654)
            Room.databaseBuilder(context, PrivateAlbumDatabase::class.java, fixture.name)
                .build()
                .withDatabase { old ->
                    old.privateMediaDao().insert(expected)
                    old.privateMediaDao().insert(expected.copy(id = 9001))
                    old.privateMediaDao().deleteById(9001)
                    old.metadataDao().upsert(metadata)
                    old.portableRestoreDao().insertReceipt(receipt)
                }
            // The current Room class is v3. Reconstruct the exact historical v2 input rather
            // than accidentally testing plaintext v3 (which the production converter rejects).
            android.database.sqlite.SQLiteDatabase.openDatabase(fixture.databaseFile.path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { legacy ->
                legacy.execSQL("DROP TABLE private_key_rewrap")
                legacy.execSQL("DROP TABLE private_key_protection")
                legacy.execSQL("DROP TABLE room_master_table")
                legacy.version = 2
            }
            assertTrue(
                fixture.databaseFile
                    .readBytes()
                    .toString(Charsets.ISO_8859_1)
                    .contains(fixture.sentinel)
            )
            var nextId = 0L
            fixture.open().withDatabase { db ->
                assertItem(expected, db.privateMediaDao().getById(41))
                assertMetadata(metadata, db.metadataDao().get())
                val actual = requireNotNull(db.portableRestoreDao().getReceipt(receipt.id))
                assertEquals(receipt.id, actual.id)
                assertEquals(receipt.archiveSha256, actual.archiveSha256)
                assertEquals(receipt.itemCount, actual.itemCount)
                assertEquals(receipt.committedAtMillis, actual.committedAtMillis)
                assertEquals(
                    actual,
                    db.portableRestoreDao().getReceiptByArchiveSha(receipt.archiveSha256),
                )
                // Receipt history survives even when fewer/no original restored rows remain.
                try {
                    db.portableRestoreDao()
                        .commitPortableRestore(
                            receipt.copy(id = UUID.randomUUID().toString(), itemCount = 1),
                            listOf(expected.copy(id = 0)),
                        )
                    fail(
                        "Historical archive must not import duplicate copies after encryption migration"
                    )
                } catch (known: PrivateRestoreAlreadyCommitted) {
                    assertEquals(receipt.id, known.receipt.id)
                }
                assertEquals(1, db.privateMediaDao().count())
                nextId = db.privateMediaDao().insert(expected.copy(id = 0))
                assertTrue(nextId > 9001)
            }
            fixture.assertEncryptedBytes()
            fixture.open().withDatabase { db ->
                assertEquals(2, db.privateMediaDao().count())
                assertItem(expected, db.privateMediaDao().getById(41))
                assertItem(expected.copy(id = nextId), db.privateMediaDao().getById(nextId))
                assertMetadata(metadata, db.metadataDao().get())
                assertEquals(receipt, db.portableRestoreDao().getReceipt(receipt.id))
            }
            fixture.assertEncryptedBytes()
        }
    }
}
