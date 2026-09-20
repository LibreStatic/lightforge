package com.ugallery.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PeerImportDao {
    @Query("SELECT * FROM peer_import_versions WHERE peerId=:peerId AND sourceId=:sourceId AND revision=:revision")
    suspend fun version(peerId:String,sourceId:String,revision:String):PeerImportVersionEntity?
    @Query("SELECT * FROM peer_import_sources WHERE peerId=:peerId AND sourceId=:sourceId")
    suspend fun source(peerId:String,sourceId:String):PeerImportSourceEntity?
    @Query("SELECT * FROM peer_import_versions WHERE operationId=:operationId ORDER BY peerId,sourceId,revision")
    suspend fun operationVersions(operationId:String):List<PeerImportVersionEntity>
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insertVersion(value:PeerImportVersionEntity)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putSource(value:PeerImportSourceEntity)
}
