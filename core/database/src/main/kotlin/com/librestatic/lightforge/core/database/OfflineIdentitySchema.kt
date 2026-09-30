package com.librestatic.lightforge.core.database

import androidx.sqlite.db.SupportSQLiteDatabase

internal object OfflineIdentitySchema {
    fun install(db:SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS pet_state (id INTEGER NOT NULL, revision INTEGER NOT NULL, enabled INTEGER NOT NULL, modelFingerprint TEXT, undoToken TEXT, PRIMARY KEY(id))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS pet_identities (id TEXT NOT NULL, species INTEGER NOT NULL, name TEXT, createdAtMillis INTEGER NOT NULL, PRIMARY KEY(id))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS pet_observations (id TEXT NOT NULL, volumeName TEXT NOT NULL, mediaStoreId INTEGER NOT NULL, generationAdded INTEGER NOT NULL, generationModified INTEGER NOT NULL, modelFingerprint TEXT NOT NULL, `left` REAL NOT NULL, `top` REAL NOT NULL, `right` REAL NOT NULL, `bottom` REAL NOT NULL, species INTEGER NOT NULL, detectorScore REAL NOT NULL, embedding BLOB NOT NULL, identityId TEXT, excluded INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(volumeName,mediaStoreId) REFERENCES media_items(volumeName,mediaStoreId) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(identityId) REFERENCES pet_identities(id) ON UPDATE NO ACTION ON DELETE SET NULL)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS index_pet_observations_volumeName_mediaStoreId ON pet_observations(volumeName,mediaStoreId)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS index_pet_observations_identityId ON pet_observations(identityId)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS index_pet_observations_modelFingerprint_species_id ON pet_observations(modelFingerprint,species,id)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS pet_analysis_stamps (volumeName TEXT NOT NULL, mediaStoreId INTEGER NOT NULL, modelFingerprint TEXT NOT NULL, generationAdded INTEGER NOT NULL, generationModified INTEGER NOT NULL, PRIMARY KEY(volumeName,mediaStoreId,modelFingerprint), FOREIGN KEY(volumeName,mediaStoreId) REFERENCES media_items(volumeName,mediaStoreId) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS pet_undo (token TEXT NOT NULL, revision INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(token))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS peer_import_versions (peerId TEXT NOT NULL, sourceId TEXT NOT NULL, revision TEXT NOT NULL, operationId TEXT NOT NULL, uri TEXT NOT NULL, volumeName TEXT, mediaStoreId INTEGER, generationAdded INTEGER NOT NULL, generationModified INTEGER NOT NULL, sha256 TEXT NOT NULL, sizeBytes INTEGER NOT NULL, modifiedMillis INTEGER NOT NULL, PRIMARY KEY(peerId,sourceId,revision))""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS index_peer_import_versions_operationId ON peer_import_versions(operationId)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS peer_import_sources (peerId TEXT NOT NULL, sourceId TEXT NOT NULL, activeRevision TEXT NOT NULL, modifiedMillis INTEGER NOT NULL, PRIMARY KEY(peerId,sourceId))""")
    }
}
