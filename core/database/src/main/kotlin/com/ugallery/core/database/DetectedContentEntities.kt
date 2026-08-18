package com.ugallery.core.database

import androidx.room.Entity
import androidx.room.Embedded
import androidx.room.Index

@Entity(
    tableName = "media_label_runs",
    primaryKeys = ["volumeName", "mediaStoreId"],
    indices = [Index(name = "index_label_run_version", value = ["modelVersion", "generationModified"])],
)
data class MediaLabelRunEntity(
    val volumeName: String,
    val mediaStoreId: Long,
    val generationModified: Long,
    val modelVersion: String,
    val completedAtMillis: Long,
)

@Entity(
    tableName = "media_labels",
    primaryKeys = ["volumeName", "mediaStoreId", "canonicalLabel"],
    indices = [Index(name = "index_media_label_canonical", value = ["canonicalLabel", "confidence"])],
)
data class MediaLabelEntity(
    val volumeName: String,
    val mediaStoreId: Long,
    val canonicalLabel: String,
    val rawLabel: String,
    val confidence: Float,
    val modelVersion: String,
)

@Entity(tableName = "label_suppressions")
data class LabelSuppressionEntity(
    @androidx.room.PrimaryKey val canonicalLabel: String,
    val suppressedAtMillis: Long,
)

@Entity(
    tableName = "media_ocr",
    primaryKeys = ["volumeName", "mediaStoreId"],
    indices = [Index(name = "index_media_ocr_version", value = ["modelVersion", "generationModified"])],
)
data class MediaOcrEntity(
    val volumeName: String,
    val mediaStoreId: Long,
    val generationModified: Long,
    val modelVersion: String,
    val rawText: String,
    val normalizedText: String,
    val blocksJson: String,
    val completedAtMillis: Long,
)

data class SearchRebuildRow(
    @Embedded val media: MediaItemEntity,
    val ocrText: String?,
    val ocrModelVersion: String?,
    val canonicalLabelsCsv: String?,
    val labelModelVersion: String?,
)

data class PetCollectionSummaryRow(
    val dogCount: Long,
    val catCount: Long,
)
