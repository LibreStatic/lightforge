package com.ugallery.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "semantic_indexes")
data class SemanticIndexEntity(
    @androidx.room.PrimaryKey val indexId: String,
    val modelId: String,
    val modelVersion: String,
    val status: String,
    val embeddedCount: Long,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "semantic_embeddings",
    primaryKeys = ["indexId", "volumeName", "mediaStoreId"],
    foreignKeys = [
        ForeignKey(
            entity = SemanticIndexEntity::class,
            parentColumns = ["indexId"],
            childColumns = ["indexId"],
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
        Index("volumeName", "mediaStoreId"),
        Index("indexId", "lsh0"),
        Index("indexId", "lsh1"),
        Index("indexId", "lsh2"),
        Index("indexId", "lsh3"),
        Index("indexId", "lsh4"),
        Index("indexId", "lsh5"),
        Index("indexId", "lsh6"),
        Index("indexId", "lsh7"),
    ],
)
data class SemanticEmbeddingEntity(
    val indexId: String,
    val volumeName: String,
    val mediaStoreId: Long,
    val generationModified: Long,
    val modelVersion: String,
    val quantizedVector: ByteArray,
    val lsh0: Int,
    val lsh1: Int,
    val lsh2: Int,
    val lsh3: Int,
    val lsh4: Int,
    val lsh5: Int,
    val lsh6: Int,
    val lsh7: Int,
    val completedAtMillis: Long,
)

data class SemanticEmbeddingCandidate(
    val volumeName: String,
    val mediaStoreId: Long,
    val quantizedVector: ByteArray,
    val generationModified: Long,
)

