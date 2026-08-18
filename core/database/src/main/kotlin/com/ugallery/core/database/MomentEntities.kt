package com.ugallery.core.database

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "moments",
    indices = [
        Index(name = "index_moment_state_updated", value = ["state", "updatedAtMillis"]),
        Index(name = "index_moment_state_start", value = ["state", "startMillis"]),
    ],
)
data class MomentEntity(
    @PrimaryKey val momentId: String,
    val origin: String,
    val state: String,
    val algorithmVersion: String,
    val startMillis: Long,
    val endMillis: Long,
    val title: String?,
    val titleMode: String,
    val isUserEdited: Boolean,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "moment_members",
    primaryKeys = ["momentId", "ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = MomentEntity::class,
            parentColumns = ["momentId"], childColumns = ["momentId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["volumeName", "mediaStoreId"],
            childColumns = ["volumeName", "mediaStoreId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(name = "index_moment_member_key", value = ["volumeName", "mediaStoreId"]),
        Index(name = "index_moment_member_order", value = ["momentId", "ordinal"]),
    ],
)
data class MomentMemberEntity(
    val momentId: String,
    val ordinal: Int,
    val volumeName: String,
    val mediaStoreId: Long,
    val generationModifiedAtSelection: Long,
    val origin: String,
    val score: Float,
)

@Entity(
    tableName = "moment_covers",
    foreignKeys = [
        ForeignKey(
            entity = MomentEntity::class,
            parentColumns = ["momentId"], childColumns = ["momentId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["volumeName", "mediaStoreId"],
            childColumns = ["volumeName", "mediaStoreId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(name = "index_moment_cover_key", value = ["volumeName", "mediaStoreId"])],
)
data class MomentCoverEntity(
    @PrimaryKey val momentId: String,
    val volumeName: String,
    val mediaStoreId: Long,
    val isUserSelected: Boolean,
)

@Entity(tableName = "moment_runs")
data class MomentRunEntity(
    @PrimaryKey val algorithmVersion: String,
    val runId: String,
    val status: String,
    val afterTimelineSortMillis: Long?,
    val afterMediaStoreId: Long?,
    val afterVolumeName: String?,
    val openStartMillis: Long?,
    val openEndMillis: Long?,
    val openLastMillis: Long?,
    val openItemCount: Int,
    val processedItems: Long,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "moment_run_candidates",
    primaryKeys = ["algorithmVersion", "rank"],
    foreignKeys = [
        ForeignKey(
            entity = MomentRunEntity::class,
            parentColumns = ["algorithmVersion"], childColumns = ["algorithmVersion"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["volumeName", "mediaStoreId"],
            childColumns = ["volumeName", "mediaStoreId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(name = "index_moment_run_candidate_key", value = ["volumeName", "mediaStoreId"])],
)
data class MomentRunCandidateEntity(
    val algorithmVersion: String,
    val rank: Int,
    val volumeName: String,
    val mediaStoreId: Long,
    val generationModified: Long,
    val timelineSortMillis: Long,
    val score: Float,
    val timeBucket: Long,
    val visualBucket: String,
)

data class MomentCandidateRow(
    @Embedded val media: MediaItemEntity,
    val latitude: Double?,
    val longitude: Double?,
    val blurScore: Float?,
    val pHash: Long?,
    val similarityClusterId: String?,
)

data class MomentSummaryRow(
    @Embedded val moment: MomentEntity,
    val memberCount: Long,
    val coverVolumeName: String?,
    val coverMediaStoreId: Long?,
)

data class MomentMemberRow(
    @Embedded val member: MomentMemberEntity,
    @Embedded(prefix = "media_") val media: MediaItemEntity,
)
