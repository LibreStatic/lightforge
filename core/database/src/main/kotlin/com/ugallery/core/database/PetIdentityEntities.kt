package com.ugallery.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A separate opt-in namespace; never joins people or face embeddings. */
@Entity(tableName = "pet_state")
data class PetStateEntity(@PrimaryKey val id: Int = 1, val revision: Long = 0,
    val enabled: Boolean = false, val modelFingerprint: String? = null, val undoToken: String? = null)

@Entity(tableName = "pet_identities")
data class PetIdentityEntity(@PrimaryKey val id: String, val species: Int, val name: String?, val createdAtMillis: Long)

@Entity(tableName = "pet_observations", foreignKeys = [
    ForeignKey(entity=MediaItemEntity::class, parentColumns=["volumeName","mediaStoreId"],
        childColumns=["volumeName","mediaStoreId"], onDelete=ForeignKey.CASCADE),
    ForeignKey(entity=PetIdentityEntity::class, parentColumns=["id"], childColumns=["identityId"], onDelete=ForeignKey.SET_NULL)
], indices=[Index("volumeName","mediaStoreId"),Index("identityId"),Index("modelFingerprint","species","id")])
data class PetObservationEntity(@PrimaryKey val id: String, val volumeName: String, val mediaStoreId: Long,
    val generationAdded: Long, val generationModified: Long, val modelFingerprint: String,
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val species: Int, val detectorScore: Float, val embedding: ByteArray,
    val identityId: String? = null, val excluded: Boolean = false)

@Entity(tableName="pet_analysis_stamps", primaryKeys=["volumeName","mediaStoreId","modelFingerprint"],
    foreignKeys=[ForeignKey(entity=MediaItemEntity::class, parentColumns=["volumeName","mediaStoreId"],
    childColumns=["volumeName","mediaStoreId"],onDelete=ForeignKey.CASCADE)])
data class PetAnalysisStampEntity(val volumeName:String,val mediaStoreId:Long,val modelFingerprint:String,
    val generationAdded:Long,val generationModified:Long)

/** Single bounded latest edit, encoded as pet-only mutable row fields, never media snapshots. */
@Entity(tableName="pet_undo")
data class PetUndoEntity(@PrimaryKey val token:String,val revision:Long,val payload:String)
