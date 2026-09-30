package com.librestatic.lightforge.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "similarity_features",
    primaryKeys = ["volumeName", "mediaStoreId"],
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["volumeName", "mediaStoreId"],
            childColumns = ["volumeName", "mediaStoreId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(name = "index_similarity_lsh0", value = ["algorithmVersion", "lsh0"]),
        Index(name = "index_similarity_lsh1", value = ["algorithmVersion", "lsh1"]),
        Index(name = "index_similarity_lsh2", value = ["algorithmVersion", "lsh2"]),
        Index(name = "index_similarity_lsh3", value = ["algorithmVersion", "lsh3"]),
    ],
)
data class SimilarityFeatureEntity(
    val volumeName: String,
    val mediaStoreId: Long,
    val generationModified: Long,
    val algorithmVersion: String,
    val pHash: Long,
    val compactEmbedding: ByteArray,
    val lsh0: Int,
    val lsh1: Int,
    val lsh2: Int,
    val lsh3: Int,
    val blurScore: Float,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "similarity_edges",
    primaryKeys = ["aVolumeName", "aMediaStoreId", "bVolumeName", "bMediaStoreId"],
    foreignKeys = [
        ForeignKey(
            entity = SimilarityFeatureEntity::class,
            parentColumns = ["volumeName", "mediaStoreId"],
            childColumns = ["aVolumeName", "aMediaStoreId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SimilarityFeatureEntity::class,
            parentColumns = ["volumeName", "mediaStoreId"],
            childColumns = ["bVolumeName", "bMediaStoreId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(name = "index_similarity_edge_b", value = ["bVolumeName", "bMediaStoreId"])],
)
data class SimilarityEdgeEntity(
    val aVolumeName: String,
    val aMediaStoreId: Long,
    val bVolumeName: String,
    val bMediaStoreId: Long,
    val score: Float,
)

@Entity(
    tableName = "similarity_memberships",
    primaryKeys = ["volumeName", "mediaStoreId"],
    foreignKeys = [
        ForeignKey(
            entity = SimilarityFeatureEntity::class,
            parentColumns = ["volumeName", "mediaStoreId"],
            childColumns = ["volumeName", "mediaStoreId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(name = "index_similarity_cluster", value = ["clusterId", "bestScore"])],
)
data class SimilarityMembershipEntity(
    val volumeName: String,
    val mediaStoreId: Long,
    val clusterId: String,
    val bestScore: Float,
)

@Entity(
    tableName = "similarity_exclusions",
    primaryKeys = ["volumeName", "mediaStoreId"],
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["volumeName", "mediaStoreId"],
            childColumns = ["volumeName", "mediaStoreId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SimilarityExclusionEntity(
    val volumeName: String,
    val mediaStoreId: Long,
    val excludedAtMillis: Long,
)

data class SimilarityStackRow(
    val clusterId: String,
    val memberCount: Long,
    val bestScore: Float,
    val recommendedVolumeName: String,
    val recommendedMediaStoreId: Long,
)
