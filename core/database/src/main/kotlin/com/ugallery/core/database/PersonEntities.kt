package com.ugallery.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "person_clusters",
    indices = [Index(name = "index_person_cluster_visible", value = ["algorithmVersion", "isHidden", "updatedAtMillis"])],
)
data class PersonClusterEntity(
    @androidx.room.PrimaryKey val clusterId: String,
    val algorithmVersion: String,
    val centroidVector: ByteArray,
    val memberCount: Int,
    val displayName: String?,
    val isHidden: Boolean,
    val isUserEdited: Boolean,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "person_memberships",
    primaryKeys = ["volumeName", "mediaStoreId", "faceOrdinal"],
    foreignKeys = [
        ForeignKey(
            entity = FaceEmbeddingEntity::class,
            parentColumns = ["volumeName", "mediaStoreId", "faceOrdinal"],
            childColumns = ["volumeName", "mediaStoreId", "faceOrdinal"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PersonClusterEntity::class,
            parentColumns = ["clusterId"],
            childColumns = ["clusterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(name = "index_person_membership_cluster", value = ["clusterId", "similarity"])],
)
data class PersonMembershipEntity(
    val volumeName: String,
    val mediaStoreId: Long,
    val faceOrdinal: Int,
    val clusterId: String,
    val algorithmVersion: String,
    val assignmentSource: String,
    val similarity: Float,
    val assignedAtMillis: Long,
)

@Entity(
    tableName = "person_cluster_projections",
    primaryKeys = ["clusterId", "band"],
    foreignKeys = [
        ForeignKey(
            entity = PersonClusterEntity::class,
            parentColumns = ["clusterId"],
            childColumns = ["clusterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(name = "index_person_projection_lookup", value = ["band", "q0", "q1"])],
)
data class PersonClusterProjectionEntity(
    val clusterId: String,
    val band: Int,
    val q0: Int,
    val q1: Int,
    val q2: Int,
    val q3: Int,
    val q4: Int,
    val q5: Int,
)

@Entity(
    tableName = "person_constraints",
    primaryKeys = [
        "leftVolumeName", "leftMediaStoreId", "leftFaceOrdinal",
        "rightVolumeName", "rightMediaStoreId", "rightFaceOrdinal",
    ],
    foreignKeys = [
        ForeignKey(
            entity = DetectedFaceEntity::class,
            parentColumns = ["volumeName", "mediaStoreId", "faceOrdinal"],
            childColumns = ["leftVolumeName", "leftMediaStoreId", "leftFaceOrdinal"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DetectedFaceEntity::class,
            parentColumns = ["volumeName", "mediaStoreId", "faceOrdinal"],
            childColumns = ["rightVolumeName", "rightMediaStoreId", "rightFaceOrdinal"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(
        name = "index_person_constraint_right",
        value = ["rightVolumeName", "rightMediaStoreId", "rightFaceOrdinal"],
    )],
)
data class PersonConstraintEntity(
    val leftVolumeName: String,
    val leftMediaStoreId: Long,
    val leftFaceOrdinal: Int,
    val rightVolumeName: String,
    val rightMediaStoreId: Long,
    val rightFaceOrdinal: Int,
    val relation: String,
    val preferredClusterId: String?,
    val createdAtMillis: Long,
)

@Entity(
    tableName = "person_face_overrides",
    primaryKeys = ["volumeName", "mediaStoreId", "faceOrdinal"],
    foreignKeys = [
        ForeignKey(
            entity = DetectedFaceEntity::class,
            parentColumns = ["volumeName", "mediaStoreId", "faceOrdinal"],
            childColumns = ["volumeName", "mediaStoreId", "faceOrdinal"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PersonClusterEntity::class,
            parentColumns = ["clusterId"],
            childColumns = ["clusterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(name = "index_person_face_override_cluster", value = ["clusterId"])],
)
data class PersonFaceOverrideEntity(
    val volumeName: String,
    val mediaStoreId: Long,
    val faceOrdinal: Int,
    val clusterId: String,
    val updatedAtMillis: Long,
)

data class PersonConstraintWithMembership(
    val relation: String,
    val otherVolumeName: String,
    val otherMediaStoreId: Long,
    val otherFaceOrdinal: Int,
    val otherClusterId: String?,
    val preferredClusterId: String?,
)

@Entity(tableName = "me_profiles")
data class MeProfileEntity(
    @androidx.room.PrimaryKey val profileId: Int,
    val embeddingModelVersion: String,
    val centroidVector: ByteArray,
    val matchThreshold: Float,
    val referenceCount: Int,
    val state: String,
    val afterVolumeName: String?,
    val afterMediaStoreId: Long?,
    val afterFaceOrdinal: Int?,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "me_references",
    primaryKeys = ["profileId", "volumeName", "mediaStoreId", "faceOrdinal"],
    foreignKeys = [
        ForeignKey(
            entity = MeProfileEntity::class,
            parentColumns = ["profileId"], childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = FaceEmbeddingEntity::class,
            parentColumns = ["volumeName", "mediaStoreId", "faceOrdinal"],
            childColumns = ["volumeName", "mediaStoreId", "faceOrdinal"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(name = "index_me_reference_face", value = ["volumeName", "mediaStoreId", "faceOrdinal"])],
)
data class MeReferenceEntity(
    val profileId: Int,
    val volumeName: String,
    val mediaStoreId: Long,
    val faceOrdinal: Int,
    val addedAtMillis: Long,
)

@Entity(
    tableName = "me_matches",
    primaryKeys = ["profileId", "volumeName", "mediaStoreId", "faceOrdinal"],
    foreignKeys = [
        ForeignKey(
            entity = MeProfileEntity::class,
            parentColumns = ["profileId"], childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = FaceEmbeddingEntity::class,
            parentColumns = ["volumeName", "mediaStoreId", "faceOrdinal"],
            childColumns = ["volumeName", "mediaStoreId", "faceOrdinal"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(name = "index_me_match_score", value = ["profileId", "similarity"])],
)
data class MeMatchEntity(
    val profileId: Int,
    val volumeName: String,
    val mediaStoreId: Long,
    val faceOrdinal: Int,
    val similarity: Float,
    val matchedAtMillis: Long,
)
