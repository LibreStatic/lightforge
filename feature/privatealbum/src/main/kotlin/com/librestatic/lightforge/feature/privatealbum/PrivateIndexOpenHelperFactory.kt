package com.librestatic.lightforge.feature.privatealbum

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper

/** Lazy IO: constructing the repository on the UI thread never opens or converts its index. */
internal class PrivateIndexOpenHelperFactory : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        object : SupportSQLiteOpenHelper {
            private var helper: SQLiteOpenHelper? = null
            private var password: ByteArray? = null
            private var wal = false
            private var closed = false
            override val databaseName: String = requireNotNull(configuration.name)

            @Synchronized private fun delegate(): SQLiteOpenHelper {
                check(!closed) { "Private index is closed" }
                helper?.let { return it }
                val key = PrivateIndexStorage(configuration.context, databaseName).prepare()
                try {
                    // The upstream SupportHelper passes a null DatabaseErrorHandler, which deletes
                    // a corrupt database. A vault must retain its evidence and containers instead.
                    return object : SQLiteOpenHelper(
                        configuration.context, databaseName, key, null,
                        configuration.callback.version, 0, PrivateIndexStorage.preserveOnCorruption,
                        null, wal,
                    ) {
                        override fun onConfigure(db: SQLiteDatabase) {
                            db.execSQL("PRAGMA synchronous=FULL")
                            configuration.callback.onConfigure(db)
                        }
                        override fun onCreate(db: SQLiteDatabase) = configuration.callback.onCreate(db)
                        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) =
                            configuration.callback.onUpgrade(db, old, new)
                        override fun onDowngrade(db: SQLiteDatabase, old: Int, new: Int) =
                            configuration.callback.onDowngrade(db, old, new)
                        override fun onOpen(db: SQLiteDatabase) = configuration.callback.onOpen(db)
                    }.also { helper = it; password = key }
                } catch (failure: Throwable) { key.fill(0); throw failure }
            }

            override val writableDatabase: SupportSQLiteDatabase get() = delegate().writableDatabase
            override val readableDatabase: SupportSQLiteDatabase get() = delegate().readableDatabase
            @Synchronized override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
                wal = enabled
                helper?.setWriteAheadLoggingEnabled(enabled)
            }
            @Synchronized override fun close() {
                try { helper?.close() } finally { password?.fill(0); password = null; closed = true }
            }
        }
}
