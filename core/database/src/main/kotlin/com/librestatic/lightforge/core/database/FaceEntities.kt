package com.librestatic.lightforge.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "face_detection_runs",
    primaryKeys = ["volumeName", "mediaStoreId"],
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["volumeName", "mediaStoreId"],
            childColumns = ["volumeName", "mediaStoreId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(name = "index_face_run_version", value = ["modelVersion", "generationModified"])],
)
data class FaceDetectionRunEntity(
    val volumeName: String,
    val mediaStoreId: Long,
    val generationModified: Long,
    val modelVersion: String,
    val acceptedFaceCount: Int,
    val completedAtMillis: Long,
)

/** Detection geometry only. This table never contains an identity, name, bitmap, or full-size crop. */
@Entity(
    tableName = "detected_faces",
    primaryKeys = ["volumeName", "mediaStoreId", "faceOrdinal"],
    foreignKeys = [
        ForeignKey(
            entity = FaceDetectionRunEntity::class,
            parentColumns = ["volumeName", "mediaStoreId"],
            childColumns = ["volumeName", "mediaStoreId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(name = "index_detected_face_media", value = ["volumeName", "mediaStoreId"])],
)
data class DetectedFaceEntity(
    val volumeName: String,
    val mediaStoreId: Long,
    val faceOrdinal: Int,
    val modelVersion: String,
    val leftPermille: Int,
    val topPermille: Int,
    val rightPermille: Int,
    val bottomPermille: Int,
    val cropLeftPermille: Int,
    val cropTopPermille: Int,
    val cropRightPermille: Int,
    val cropBottomPermille: Int,
    val eulerX: Float,
    val eulerY: Float,
    val eulerZ: Float,
    val qualityScore: Float,
    val landmarksJson: String,
)

/** L2-normalized embedding compacted to exactly 128 signed int8 components. */
@Entity(
    tableName = "face_embeddings",
    primaryKeys = ["volumeName", "mediaStoreId", "faceOrdinal"],
    foreignKeys = [
        ForeignKey(
            entity = DetectedFaceEntity::class,
            parentColumns = ["volumeName", "mediaStoreId", "faceOrdinal"],
            childColumns = ["volumeName", "mediaStoreId", "faceOrdinal"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(name = "index_face_embedding_version", value = ["embeddingModelVersion"]),
        Index(name = "index_face_embedding_media", value = ["volumeName", "mediaStoreId"]),
    ],
)
data class FaceEmbeddingEntity(
    val volumeName: String,
    val mediaStoreId: Long,
    val faceOrdinal: Int,
    val detectionModelVersion: String,
    val embeddingModelVersion: String,
    val quantizedVector: ByteArray,
    val completedAtMillis: Long,
)
