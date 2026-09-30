package com.librestatic.lightforge.feature.privatealbum

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Host runs seed, kills the isolated instrumentation package, then runs recovery with the same name. */
class PrivateIndexWalProcessDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name: String get() {
        val supplied = InstrumentationRegistry.getArguments().getString("indexFixture")
        org.junit.Assume.assumeTrue("This acceptance fixture requires the host process-death driver", supplied != null)
        return requireNotNull(supplied).also {
            require(it.matches(Regex("private-index-wal-[0-9a-f-]{36}\\.db")))
        }
    }

    @Test fun seedCommittedAndRolledBackWal(): Unit = runBlocking {
        val file = context.getDatabasePath(name)
        check(!file.exists()) { "WAL fixture must be new" }
        val schema = Room.databaseBuilder(context, PrivateAlbumDatabase::class.java, name).build()
        try { schema.privateMediaDao().count() } finally { schema.close() }
        held = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).also { db ->
            assertTrue(db.enableWriteAheadLogging())
            db.rawQuery("PRAGMA wal_autocheckpoint=0", null).close()
            db.beginTransaction()
            try {
                db.execSQL("INSERT INTO private_album_metadata VALUES (0,1,'committed-in-wal',31415)")
                db.execSQL("INSERT INTO private_restore_receipts VALUES ('11111111-1111-4111-8111-111111111111',?,1,2718)", arrayOf("c".repeat(64)))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            db.beginTransaction()
            try {
                db.execSQL("INSERT INTO private_restore_receipts VALUES ('22222222-2222-4222-8222-222222222222',?,1,9999)", arrayOf("d".repeat(64)))
            } finally { db.endTransaction() }
            assertTrue(File(file.path + "-wal").length() > 32)
            assertTrue(PrivateIndexStorage.isPlaintext(file))
            // Kept strongly reachable and deliberately not closed: host process death is the cut.
        }
        waitForHostDeath("seed")
    }

    @Test fun pauseAfterEncryptedIndexValidation() {
        check(File(context.getDatabasePath(name).path + "-wal").length() > 32)
        PrivateIndexStorage(context, name) {
            if (it == PrivateIndexStorage.Stage.Validated) waitForHostDeath("validated")
        }.prepare().fill(0)
        fail("Host must terminate this process before publication")
    }

    @Test fun recoverCommittedWalAfterHostProcessDeath(): Unit = runBlocking {
        val file = context.getDatabasePath(name)
        check(File(file.path + ".encrypting").length() > 32) { "No surviving encrypted stage; process-death precondition failed" }
        assertTrue(PrivateIndexStorage.isPlaintext(file))
        val db = PrivateAlbumDatabase.open(context, name)
        try {
            assertEquals("committed-in-wal", db.metadataDao().get()!!.keyAlias)
            assertEquals(31415L, db.metadataDao().get()!!.createdAtMillis)
            assertEquals(2718L, db.portableRestoreDao().getReceiptByArchiveSha("c".repeat(64))!!.committedAtMillis)
            assertNull(db.portableRestoreDao().getReceiptByArchiveSha("d".repeat(64)))
            assertFalse(PrivateIndexStorage.isPlaintext(file))
            File(context.noBackupFilesDir, "$name.recovered").writeText(android.os.Process.myPid().toString())
        } finally {
            db.close()
            context.deleteDatabase(name)
            val key = PrivateIndexKey(context, name)
            key.file.delete()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(key.alias) }
            File(context.noBackupFilesDir, "$name.index-migration.lock").delete()
        }
    }

    private fun waitForHostDeath(phase: String): Nothing {
        val ready = File(context.noBackupFilesDir, "$name.$phase.ready")
        java.io.FileOutputStream(ready).use {
            it.write(android.os.Process.myPid().toString().toByteArray()); it.fd.sync()
        }
        val kill = File(context.noBackupFilesDir, "$name.$phase.kill")
        repeat(1200) {
            // Host requests a real SIGKILL from inside the instrumentation UID. This avoids
            // Android 11 run-as/toolbox PID lookup differences, without an exception/finally unwind.
            if (kill.exists()) android.os.Process.killProcess(android.os.Process.myPid())
            Thread.sleep(100)
        }
        error("Host did not terminate the prepared WAL fixture")
    }

    companion object { private var held: SQLiteDatabase? = null }
}
