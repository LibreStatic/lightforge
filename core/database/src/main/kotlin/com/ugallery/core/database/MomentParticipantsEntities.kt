package com.ugallery.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Local identity annotations only; never included in portable biometric-free organization. */
@Entity(tableName = "moment_participant_state", foreignKeys = [ForeignKey(
    entity = MomentEntity::class, parentColumns = ["momentId"], childColumns = ["momentId"],
    onDelete = ForeignKey.CASCADE,
)])
data class MomentParticipantStateEntity(@PrimaryKey val momentId: String, val mode: String, val revision: Long)

@Entity(tableName = "moment_participants", primaryKeys = ["momentId", "clusterId"], foreignKeys = [
    ForeignKey(entity = MomentParticipantStateEntity::class, parentColumns = ["momentId"], childColumns = ["momentId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = PersonClusterEntity::class, parentColumns = ["clusterId"], childColumns = ["clusterId"], onDelete = ForeignKey.CASCADE),
], indices = [Index(value = ["clusterId"])])
data class MomentParticipantEntity(val momentId: String, val clusterId: String)

data class MomentParticipantPersonRow(val clusterId: String, val displayName: String?, val memberCount: Int,
    val coverVolumeName: String, val coverMediaStoreId: Long, val coverGenerationModified: Long)
