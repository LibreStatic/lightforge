package com.ugallery.feature.privatealbum

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PrivatePortableRestoreDaoDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private inline fun <T> PrivateAlbumDatabase.useDb(block: (PrivateAlbumDatabase) -> T): T =
        try { block(this) } finally { close() }
    private fun item(path: String, name: String = "private.jpg") = PrivateMediaEntity(
        originalMediaKey = "portable:fixture", originalMimeType = "image/jpeg",
        originalDisplayName = name, containerPath = path, containerSizeBytes = 128,
        encryptedDataKey = ByteArray(48) { 7 }, dataKeyIv = ByteArray(12) { 8 },
        chunkSize = 1024, totalChunks = 1, ivBase = ByteArray(12) { 9 },
        sha256 = ByteArray(32) { 10 }, addedAtMillis = 1, mediaKind = "image",
    )
    private fun receipt(hash: String, count: Int = 1) = PrivateRestoreReceiptEntity(
        UUID.randomUUID().toString(), hash.repeat(64), count, 1234,
    )

    @Test fun versionOneMigrationPreservesWrappedKeysAndPrivateMetadata(): Unit = runBlocking {
        val name = "private-migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
                // Exact v1 tables captured from the pre-change Room-generated implementation.
                old.execSQL("CREATE TABLE private_media (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, originalMediaKey TEXT NOT NULL, originalMimeType TEXT NOT NULL, originalDisplayName TEXT NOT NULL, containerPath TEXT NOT NULL, containerSizeBytes INTEGER NOT NULL, encryptedDataKey BLOB NOT NULL, dataKeyIv BLOB NOT NULL, chunkSize INTEGER NOT NULL, totalChunks INTEGER NOT NULL, ivBase BLOB NOT NULL, sha256 BLOB NOT NULL, addedAtMillis INTEGER NOT NULL, mediaKind TEXT NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL, durationMillis INTEGER NOT NULL)")
                old.execSQL("CREATE TABLE private_album_metadata (id INTEGER NOT NULL PRIMARY KEY, isSetup INTEGER NOT NULL, keyAlias TEXT, createdAtMillis INTEGER)")
                old.execSQL("INSERT INTO private_media VALUES (41,'old-source','image/jpeg','old.jpg','old-container',128,?, ?,1024,1,?, ?,1,'image',32,24,0)", arrayOf(ByteArray(48) { 7 }, ByteArray(12) { 8 }, ByteArray(12) { 9 }, ByteArray(32) { 10 }))
                old.execSQL("INSERT INTO private_album_metadata VALUES (0,1,'old-keystore-alias',123)")
                old.version = 1
            }
            Room.databaseBuilder(context, PrivateAlbumDatabase::class.java, name)
                .addMigrations(PrivateAlbumDatabase.Migration1To2, PrivateAlbumDatabase.Migration2To3, PrivateAlbumDatabase.Migration3To4).build().useDb { db ->
                    val old = requireNotNull(db.privateMediaDao().getById(41))
                    assertEquals("old-container", old.containerPath)
                    assertArrayEquals(ByteArray(48) { 7 }, old.encryptedDataKey)
                    assertArrayEquals(ByteArray(32) { 10 }, old.sha256)
                    assertEquals("old-keystore-alias", db.metadataDao().get()!!.keyAlias)
                    assertTrue(db.metadataDao().get()!!.isSetup)
                    val receipt = receipt("a")
                    val inserted = db.portableRestoreDao().commitPortableRestore(receipt, listOf(item("new-container")))
                    assertEquals(1, inserted.size)
                    assertTrue(inserted.single() > 41)
                    assertEquals(receipt, db.portableRestoreDao().getReceiptByArchiveSha(receipt.archiveSha256))
                    try {
                        db.portableRestoreDao().commitPortableRestore(receipt.copy(id = UUID.randomUUID().toString()), listOf(item("duplicate")))
                        fail("An already committed archive must not create another private copy")
                    } catch (known: PrivateRestoreAlreadyCommitted) { assertEquals(receipt, known.receipt) }
                    assertEquals(2, db.privateMediaDao().count())
                    assertEquals("old-container", db.privateMediaDao().getById(41)!!.containerPath)
                }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun failedSecondInsertRollsBackFirstItemAndReceipt(): Unit = runBlocking {
        Room.inMemoryDatabaseBuilder(context, PrivateAlbumDatabase::class.java).build().useDb { db ->
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_fixture BEFORE INSERT ON private_media WHEN NEW.originalDisplayName='reject' BEGIN SELECT RAISE(ABORT,'fixture'); END")
            val receipt = receipt("b", 2)
            try {
                db.portableRestoreDao().commitPortableRestore(receipt, listOf(item("first"), item("second", "reject")))
                fail("Second insert must reject this transaction")
            } catch (_: android.database.SQLException) { }
            assertEquals(0, db.privateMediaDao().count())
            assertNull(db.portableRestoreDao().getReceipt(receipt.id))
            assertNull(db.portableRestoreDao().getReceiptByArchiveSha(receipt.archiveSha256))
        }
    }
}
