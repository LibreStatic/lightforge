package com.librestatic.lightforge.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Mutation counter; triggers update it only when discovery inputs actually change. */
@Entity(tableName = "moment_discovery_revision")
data class MomentDiscoveryRevisionEntity(@PrimaryKey val id: Int = 1, val revision: Long = 0)

/** Reconciliation ownership survives interruption and is never a replacement for user decisions. */
@Entity(
    tableName = "moment_discovery_seen",
    primaryKeys = ["algorithmVersion", "runId", "momentId"],
    foreignKeys =
        [
            ForeignKey(
                entity = MomentRunEntity::class,
                parentColumns = ["algorithmVersion"],
                childColumns = ["algorithmVersion"],
                onDelete = ForeignKey.CASCADE,
            ),
            ForeignKey(
                entity = MomentEntity::class,
                parentColumns = ["momentId"],
                childColumns = ["momentId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index(value = ["momentId"])],
)
data class MomentDiscoverySeenEntity(
    val algorithmVersion: String,
    val runId: String,
    val momentId: String,
)
