package com.ugallery.core.database

import androidx.room.Entity
import androidx.room.Index

/** Peer identity is the authenticated certificate digest, independent of address or supplied IDs. */
@Entity(tableName="peer_import_versions",primaryKeys=["peerId","sourceId","revision"],indices=[Index("operationId")])
data class PeerImportVersionEntity(val peerId:String,val sourceId:String,val revision:String,
    val operationId:String,val uri:String,val volumeName:String?,val mediaStoreId:Long?,
    val generationAdded:Long,val generationModified:Long,val sha256:String,val sizeBytes:Long,val modifiedMillis:Long)

/** Use newest selects a logical version. Previous published bytes remain untouched. */
@Entity(tableName="peer_import_sources",primaryKeys=["peerId","sourceId"])
data class PeerImportSourceEntity(val peerId:String,val sourceId:String,val activeRevision:String,val modifiedMillis:Long)
