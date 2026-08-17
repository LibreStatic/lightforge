package com.ugallery.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "duplicate_hashes",
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
        Index(name = "index_duplicate_sample", value = ["hashVersion", "sizeBytes", "sampleSha256"]),
        Index(name = "index_duplicate_full", value = ["hashVersion", "sizeBytes", "sha256"]),
    ],
)
data class DuplicateHashEntity(
    val volumeName: String,
    val mediaStoreId: Long,
    val generationModified: Long,
    val sizeBytes: Long,
    val hashVersion: String,
    val sampleSha256: String,
    val sha256: String?,
    val updatedAtMillis: Long,
)

data class ExactDuplicateGroupRow(
    val groupId: String,
    val sha256: String,
    val sizeBytes: Long,
    val memberCount: Long,
    val recoverableBytes: Long,
    val recommendedVolumeName: String,
    val recommendedMediaStoreId: Long,
)
