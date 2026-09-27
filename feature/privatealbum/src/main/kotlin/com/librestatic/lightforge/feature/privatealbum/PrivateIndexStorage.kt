package com.librestatic.lightforge.feature.privatealbum

import android.content.Context
import android.database.Cursor
import android.system.Os
import android.system.OsConstants
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Converts the existing index before Room or portable recovery can observe it.
 * Only one atomic replacement publishes the validated encrypted database. Before that rename,
 * plaintext is authoritative; after it, encrypted writes are authoritative, never an old snapshot.
 */
internal class PrivateIndexStorage(
    private val context: Context,
    private val name: String,
    private val checkpoint: (Stage) -> Unit = {},
) {
    internal enum class Stage { KeyDurable, Exported, Validated, Published }
    private val database = context.getDatabasePath(name)
    internal val staging = File(database.path + ".encrypting")

    fun prepare(): ByteArray = synchronized(processLock) {
        System.loadLibrary("sqlcipher")
        require(name.matches(Regex("[A-Za-z0-9._-]+")) && name != "." && name != "..")
        val directory = requireNotNull(database.parentFile)
        check(directory.isDirectory || directory.mkdirs())
        val lock = File(context.noBackupFilesDir, "$name.index-migration.lock")
        requireRegularOrAbsent(lock)
        RandomAccessFile(lock, "rw").use { file -> file.channel.lock().use {
            listOf(database, staging).forEach(::requireRegularOrAbsent)
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                requireRegularOrAbsent(File(database.path + suffix))
                requireRegularOrAbsent(File(staging.path + suffix))
            }
            val initialization = File(context.noBackupFilesDir, "$name.index-creating")
            requireRegularOrAbsent(initialization)
            if (!database.exists()) {
                check(listOf("-wal", "-shm", "-journal", ".encrypting").none { File(database.path + it).exists() }) {
                    "Private index main file is missing; existing recovery data retained"
                }
                val keyFile = PrivateIndexKey(context, name).file
                if (!initialization.exists()) {
                    check(!keyFile.exists() && !File(keyFile.path + ".bak").exists()) {
                        "Private index main file is missing; existing device key retained"
                    }
                    check(initialization.createNewFile())
                    RandomAccessFile(initialization, "rw").use { it.fd.sync() }
                    syncDirectory(requireNotNull(initialization.parentFile))
                }
            }
            val plaintext = database.exists() && isPlaintext(database)
            val key = PrivateIndexKey(context, name).loadOrCreate(!database.exists() || plaintext)
            try {
                checkpoint(Stage.KeyDurable)
                if (initialization.exists()) initialize(key, initialization)
                if (plaintext) convert(key)
                else if (database.exists()) {
                    // Wrong key/corruption must fail before Room (or recovery) creates any state.
                    open(database, key, SQLiteDatabase.OPEN_READONLY).use { db ->
                        verifyIntegrity(db)
                    }
                }
                key
            } catch (failure: Throwable) { key.fill(0); throw failure }
        } }
    }

    fun protectKey(authenticatedAlias: String) = synchronized(processLock) {
        val lock = File(context.noBackupFilesDir, "$name.index-migration.lock")
        requireRegularOrAbsent(lock)
        RandomAccessFile(lock, "rw").use { file -> file.channel.lock().use {
            PrivateIndexKey(context, name).protect(authenticatedAlias)
        } }
    }

    private fun initialize(key: ByteArray, marker: File) {
        val fresh = File(database.path + ".initializing")
        requireRegularOrAbsent(fresh)
        if (!database.exists()) {
            // The durable marker exists only before any Room access. A torn initial, empty
            // database may be retried; no previously populated index uses this branch.
            if (fresh.exists()) check(fresh.delete())
            open(fresh, key, SQLiteDatabase.CREATE_IF_NECESSARY).use { it.version = 0 }
            RandomAccessFile(fresh, "rw").use { it.fd.sync() }
            Os.rename(fresh.path, database.path)
            syncDirectory(requireNotNull(database.parentFile))
        }
        open(database, key, SQLiteDatabase.OPEN_READONLY).use { db ->
            verifyIntegrity(db)
            check(db.version == 0)
            db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { check(it.moveToFirst() && it.getInt(0) == 0) }
        }
        check(marker.delete())
        syncDirectory(requireNotNull(marker.parentFile))
    }

    private fun convert(key: ByteArray) {
        // A pre-publication crash leaves only this exact owned encrypted staging file. No plaintext
        // backup is created, and no general directory/container cleanup runs during conversion.
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            val abandoned = File(staging.path + suffix)
            if (abandoned.exists()) check(abandoned.delete()) { "Private index staging is busy" }
        }
        var expected: ByteArray? = null
        var version = 0
        open(database, byteArrayOf(), SQLiteDatabase.OPEN_READWRITE).use { source ->
            version = source.version
            check(version in 1..2) { "Unsupported private index version" }
            source.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getInt(0) == 0) { "Private index WAL is busy" }
            }
            source.rawQuery("PRAGMA journal_mode=DELETE", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0).equals("delete", true))
            }
            // Source is deliberately opened without CREATE_IF_NECESSARY. SQLite propagates
            // that flag to ATTACH, so reserve the exact new destination before attaching it.
            check(staging.createNewFile())
            source.execSQL("ATTACH DATABASE ? AS encrypted KEY ?", arrayOf(staging.path, key))
            try {
                source.beginTransaction()
                try {
                    // A framework TRUNCATE/PERSIST database can leave an inert journal behind.
                    // Cycle its header through a real DELETE-mode transaction, preserving version
                    // and rows, so SQLite itself retires that journal rather than guessing hotness.
                    source.version = version
                    expected = fingerprint(source, "main")
                    source.rawQuery("SELECT sqlcipher_export('encrypted')", null).use { check(it.moveToFirst()) }
                    source.execSQL("PRAGMA encrypted.user_version=$version")
                    source.rawQuery("PRAGMA application_id", null).use {
                        check(it.moveToFirst()); source.execSQL("PRAGMA encrypted.application_id=${it.getInt(0)}")
                    }
                    check(expected.contentEquals(fingerprint(source, "encrypted"))) { "Private index export differs" }
                    source.setTransactionSuccessful()
                } finally { source.endTransaction() }
            } finally { source.execSQL("DETACH DATABASE encrypted") }
        }
        checkpoint(Stage.Exported)
        check(!isPlaintext(staging)) { "Private index export was not encrypted" }
        open(staging, key, SQLiteDatabase.OPEN_READONLY).use { verified ->
            check(verified.version == version && expected.contentEquals(fingerprint(verified, "main")))
            verifyIntegrity(verified)
        }
        RandomAccessFile(staging, "rw").use { it.fd.sync() }
        checkpoint(Stage.Validated)
        // WAL is checkpointed and the DELETE journal closed before replacing its main database.
        // Never attach stale plaintext sidecars to the new encrypted file.
        listOf("-wal", "-shm", "-journal").forEach {
            check(!File(database.path + it).exists() && !File(staging.path + it).exists()) {
                "Private index sidecar is still active: $it"
            }
        }
        Os.rename(staging.path, database.path)
        syncDirectory(requireNotNull(database.parentFile))
        checkpoint(Stage.Published)
    }

    /** Compare every cell, including sqlite_sequence, nullable metadata and historical receipts. */
    private fun fingerprint(db: SQLiteDatabase, schema: String): ByteArray {
        require(schema == "main" || schema == "encrypted")
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(bytes: ByteArray) {
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
        }
        fun rows(query: String) {
            db.rawQuery(query, null).use { cursor ->
                while (cursor.moveToNext()) {
                    digest.update(0x7f.toByte())
                    for (i in 0 until cursor.columnCount) {
                        val type = cursor.getType(i); digest.update(type.toByte())
                        when (type) {
                            Cursor.FIELD_TYPE_NULL -> Unit
                            Cursor.FIELD_TYPE_BLOB -> add(cursor.getBlob(i))
                            Cursor.FIELD_TYPE_INTEGER -> add(ByteBuffer.allocate(8).putLong(cursor.getLong(i)).array())
                            Cursor.FIELD_TYPE_FLOAT -> add(ByteBuffer.allocate(8).putDouble(cursor.getDouble(i)).array())
                            else -> add(cursor.getString(i).toByteArray(Charsets.UTF_8))
                        }
                    }
                }
            }
        }
        rows("SELECT type,name,tbl_name,sql FROM $schema.sqlite_master ORDER BY type,name")
        val tables = mutableListOf<String>()
        db.rawQuery("SELECT name FROM $schema.sqlite_master WHERE type='table' ORDER BY name", null).use {
            while (it.moveToNext()) tables += it.getString(0)
        }
        for (table in tables) {
            check(table in setOf("private_media", "private_album_metadata", "private_restore_receipts", "room_master_table", "sqlite_sequence", "android_metadata")) {
                "Unrecognized private index table"
            }
            add(table.toByteArray()); rows("SELECT * FROM $schema.\"$table\" ORDER BY rowid")
        }
        return digest.digest()
    }

    companion object {
        private val processLock = Any()
        internal val preserveOnCorruption = DatabaseErrorHandler { _, exception -> throw exception }
        private fun open(file: File, key: ByteArray, flags: Int): SQLiteDatabase =
            SQLiteDatabase.openDatabase(file.path, key, null, flags, preserveOnCorruption, null)
        private fun verifyIntegrity(db: SQLiteDatabase) {
            db.rawQuery("PRAGMA integrity_check", null).use {
                check(it.moveToFirst() && it.getString(0) == "ok" && !it.moveToNext())
            }
            db.rawQuery("PRAGMA cipher_integrity_check", null).use { check(!it.moveToFirst()) }
        }
        internal fun isPlaintext(file: File): Boolean = FileInputStream(file).use {
            val header = ByteArray(16)
            it.read(header) == 16 && header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))
        }
        internal fun requireRegularOrAbsent(file: File) {
            try { check(OsConstants.S_ISREG(Os.lstat(file.path).st_mode)) { "Private index path is not a regular file" } }
            catch (missing: android.system.ErrnoException) { if (missing.errno != OsConstants.ENOENT) throw missing }
        }
        internal fun syncDirectory(directory: File) {
            val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
            try { check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)); Os.fsync(fd) } finally { Os.close(fd) }
        }
    }
}
